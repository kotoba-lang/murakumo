"""Run the pinned Qwen3.8-27B benchmark as a RunPod queue worker job."""

import glob
import os
import platform
import time

import runpod
import torch
import vllm
from vllm import LLM, SamplingParams

MODEL = "Qwen/Qwen3.8-27B-FP8"
MODEL_REVISION = "017b9c7af6b5689d5dd426a76e0bc077eb5ca20a"
FLEX_USD_PER_SECOND = 0.00116
CONTAINER_STARTED = time.time()


def _model_path() -> str:
    """Prefer RunPod's unbilled cached-model mount when it is present."""
    pattern = (
        "/runpod-volume/huggingface-cache/hub/"
        "models--Qwen--Qwen3.8-27B-FP8/snapshots/*"
    )
    snapshots = sorted(glob.glob(pattern))
    return snapshots[-1] if snapshots else MODEL


def _prompt(approx_tokens: int) -> str:
    unit = "The fleet control plane assigns work to nodes by measured cost. "
    return (unit * max(1, approx_tokens // 11)).strip()


MODEL_SOURCE = _model_path()
LOAD_STARTED = time.time()
LLM_INSTANCE = LLM(
    model=MODEL_SOURCE,
    revision=None if MODEL_SOURCE != MODEL else MODEL_REVISION,
    max_model_len=65536,
    max_num_seqs=128,
    kv_cache_dtype="fp8",
    gpu_memory_utilization=0.92,
    trust_remote_code=True,
    speculative_config={"method": "mtp", "num_speculative_tokens": 3},
)
LOAD_SECONDS = time.time() - LOAD_STARTED
TOKENIZER = LLM_INSTANCE.get_tokenizer()


def _measure(label: str, count: int, input_tokens: int, output_tokens: int) -> dict:
    prompts = [_prompt(input_tokens)] * count
    real_input = len(TOKENIZER.encode(prompts[0]))
    sampling = SamplingParams(
        temperature=0.0,
        max_tokens=output_tokens,
        min_tokens=output_tokens,
        ignore_eos=True,
    )
    before = time.time()
    outputs = LLM_INSTANCE.generate(prompts, sampling)
    elapsed = time.time() - before
    generated = sum(
        len(output.token_ids) for request in outputs for output in request.outputs
    )
    throughput = generated / elapsed
    return {
        "label": label,
        "status": "measured",
        "concurrency": count,
        "prompt_tokens_each": real_input,
        "generated_tokens_total": generated,
        "wall_seconds": round(elapsed, 2),
        "output_tok_per_s": round(throughput, 1),
        "usd_per_mtok_flex": round(
            FLEX_USD_PER_SECOND / throughput * 1_000_000, 3
        ),
    }


def handler(job: dict) -> dict:
    requested_plan = job.get("input", {}).get("plan", "full")
    started = time.time()
    result = {
        "status": "measured",
        "provider": "RunPod Serverless Flex",
        "rate_usd_per_second": FLEX_USD_PER_SECOND,
        "model": MODEL,
        "model_revision": MODEL_REVISION,
        "model_source": "runpod-cached-model" if MODEL_SOURCE != MODEL else "huggingface",
        "vllm": vllm.__version__,
        "torch": str(torch.__version__),
        "python": platform.python_version(),
        "device": torch.cuda.get_device_name(0),
        "vram_total_gib": round(
            torch.cuda.get_device_properties(0).total_memory / 2**30, 2
        ),
        "mtp": True,
        "max_model_len": 65536,
        "max_num_seqs": 128,
        "kv_cache_dtype": "fp8",
        "gpu_memory_utilization": 0.92,
        "container_to_handler_seconds": round(started - CONTAINER_STARTED, 1),
        "load": {
            "status": "measured",
            "seconds": round(LOAD_SECONDS, 1),
            "usd_flex": round(LOAD_SECONDS * FLEX_USD_PER_SECOND, 4),
        },
        "configs": [],
    }

    _measure("warmup", 1, 128, 16)
    plan = [
        ("single-stream 256in/512out", 1, 256, 512),
    ]
    if requested_plan == "full":
        plan.extend(
            [
                ("concurrency-8 256in/512out", 8, 256, 512),
                ("concurrency-32 256in/512out", 32, 256, 512),
                ("concurrency-64 256in/512out", 64, 256, 512),
                ("concurrency-128 256in/512out", 128, 256, 512),
                ("long-prompt 4k-in/256out", 1, 4096, 256),
                ("long-prompt 32k-in/256out", 1, 32768, 256),
            ]
        )
    for config in plan:
        result["configs"].append(_measure(*config))
    result["benchmark_wall_seconds"] = round(time.time() - started, 1)
    result["worker_billed_proxy_seconds"] = round(
        time.time() - CONTAINER_STARTED, 1
    )
    return result


runpod.serverless.start({"handler": handler})
