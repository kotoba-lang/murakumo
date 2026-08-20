"""Measure Qwen3.8-27B-FP8 on Modal, so the cost table stops being an estimate.

The ladder in ADR-260820 was assembled from other people's llama.cpp numbers
(3090/4090/5090/M5 Max) plus bandwidth-scaled *guesses* for every datacentre
card.  Nobody had run this model on the GPUs the cost table actually
recommends.  This runs it.

    modal run tools/modal-bench/qwen38_27b_bench.py --gpu L40S
    modal run tools/modal-bench/qwen38_27b_bench.py --gpu H100
    modal run tools/modal-bench/qwen38_27b_bench.py --gpu H100 --mtp

Python, not nbb, because the Modal SDK is Python-only -- the same category as
amu's `tools/kexe_loader.c`: a mechanism layer for a platform that offers no
other entry point.  No decision lives here; the script measures and prints.

Every configuration reports one of three statuses, and they are distinguishable
on purpose (CLAUDE.md: a measurement that could not run must not return the
same value as a measurement that ran and found nothing wrong):

    measured           -- it ran, the numbers below are real
    could-not-measure  -- it refused/OOMed/errored; `reason` says why
    skipped            -- deliberately not attempted; `reason` says why

Exit code 2 means "could not answer" (neither pass nor fail).
"""

import json
import os
import re
import sys
import time

import modal

MODEL = "Qwen/Qwen3.8-27B-FP8"
MODEL_REVISION = None  # pinned at first run; see PIN below

# Modal's published rates (modal.com/pricing, read 2026-08-20), $/second.
# Used to turn wall-clock into $/Mtok without a second source of truth.
GPU_USD_PER_SEC = {
    "L40S": 0.000542,
    "H100": 0.001097,
    "H200": 0.001261,
    "A100-80GB": 0.000694,
    "B200": 0.001736,
    "B300": 0.001972,
    "RTX-PRO-6000": 0.000842,
}

# Modal bills CPU and memory SEPARATELY from the GPU -- unlike a RunPod pod,
# where the hourly rate is everything.  At the 8-core/32-GiB shape used here
# that is +32% on top of an L40S and +16% on top of an H100, so a $/token
# figure that counts only the GPU line is wrong on this vendor specifically.
CPU_USD_PER_CORE_SEC = 0.0000131
MEM_USD_PER_GIB_SEC = 0.00000222

# A *devel* CUDA base, not debian_slim.  FlashInfer JIT-compiles its attention
# and sampling kernels at engine init and calls nvcc to do it; debian_slim has
# no nvcc, and the failure surfaces four frames deep as
# "Could not find nvcc and default cuda_home='/usr/local/cuda' doesn't exist"
# during profile_run -- i.e. the engine never starts, on any GPU.  Measured
# 2026-08-20: three separate JIT sites (sampler, decode plan, prefill module),
# so turning them off one env var at a time does not converge.
vllm_image = (
    modal.Image.from_registry(
        "nvidia/cuda:13.0.1-devel-ubuntu24.04", add_python="3.12"
    )
    .pip_install("vllm==0.27.1", "hf_transfer")
    .env(
        {
            "HF_HUB_ENABLE_HF_TRANSFER": "1",
            "VLLM_USE_V1": "1",
            "CUDA_HOME": "/usr/local/cuda",
            # JIT output lands on the cache volume, so only the first cold
            # start on a given GPU pays for it.
            "FLASHINFER_CACHE_DIR": "/root/.cache/vllm/flashinfer",
        }
    )
)

