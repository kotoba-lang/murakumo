#!/usr/bin/env python3
"""Time one Q8_0 x Q8_0 matvec built three ways on one Mac.

  amu-macos   q8_matvec.kotoba compiled by the Amu selfhost Mac CLI (bin/amu
              compile), run directly as a native arm64 Mach-O
  amu-static  the same source compiled by the pinned static root
              (seed/bootstrap/aarch64-linux-static-root.elf), run as a
              loaderless aarch64-linux image inside podman (optional)
  c-O2, c-O3  q8_matvec.c with the host cc

Every build must print the same checksum (exact integer arithmetic), and a
mutated Kotoba kernel (top byte without sign extension) must print a
different one. Per-matvec time is (t[reps] - t[0]) / reps, so start-up and
weight generation cancel; rounds are interleaved across builds.

  python3 -I run.py --amu ../amu --out result.json [--static] [--reps 300]
"""
import argparse, hashlib, json, os, platform, shutil, statistics, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROWS, COLS = 4864, 896  # Qwen2.5-0.5B ffn_up
# Qwen2.5-0.5B matvec MACs per decoded token: 24 layers x (q, k, v, o, gate,
# up, down) + the tied 151936-row output head.
TOKEN_MACS = 24 * (896 * 896 * 2 + 896 * 128 * 2 + 896 * 4864 * 3) + 896 * 151936


def sha256(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, check=True, **kw).stdout.strip()


def source(reps, mutate=False):
    text = (HERE / "q8_matvec.kotoba.in").read_text()
    text = text.replace("@ROWS@", str(ROWS)).replace("@COLS@", str(COLS)).replace("@REPS@", str(reps))
    if mutate:
        bad = "(* (i64-shift-right w 56) (vector-at x (+ b 7)))"
        assert bad in text
        text = text.replace(bad, "(* (u64-shift-right w 56) (vector-at x (+ b 7)))")
    return text


