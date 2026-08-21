"""Run the Qwen3.8-27B serving ladder on an already-provisioned GPU VM."""

import argparse
import json
import platform
import time

import torch
import vllm
from vllm import LLM, SamplingParams

MODEL = "Qwen/Qwen3.8-27B-FP8"
MODEL_REVISION = "017b9c7af6b5689d5dd426a76e0bc077eb5ca20a"


def prompt(approx_tokens: int) -> str:
    unit = "The fleet control plane assigns work to nodes by measured cost. "
    return (unit * max(1, approx_tokens // 11)).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--hourly-usd", type=float, required=True)
    parser.add_argument("--provider", default="Hyperstack")
    parser.add_argument("--flavor", required=True)
    parser.add_argument("--max-model-len", type=int, default=65536)
    parser.add_argument("--max-num-seqs", type=int, default=128)
    args = parser.parse_args()

    result = {
        "status": "measured",
        "provider": args.provider,
        "flavor": args.flavor,
        "hourly_usd": args.hourly_usd,
        "model": MODEL,
        "model_revision": MODEL_REVISION,
        "vllm": vllm.__version__,
        "torch": str(torch.__version__),
        "python": platform.python_version(),
        "device": torch.cuda.get_device_name(0),
        "vram_total_gib": round(
            torch.cuda.get_device_properties(0).total_memory / 2**30, 2
        ),
        "mtp": True,
        "max_model_len": args.max_model_len,
        "max_num_seqs": args.max_num_seqs,
        "kv_cache_dtype": "fp8",
        "gpu_memory_utilization": 0.92,
        "configs": [],
    }

    started = time.time()
    try:
        llm = LLM(
            model=MODEL,
            revision=MODEL_REVISION,
            max_model_len=args.max_model_len,
            max_num_seqs=args.max_num_seqs,
            kv_cache_dtype="fp8",
            gpu_memory_utilization=0.92,
            trust_remote_code=True,
            speculative_config={"method": "mtp", "num_speculative_tokens": 3},
        )
    except Exception as exc:  # the failure reason is measurement data
        result["status"] = "could-not-measure"
        result["load"] = {
            "status": "could-not-measure",
            "seconds": round(time.time() - started, 1),
            "reason": f"{type(exc).__name__}: {exc}"[:2000],
        }
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(result, handle, indent=2)
            handle.write("\n")
        return 2

    load_seconds = time.time() - started
    result["load"] = {
        "status": "measured",
        "seconds": round(load_seconds, 1),
        "usd": round(load_seconds * args.hourly_usd / 3600, 4),
    }
    result["max_num_seqs_effective"] = args.max_num_seqs
    result["kv_cache_gpu_blocks"] = str(
        getattr(getattr(llm.llm_engine, "cache_config", None), "num_gpu_blocks", None)
    )
    tokenizer = llm.get_tokenizer()

    def measure(label: str, count: int, input_tokens: int, output_tokens: int):
        prompts = [prompt(input_tokens)] * count
        real_input = len(tokenizer.encode(prompts[0]))
        sampling = SamplingParams(
            temperature=0.0,
            max_tokens=output_tokens,
            min_tokens=output_tokens,
            ignore_eos=True,
        )
        try:
            before = time.time()
            outputs = llm.generate(prompts, sampling)
            elapsed = time.time() - before
            generated = sum(
                len(output.token_ids) for request in outputs for output in request.outputs
            )
            tokens_per_second = generated / elapsed
            return {
                "label": label,
                "status": "measured",
                "concurrency": count,
                "prompt_tokens_each": real_input,
                "generated_tokens_total": generated,
                "wall_seconds": round(elapsed, 2),
                "output_tok_per_s": round(tokens_per_second, 1),
                "usd_per_mtok_all_in": round(
                    args.hourly_usd / 3600 / tokens_per_second * 1_000_000, 3
                ),
            }
        except Exception as exc:  # the failure reason is measurement data
            return {
                "label": label,
                "status": "could-not-measure",
                "reason": f"{type(exc).__name__}: {exc}"[:2000],
            }

    measure("warmup", 1, 128, 16)
    for config in (
        ("single-stream 256in/512out", 1, 256, 512),
        ("concurrency-8 256in/512out", 8, 256, 512),
        ("concurrency-32 256in/512out", 32, 256, 512),
        ("concurrency-64 256in/512out", 64, 256, 512),
        ("concurrency-128 256in/512out", 128, 256, 512),
        ("long-prompt 4k-in/256out", 1, 4096, 256),
        ("long-prompt 32k-in/256out", 1, 32768, 256),
    ):
        row = measure(*config)
        result["configs"].append(row)
        print(json.dumps(row), flush=True)

    result["benchmark_wall_seconds"] = round(time.time() - started, 1)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2)
        handle.write("\n")
    return 0 if any(row["status"] == "measured" for row in result["configs"]) else 2


if __name__ == "__main__":
    raise SystemExit(main())
