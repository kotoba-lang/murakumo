#!/usr/bin/env python3
"""Combine same-host MLX and llama.cpp measurements without extrapolation."""

import argparse
import json
from pathlib import Path


def one_row(path):
    value = json.loads(Path(path).read_text())
    if not isinstance(value, list) or len(value) != 1:
        raise ValueError(f"expected exactly one llama-bench row in {path}")
    return value[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mlx", required=True)
    parser.add_argument("--gguf-pp", required=True)
    parser.add_argument("--gguf-tg", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    mlx = json.loads(Path(args.mlx).read_text())
    pp = one_row(args.gguf_pp)
    tg = one_row(args.gguf_tg)
    mlx_tg = mlx["throughput"]["completion_tok_s"]
    mlx_pp = mlx["prefill"]["prompt_tok_s"]
    gguf_tg = tg["avg_ts"]
    gguf_pp = pp["avg_ts"]
    evidence = {
        "schema": "murakumo.mishima-backend-comparison/v1",
        "hardware": mlx["hardware"],
        "model_identity": "Ternary Bonsai 2 27B; same source weights, different packing/runtime",
        "mlx": {
            "artifact": "MLX affine 2-bit group-128 with Hadamard runtime",
            "artifact_bytes": mlx["model"]["artifact_bytes"],
            "decode_128_tok_s": mlx_tg,
            "decode_samples_tok_s": mlx["throughput"]["completion_tok_s_samples"],
            "prefill_prompt_tokens": mlx["prefill"]["prompt_tokens"],
            "prefill_tok_s": mlx_pp,
            "prefill_samples_tok_s": mlx["prefill"]["prompt_tok_s_samples"],
            "peak_memory_gb": mlx["throughput"]["peak_memory_gb"],
        },
        "gguf": {
            "artifact": "PTQ1_0 1.75 bpw with Prism llama.cpp Metal",
            "artifact_bytes": tg["model_size"],
            "decode_128_tok_s": gguf_tg,
            "decode_samples_tok_s": tg["samples_ts"],
            "prefill_prompt_tokens": pp["n_prompt"],
            "prefill_tok_s": gguf_pp,
            "prefill_samples_tok_s": pp["samples_ts"],
        },
        "ratios": {
            "mlx_over_gguf_decode": mlx_tg / gguf_tg,
            "mlx_over_gguf_prefill": mlx_pp / gguf_pp,
            "mlx_over_gguf_artifact_bytes": mlx["model"]["artifact_bytes"] / tg["model_size"],
        },
        "qualification": {
            "decode_improves": mlx_tg > gguf_tg,
            "prefill_improves": mlx_pp > gguf_pp,
            "recommended_for_short_chat": mlx_tg > gguf_tg,
            "recommended_for_hermes_long_prompts": mlx_pp > gguf_pp,
        },
        "boundaries": [
            "MLX prefill used exactly 512 prompt tokens plus one generated token; llama-bench pp used 512 prompt tokens and no generation.",
            "Decode comparison is 128 generated tokens on both runtimes after warmup.",
            "Concurrency is qualified separately by mishima-mlx-server-m1max-20260920.json.",
        ],
    }
    Path(args.output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