def timed(argv, env=None):
    t = time.perf_counter()
    out = subprocess.run(argv, capture_output=True, text=True, env=env)
    return time.perf_counter() - t, out.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--amu", required=True, help="Amu checkout holding bin/amu and seed/bootstrap")
    ap.add_argument("--work", default=None, help="build directory (default: a fresh one under $TMPDIR)")
    ap.add_argument("--reps", type=int, default=300)
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--static", action="store_true", help="also build/run the static aarch64-linux image in podman")
    ap.add_argument("--static-reps", type=int, default=20)
    ap.add_argument("--static-rounds", type=int, default=3)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    amu = Path(a.amu).resolve()
    cli = amu / "bin/amu"
    root = amu / "seed/bootstrap/aarch64-linux-static-root.elf"
    work = Path(a.work or os.path.join(os.environ.get("TMPDIR", "/tmp"), f"q8-matvec-{os.getpid()}")).resolve()
    work.mkdir(parents=True, exist_ok=True)
    cc = os.environ.get("CC", "cc")

    builds = {}
    for reps in sorted({0, 1, a.reps} | ({a.static_reps} if a.static else set())):
        (work / f"k{reps}.kotoba").write_text(source(reps))
        exe = work / f"amu-macos-{reps}"
        exe.unlink(missing_ok=True)
        sh([str(cli), "compile", str(work / f"k{reps}.kotoba"), "-o", str(exe)])
        builds[("amu-macos", reps)] = [str(exe)]
        for opt in ("O2", "O3"):
            exe = work / f"c-{opt}-{reps}"
            flags = ["-O2"] if opt == "O2" else ["-O3", "-mcpu=native"]
            sh([cc, *flags, f"-DREPS={reps}", "-o", str(exe), str(HERE / "q8_matvec.c")])
            builds[(f"c-{opt}", reps)] = [str(exe)]
    (work / "mutant.kotoba").write_text(source(1, mutate=True))
    (work / "amu-macos-mutant").unlink(missing_ok=True)
    sh([str(cli), "compile", str(work / "mutant.kotoba"), "-o", str(work / "amu-macos-mutant")])

    # Correctness gate: identical checksum at reps=1 and reps=N, mutant differs.
    check = {}
    for (name, reps), argv in builds.items():
        if reps:
            check.setdefault(reps, {})[name] = timed(argv)[1]
    mutant = timed([str(work / "amu-macos-mutant")])[1]
    ok = all(len(set(v.values())) == 1 for v in check.values()) and mutant != check[1]["c-O2"]
    if not ok:
        print(json.dumps({"check": check, "mutant": mutant}), file=sys.stderr)
        sys.exit("checksum gate failed")

    names = ["amu-macos", "c-O2", "c-O3"]
    samples = {n: {0: [], a.reps: []} for n in names}
    for n in names:  # the first exec of a new binary pays a one-off system scan
        for reps in (0, a.reps):
            timed(builds[(n, reps)])
    load = [os.getloadavg()]
    for _ in range(a.rounds):
        for n in names:
            for reps in (0, a.reps):
                samples[n][reps].append(timed(builds[(n, reps)])[0])
    load.append(os.getloadavg())

    def summary(s, reps):
        per = sorted((t - z) / reps * 1e3 for t, z in zip(s[reps], s[0]))
        med = statistics.median(per)
        return {"ms_per_matvec_median": round(med, 4), "ms_per_matvec_range": [round(per[0], 4), round(per[-1], 4)],
                "gmac_per_s": round(ROWS * COLS / (med * 1e-3) / 1e9, 3),
                "kernel_only_decode_bound_tok_s": round(1.0 / (TOKEN_MACS / (ROWS * COLS) * med * 1e-3), 2),
                "wall_s": {str(k): [round(x, 4) for x in v] for k, v in s.items()}}

    result = {n: summary(samples[n], a.reps) for n in names}

    static = None
    if a.static:
        g = work / "static"
        g.mkdir(exist_ok=True)
        shutil.copy(root, g / "root.elf")
        for reps in (0, a.static_reps):
            (g / f"k{reps}.kotoba").write_text(source(reps))
        script = (
            "set -e; for r in 0 %d; do ./root.elf compile %s/k$r.kotoba --target aarch64-linux "
            "--output %s/k$r.elf --format static >/dev/null; chmod +x k$r.elf; done; "
            "python3 -I -c \"import subprocess,time,os,json\n"
            "env=dict(os.environ,KEXE_STRUCTURED_REPORT='1')\nres={}\n"
            "for i in range(%d):\n"
            " for r in (0,%d):\n"
            "  t=time.perf_counter();o=subprocess.run(['./k%%d.elf'%%r],capture_output=True,text=True,env=env)\n"
            "  res.setdefault(r,[]).append([time.perf_counter()-t,(o.stdout+o.stderr).strip()])\n"
            "print(json.dumps(res))\""
        ) % (a.static_reps, g, g, a.static_rounds, a.static_reps)
        out = sh(["podman", "run", "--rm", "--platform", "linux/arm64", "--network", "none",
                  "-e", f"KEXE_CAP_RESOURCES_35={g}", "-v", f"{g}:{g}", "-w", str(g),
                  "docker.io/library/python:3.12-alpine", "sh", "-c", script])
        raw = json.loads(out.splitlines()[-1])
        s = {int(k): [x[0] for x in v] for k, v in raw.items()}
        static = summary(s, a.static_reps)
        static["report"] = raw[str(a.static_reps)][0][1]
        static["checksum_matches"] = (":result %s " % check[a.static_reps]["c-O2"]) in static["report"]
        static["reps"] = a.static_reps
        result["amu-static"] = static

    doc = {
        "schema": "murakumo.kernel-bench.q8_0-matvec.v1",
        "measured_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "kernel": {"op": "Q8_0 x Q8_0 matvec (ggml_vec_dot_q8_0_q8_0 regime), exact integer scales",
                   "shape": [ROWS, COLS], "shape_from": "Qwen2.5-0.5B blk.*.ffn_up",
                   "weights": "synthetic 31-bit LCG bytes, packed 8 per i64",
                   "token_macs_qwen2_5_0_5b": TOKEN_MACS},
        "reps": a.reps, "rounds": a.rounds,
        "checksum": {"reps_1": check[1]["c-O2"], f"reps_{a.reps}": check[a.reps]["c-O2"],
                     "mutant_reps_1": mutant, "gate": "all builds equal; mutant differs"},
        "builds": {
            "amu-macos": {"cli": str(cli.relative_to(amu)), "cli_sha256": sha256(cli),
                          "amu_head": sh(["git", "-C", str(amu), "rev-parse", "HEAD"])},
            "amu-static": {"root": str(root.relative_to(amu)), "root_sha256": sha256(root)} if a.static else None,
            "c": {"cc": sh([cc, "--version"]).splitlines()[0], "O2": "-O2", "O3": "-O3 -mcpu=native"},
        },
        "host": {"machine": platform.machine(), "cpu": sh(["sysctl", "-n", "machdep.cpu.brand_string"]),
                 "memsize": int(sh(["sysctl", "-n", "hw.memsize"])), "os": platform.mac_ver()[0],
                 "loadavg_start_end": load},
        "results": result,
        "non_claims": [
            "One kernel, not tokens: kernel_only_decode_bound_tok_s divides the per-token matvec MACs by this "
            "kernel's rate and ignores attention, norms, sampling, memory traffic beyond these weights and threads.",
            "Single thread for every build; llama.cpp's CPU path uses NEON sdot and 8 threads.",
            "Synthetic weights; no GGUF is read by the Kotoba builds.",
        ],
    }
    Path(a.out).write_text(json.dumps(doc, indent=2) + "\n")
    print(json.dumps({k: v["ms_per_matvec_median"] for k, v in result.items()}))


if __name__ == "__main__":
    main()
