#!/usr/bin/env python3
"""Fold one bench/results/<run>/ directory into summary.json.

Reads raw/llama-{metal,cpu,cpu1t}-r*.json (llama-bench -o json), raw/mlx-r*.txt
(mlx_lm.benchmark) and q8-matvec-*.json (kernels/q8_0-matvec/run.py), and
writes the per-engine median and range of the round means.

  python3 -I bench/summarize.py bench/results/2026-11-03-m1max
"""
import json, re, statistics, sys
from pathlib import Path


def stat(xs):
    xs = sorted(xs)
    return {"median": round(statistics.median(xs), 1), "range": [round(xs[0], 1), round(xs[-1], 1)], "rounds": len(xs)}


def main(run):
    run = Path(run)
    out = {"run": run.name, "engines": {}}
    for kind, label in (("metal", "llama.cpp Metal"), ("cpu", "llama.cpp CPU, 8 threads"),
                        ("cpu1t", "llama.cpp CPU, 1 thread")):
        pp, tg, meta, shape = [], [], {}, {}
        for f in sorted(run.glob(f"raw/llama-{kind}-r*.json")):
            for r in json.loads(f.read_text()):
                (pp if r["n_prompt"] else tg).append(r["avg_ts"])
                shape["n_prompt" if r["n_prompt"] else "n_gen"] = r["n_prompt"] or r["n_gen"]
                meta = {"build": f"{r['build_commit']} ({r['build_number']})", "backends": r["backends"],
                        "n_gpu_layers": r["n_gpu_layers"], "n_threads": r["n_threads"],
                        "model": r["model_filename"], "model_type": r["model_type"]}
        if pp:
            out["engines"][label] = {"prefill_tok_s": stat(pp), "decode_tok_s": stat(tg), **shape, **meta}
    pp, tg = [], []
    for f in sorted(run.glob("raw/mlx-r*.txt")):
        m = re.search(r"Averages: prompt_tps=([\d.]+), generation_tps=([\d.]+)", f.read_text())
        assert m, f
        pp.append(float(m.group(1)))
        tg.append(float(m.group(2)))
    if pp:
        out["engines"]["MLX (mlx_lm.benchmark)"] = {"prefill_tok_s": stat(pp), "decode_tok_s": stat(tg),
                                                     "n_prompt": 512, "n_gen": 128}
    for f in run.glob("q8-matvec-*.json"):
        k = json.loads(f.read_text())
        out["kernel_q8_0_matvec"] = {
            name: {key: v[key] for key in ("ms_per_matvec_median", "ms_per_matvec_range", "gmac_per_s",
                                           "kernel_only_decode_bound_tok_s")}
            for name, v in k["results"].items()}
        out["kernel_q8_0_matvec"]["checksum_gate"] = k["checksum"]["gate"]
    (run / "summary.json").write_text(json.dumps(out, indent=2) + "\n")
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main(sys.argv[1])