hf_cache = modal.Volume.from_name("qwen38-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("qwen38-vllm-cache", create_if_missing=True)

app = modal.App("qwen38-27b-bench")


def _prompt(approx_tokens: int) -> str:
    """A prompt of roughly `approx_tokens` tokens, deterministic."""
    unit = "The fleet control plane assigns work to nodes by measured cost. "
    # ~11 tokens per unit; overshoot slightly, the real count is reported.
    return (unit * max(1, approx_tokens // 11)).strip()


def _run(gpu: str, mtp: bool, max_model_len: int, max_num_seqs: int,
         eager: bool = False, capture_sizes: str = "",
         text_only: bool = False) -> str:
    import torch
    from vllm import LLM, SamplingParams

    out = {
        "gpu": gpu,
        "model": MODEL,
        "vllm": None,
        "mtp": mtp,
        "max_model_len": max_model_len,
        "max_num_seqs": max_num_seqs,
        "eager": eager,
        "capture_sizes": capture_sizes,
        "text_only": text_only,
        "configs": [],
    }

    import vllm

    out["vllm"] = vllm.__version__
    out["torch"] = str(torch.__version__)
    out["device"] = str(torch.cuda.get_device_name(0))
    out["vram_total_gib"] = round(
        torch.cuda.get_device_properties(0).total_memory / 2**30, 2
    )

    kwargs = dict(
        model=MODEL,
        max_model_len=max_model_len,
        # Gated DeltaNet keeps one recurrent ("Mamba") state block per decode
        # sequence, allocated out of the same pool as the KV cache.  vLLM's
        # default max_num_seqs=256 exceeds what a 48 GiB card has room for at
        # 64k context (measured: 167 blocks on L40S) and the engine refuses to
        # start rather than degrading.  This is the hybrid-attention analogue
        # of a KV-cache ceiling and it binds concurrency, not context.
        max_num_seqs=max_num_seqs,
        kv_cache_dtype="fp8",
        gpu_memory_utilization=0.92,
        enforce_eager=eager,
        trust_remote_code=True,
    )
    if text_only:
        # Qwen3.8-27B is a VL model, and vLLM warms the vision tower on every
        # start (~30 s of a 429 s warm start) plus an encoder cache budget.
        # A text-only deployment does not need either.
        kwargs["limit_mm_per_prompt"] = {"image": 0, "video": 0}
    if capture_sizes:
        # vLLM captures 49 CUDA-graph shapes by default; on a warm H200 that
        # is 126 s of a 429 s start, the largest single phase once the compile
        # cache hits.  Capturing only the batch sizes a deployment actually
        # sees keeps the graphs where they pay and drops the rest.
        kwargs["compilation_config"] = {
            "cudagraph_capture_sizes": [int(x) for x in capture_sizes.split(",")]
        }
    if mtp:
        kwargs["speculative_config"] = {
            "method": "mtp",
            "num_speculative_tokens": 3,
        }

    # vLLM names the Gated DeltaNet recurrent-state ceiling in the exception it
    # raises when max_num_seqs exceeds it.  Ask for the moon, read the ceiling
    # out of the refusal, then retry under it -- so the ceiling gets *reported*
    # for each card instead of being a number someone has to know in advance.
    t0 = time.time()
    llm = None
    for attempt_seqs in (max_num_seqs, None):
        if attempt_seqs is None:
            break
        kwargs["max_num_seqs"] = attempt_seqs
        try:
            llm = LLM(**kwargs)
            out["max_num_seqs_effective"] = attempt_seqs
            break
        except Exception as e:  # noqa: BLE001 -- the reason is the payload
            m = re.search(r"available Mamba cache blocks \((\d+)\)", str(e))
            if m and int(m.group(1)) < attempt_seqs:
                out["mamba_cache_blocks"] = int(m.group(1))
                out["retried_under_mamba_ceiling"] = True
                kwargs["max_num_seqs"] = int(m.group(1))
                try:
                    llm = LLM(**kwargs)
                    out["max_num_seqs_effective"] = int(m.group(1))
                except Exception as e2:  # noqa: BLE001
                    e = e2
            if llm is None:
                out["load"] = {
                    "status": "could-not-measure",
                    "reason": f"{type(e).__name__}: {e}"[:600],
                    "seconds": round(time.time() - t0, 1),
                }
                return json.dumps(out, default=str)
            break
    load_s = time.time() - t0
    out["load"] = {"status": "measured", "seconds": round(load_s, 1)}
    # vLLM logs its own start-time breakdown; capture it so "7 minutes" can be
    # attacked at whichever phase actually owns the time, rather than as a lump.
    try:
        import subprocess
        out["load"]["phases"] = subprocess.run(
            ["bash", "-lc",
             "grep -hoE '(init engine [^)]*\\)|torch.compile took [0-9.]+ s"
             "|Loading .* took [0-9.]+ GiB and [0-9.]+ seconds"
             "|Graph capturing finished in [0-9]+ secs[^,]*)' /proc/1/fd/1 "
             "2>/dev/null | tail -8"],
            capture_output=True, text=True, timeout=10).stdout.strip().split("\n")
        if out["load"]["phases"] == [""]:
            out["load"]["phases"] = ("could-not-measure: vLLM's phase lines go to "
                                     "the container log, not to a file this "
                                     "process can read; grep the modal run log")
    except Exception as e:  # noqa: BLE001
        out["load"]["phases"] = f"unavailable: {type(e).__name__}"
    out["kv_cache_gpu_blocks"] = str(
        getattr(getattr(llm.llm_engine, "cache_config", None), "num_gpu_blocks", None)
    )

    tok = llm.get_tokenizer()

    def measure(label, n_prompts, in_tokens, out_tokens):
        prompts = [_prompt(in_tokens)] * n_prompts
        real_in = len(tok.encode(prompts[0]))
        sp = SamplingParams(
            temperature=0.0,
            max_tokens=out_tokens,
            min_tokens=out_tokens,  # force full length: no early-stop skew
            ignore_eos=True,
        )
        try:
            t = time.time()
            res = llm.generate(prompts, sp)
            wall = time.time() - t
        except Exception as e:  # noqa: BLE001
            return {
                "label": label,
                "status": "could-not-measure",
                "reason": f"{type(e).__name__}: {e}"[:600],
            }
        gen = sum(len(o.token_ids) for r in res for o in r.outputs)
        if gen == 0:
            return {
                "label": label,
                "status": "could-not-measure",
                "reason": "zero tokens generated",
            }
        return {
            "label": label,
            "status": "measured",
            "concurrency": n_prompts,
            "prompt_tokens_each": real_in,
            "generated_tokens_total": gen,
            "wall_seconds": round(wall, 2),
            "output_tok_per_s": round(gen / wall, 1),
        }

    # warm the graphs; result discarded on purpose
    measure("warmup", 1, 128, 16)

    plan = [
        ("single-stream 256in/512out", 1, 256, 512),
        ("concurrency-8 256in/512out", 8, 256, 512),
        ("concurrency-32 256in/512out", 32, 256, 512),
        ("concurrency-64 256in/512out", 64, 256, 512),
        ("concurrency-128 256in/512out", 128, 256, 512),
        ("long-prompt 4k-in/256out", 1, 4096, 256),
        ("long-prompt 32k-in/256out", 1, 32768, 256),
    ]
    for label, n, i, o in plan:
        if i >= max_model_len:
            out["configs"].append(
                {
                    "label": label,
                    "status": "skipped",
                    "reason": f"prompt {i} >= max_model_len {max_model_len}",
                }
            )
            continue
        r = measure(label, n, i, o)
        out["configs"].append(r)
        print(f"  {label}: {r.get('output_tok_per_s', r.get('reason'))}", flush=True)

    gpu_rate = GPU_USD_PER_SEC.get(gpu)
    side_rate = (
        REQ_CPU * CPU_USD_PER_CORE_SEC
        + (REQ_MEM_MIB / 1024) * MEM_USD_PER_GIB_SEC
    )
    out["usd_per_sec"] = {
        "gpu": gpu_rate,
        "cpu_and_memory": round(side_rate, 8),
        "total": round((gpu_rate or 0) + side_rate, 8),
    }
    if gpu_rate:
        for c in out["configs"]:
            if c.get("status") == "measured" and c.get("output_tok_per_s"):
                tps = c["output_tok_per_s"]
                c["usd_per_mtok_gpu_only"] = round(gpu_rate / tps * 1e6, 3)
                c["usd_per_mtok_all_in"] = round((gpu_rate + side_rate) / tps * 1e6, 3)
        # What one cold start costs, in the same currency as the tokens.
        if out["load"].get("status") == "measured":
            out["load"]["usd_all_in"] = round(
                (gpu_rate + side_rate) * out["load"]["seconds"], 3
            )
    return json.dumps(out, default=str)


# One @app.function per GPU, at module scope.  Not a loop and not a factory:
# Modal requires decorated functions to be global (a factory needs
# `serialized=True`, which then demands the local interpreter match the image's
# Python -- 3.14 here vs 3.12 there).  Explicit is what actually deploys.

REQ_CPU = 8
REQ_MEM_MIB = 32768

_COMMON = dict(
    image=vllm_image,
    volumes={"/root/.cache/huggingface": hf_cache, "/root/.cache/vllm": vllm_cache},
    timeout=60 * 60,
    cpu=REQ_CPU,
    memory=REQ_MEM_MIB,
)


@app.function(gpu="L40S", **_COMMON)
def bench_l40s(mtp: bool, max_model_len: int, max_num_seqs: int, eager: bool = False,
               capture_sizes: str = "", text_only: bool = False):
    return _run("L40S", mtp, max_model_len, max_num_seqs, eager, capture_sizes,
                text_only)


@app.function(gpu="H100", **_COMMON)
def bench_h100(mtp: bool, max_model_len: int, max_num_seqs: int, eager: bool = False,
               capture_sizes: str = "", text_only: bool = False):
    return _run("H100", mtp, max_model_len, max_num_seqs, eager, capture_sizes,
                text_only)


@app.function(gpu="H200", **_COMMON)
def bench_h200(mtp: bool, max_model_len: int, max_num_seqs: int, eager: bool = False,
               capture_sizes: str = "", text_only: bool = False):
    return _run("H200", mtp, max_model_len, max_num_seqs, eager, capture_sizes,
                text_only)


@app.function(gpu="A100-80GB", **_COMMON)
def bench_a100(mtp: bool, max_model_len: int, max_num_seqs: int, eager: bool = False,
               capture_sizes: str = "", text_only: bool = False):
    return _run("A100-80GB", mtp, max_model_len, max_num_seqs, eager, capture_sizes,
                text_only)


@app.function(gpu="B200", **_COMMON)
def bench_b200(mtp: bool, max_model_len: int, max_num_seqs: int, eager: bool = False,
               capture_sizes: str = "", text_only: bool = False):
    return _run("B200", mtp, max_model_len, max_num_seqs, eager, capture_sizes,
                text_only)


@app.function(gpu="RTX-PRO-6000", **_COMMON)
def bench_rtx_pro_6000(mtp: bool, max_model_len: int, max_num_seqs: int, eager: bool = False,
               capture_sizes: str = "", text_only: bool = False):
    return _run("RTX-PRO-6000", mtp, max_model_len, max_num_seqs, eager, capture_sizes,
                text_only)


_FNS = {
    "B200": bench_b200,
    "RTX-PRO-6000": bench_rtx_pro_6000,
    "L40S": bench_l40s,
    "H100": bench_h100,
    "H200": bench_h200,
    "A100-80GB": bench_a100,
}


@app.local_entrypoint()
def main(gpu: str = "L40S", mtp: bool = False, max_model_len: int = 65536,
         max_num_seqs: int = 128, eager: bool = False,
         capture_sizes: str = "", text_only: bool = False, out: str = ""):
    fn = _FNS.get(gpu)
    if fn is None:
        print(f"unknown gpu {gpu!r}; known: {sorted(_FNS)}", file=sys.stderr)
        raise SystemExit(2)
    print(f"== {gpu} mtp={mtp} eager={eager} len={max_model_len} seqs={max_num_seqs} ==", flush=True)
    t = time.time()
    res = json.loads(fn.remote(mtp, max_model_len, max_num_seqs, eager, capture_sizes, text_only))
    res["harness_wall_seconds"] = round(time.time() - t, 1)
    blob = json.dumps(res, indent=2, default=str)
    print(blob)
    if out:
        with open(out, "w") as f:
            f.write(blob + "\n")
        print(f"-> {out}", file=sys.stderr)
    measured = [c for c in res["configs"] if c.get("status") == "measured"]
    if not measured:
        print("REFUSING TO REPORT A RESULT: nothing was measured", file=sys.stderr)
        raise SystemExit(2)
