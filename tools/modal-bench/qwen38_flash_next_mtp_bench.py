"""Measure MTP (`--spec-type draft-mtp`) decode for Qwen3.8-Flash-Next
(uncensored, IQ4_XS) on a SEPARATE node, so the production endpoint of
app-kotoba-cloud ADR 2609160940 is never touched.

    modal run tools/modal-bench/qwen38_flash_next_mtp_bench.py::download_models
    modal run tools/modal-bench/qwen38_flash_next_mtp_bench.py

The node is a Modal RTX-PRO-6000 — the same card the production endpoint runs
on — with llama.cpp built from the still-open MTP pull request (#28243, head
pinned below; it stacks #27836).  The platform image of the production
endpoint tracks llama.cpp master, which has no MTP path yet, and a custom
image from an unmerged branch is not a production pin.  This script exists
to answer one question before that merge: what does draft-mtp buy on this
model on this card.

Python, not nbb: the Modal SDK is Python-only (same category as
qwen38_27b_bench.py).  No decision lives here; it measures and prints.

Every cell reports one of three statuses (CLAUDE.md: a measurement that could
not run must not look like one that ran):

    measured           -- it ran; the numbers are llama-server's own timings
    could-not-measure  -- the server refused/OOMed/errored; `reason` says why
    skipped            -- deliberately not attempted; `reason` says why

Exit code 2 means "could not answer".
"""

import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import modal

APP_NAME = "murakumo-qwen38-flash-next-mtp-bench"
HF_REPO = "orcarouter/Qwen3.8-Flash-Next-Uncensored-GGUF"
HF_REVISION = "0434906af7b5202b676d43f108cf4f73d25691ef"  # ADR 2609160940
TARGET_FILES = [
    "Qwen3.8-Flash-Next-Uncensored-IQ4_XS-00001-of-00003.gguf",
    "Qwen3.8-Flash-Next-Uncensored-IQ4_XS-00002-of-00003.gguf",
    "Qwen3.8-Flash-Next-Uncensored-IQ4_XS-00003-of-00003.gguf",
]
DRAFT_FILE = "Qwen3.8-Flash-Next-Uncensored-MTP-draft.gguf"
# sha256 from the Hub's LFS metadata (api/models/...?blobs=true), 2026-09-16
SHA256 = {
    TARGET_FILES[0]: "28a99b125cb905fc3bdc06baf6266a56b0cbd1b27e833b9142acc2017b673704",
    TARGET_FILES[1]: "2a309e0b112fde96ba3bcba5a6b58cc05e5df7bb7fad5a990eaa51df335b0e43",
    TARGET_FILES[2]: "ebc43c58e2eaeba1d5bdf62c8cb1f0eac198c4dc01941f771921edeebf574bc3",
    DRAFT_FILE: "ff803fb437e90567c27991fecae2537ea1ed63e8842b0399aa53820f02ae8882",
}
# ggml-org/llama.cpp PR #28243 "models: Qwen3.8-Flash-Next MTP", head on
# 2026-09-16 (branch qwen4exp/mtp of danielhanchen/llama.cpp; not mergeable
# against master that day).  Pinned so the measurement names its code.
LLAMA_PR = 28243
LLAMA_PR_HEAD = "d1a92352cbd417fd840b4e765c0b82f5fe3d1d89"
ALIAS = "qwen3.8-flash-next-uncensored-iq4-xs"

MODEL_DIR = Path("/models")
volume = modal.Volume.from_name("qwen38-flash-next-uncensored-gguf", create_if_missing=True)
hf_secret = modal.Secret.from_name("hf-token")

image = (
    modal.Image.from_registry("nvidia/cuda:12.8.1-devel-ubuntu24.04", add_python="3.12")
    .apt_install("build-essential", "ca-certificates", "cmake", "curl", "git", "libcurl4-openssl-dev")
    .pip_install("huggingface_hub==0.34.4", "hf_transfer")
    .env({"HF_HUB_ENABLE_HF_TRANSFER": "1"})
    .run_commands(
        "git clone https://github.com/ggml-org/llama.cpp /opt/llama.cpp",
        f"cd /opt/llama.cpp && git fetch origin pull/{LLAMA_PR}/head:pr-{LLAMA_PR} && git checkout {LLAMA_PR_HEAD}",
        "cmake -S /opt/llama.cpp -B /opt/llama.cpp/build "
        "-DGGML_CUDA=ON -DLLAMA_CURL=ON -DCMAKE_BUILD_TYPE=Release "
        # RTX PRO 6000 only (sm_120a); see qwen38_fastmtp_server.py for why.
        "-DCMAKE_CUDA_ARCHITECTURES=120a-real "
        "-DCMAKE_EXE_LINKER_FLAGS='-Wl,--allow-shlib-undefined'",
        "cmake --build /opt/llama.cpp/build --config Release -j --target llama-server",
        "cd /opt/llama.cpp && git rev-parse HEAD > /opt/llama.cpp/BUILT_COMMIT",
    )
)

app = modal.App(APP_NAME)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@app.function(image=image, volumes={str(MODEL_DIR): volume}, secrets=[hf_secret],
              cpu=4, memory=8192, timeout=7200)
def download_models() -> dict:
    """Pin, download and checksum the three IQ4_XS shards and the MTP draft."""
    from huggingface_hub import hf_hub_download

    result = {}
    for filename, expected in SHA256.items():
        target = MODEL_DIR / filename
        if target.exists() and target.stat().st_size > 0 and _sha256(target) == expected:
            result[filename] = {"bytes": target.stat().st_size, "sha256": expected, "cached": True}
            continue
        downloaded = Path(hf_hub_download(repo_id=HF_REPO, filename=filename,
                                          revision=HF_REVISION, local_dir=MODEL_DIR))
        actual = _sha256(downloaded)
        if actual != expected:
            raise RuntimeError(f"checksum mismatch for {filename}: {actual}")
        result[filename] = {"bytes": downloaded.stat().st_size, "sha256": actual, "cached": False}
        volume.commit()
    return {"revision": HF_REVISION, "files": result}


# ---------------------------------------------------------------- the probes
def _post(path: str, body: dict, timeout: float = 1800) -> dict:
    req = urllib.request.Request("http://127.0.0.1:8000" + path, data=json.dumps(body).encode(),
                                 headers={"content-type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def _corpus(chars: int) -> str:
    """The same text the production probes used: llama.cpp's own sources."""
    parts = []
    for rel in ("common/arg.cpp", "src/llama-model.cpp", "tools/server/README.md",
                "src/llama-arch.cpp", "common/common.h"):
        p = Path("/opt/llama.cpp") / rel
        if p.exists():
            parts.append(p.read_text(encoding="utf-8", errors="replace"))
    text = "\n\n".join(parts)
    while len(text) < chars:
        text += "\n\n" + text
    return text[:chars]


def _probe(label: str, prompt: str, max_tokens: int, greedy: bool) -> dict:
    body = {"model": ALIAS, "stream": False, "max_tokens": max_tokens,
            "messages": [{"role": "user", "content": prompt}],
            "ignore_eos": True}
    if greedy:
        body["temperature"] = 0
        body["top_k"] = 1
    t0 = time.time()
    try:
        d = _post("/v1/chat/completions", body)
    except urllib.error.HTTPError as e:
        return {"label": label, "status": "could-not-measure", "reason": f"HTTP {e.code}: {e.read()[:200]!r}"}
    except Exception as e:  # noqa: BLE001
        return {"label": label, "status": "could-not-measure", "reason": repr(e)}
    t = d.get("timings") or {}
    m = d["choices"][0]["message"]
    out = {"label": label, "status": "measured", "wall_s": round(time.time() - t0, 2),
           "prompt_n": t.get("prompt_n"), "prefill_tps": round(t.get("prompt_per_second", 0), 1),
           "cache_n": t.get("cache_n"),
           "predicted_n": t.get("predicted_n"), "decode_tps": round(t.get("predicted_per_second", 0), 1),
           "finish": d["choices"][0].get("finish_reason"),
           "content_chars": len(m.get("content") or ""), "reasoning_chars": len(m.get("reasoning_content") or "")}
    for k in ("draft_n", "draft_n_accepted"):
        if k in t:
            out[k] = t[k]
    if out.get("draft_n"):
        out["draft_acceptance"] = round(out["draft_n_accepted"] / out["draft_n"], 3)
    return out


def _wait_health(proc: subprocess.Popen, timeout_s: float = 1200) -> str | None:
    """None when healthy; otherwise the reason it is not."""
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if proc.poll() is not None:
            return f"llama-server exited with {proc.returncode}"
        try:
            with urllib.request.urlopen("http://127.0.0.1:8000/health", timeout=5) as r:
                if r.status == 200:
                    return None
        except Exception:  # noqa: BLE001
            pass
        time.sleep(3)
    return f"no /health 200 within {timeout_s:.0f} s"


def _server_command(mtp: bool) -> list[str]:
    cmd = [
        "/opt/llama.cpp/build/bin/llama-server",
        "--host", "127.0.0.1", "--port", "8000",
        "--model", str(MODEL_DIR / TARGET_FILES[0]),
        "--alias", ALIAS,
        "--n-gpu-layers", "all",
        "--ctx-size", "131072",
        "--parallel", "1",
        # the production endpoint's values (ADR 2609160940)
        "--batch-size", "4096",
        "--ubatch-size", "2048",
        "--threads", "16",
        "--flash-attn", "auto",
        "--jinja",
        "--reasoning-budget", "1024",
    ]
    if mtp:
        cmd += [
            "--spec-draft-model", str(MODEL_DIR / DRAFT_FILE),
            "--spec-draft-ngl", "all",
            "--spec-type", "draft-mtp",
            "--spec-draft-n-max", "3",
            "--spec-draft-p-min", "0",
        ]
    return cmd


@app.function(image=image, gpu="RTX-PRO-6000", cpu=16, memory=65536,
              volumes={str(MODEL_DIR): volume}, timeout=3 * 3600)
def bench(contexts: str = "14000,60000", max_tokens: int = 256) -> dict:
    """Baseline vs draft-mtp on one node, same prompts, greedy and sampled."""
    built = Path("/opt/llama.cpp/BUILT_COMMIT").read_text().strip()
    report = {"node": "modal RTX-PRO-6000", "llama_cpp": {"pr": LLAMA_PR, "commit": built},
              "model": {"repo": HF_REPO, "revision": HF_REVISION, "quant": "IQ4_XS", "draft": DRAFT_FILE},
              "modes": {}}
    for filename in [*TARGET_FILES, DRAFT_FILE]:
        if not (MODEL_DIR / filename).exists():
            report["status"] = "could-not-measure"
            report["reason"] = f"{filename} missing from the volume; run ::download_models first"
            return report
    approx_tokens = [int(x) for x in contexts.split(",")]
    for mode in ("baseline", "draft-mtp"):
        log = open(f"/tmp/llama-server-{mode}.log", "wb")
        proc = subprocess.Popen(_server_command(mode == "draft-mtp"), stdout=log, stderr=subprocess.STDOUT)
        t_start = time.time()
        why = _wait_health(proc)
        if why is not None:
            tail = Path(f"/tmp/llama-server-{mode}.log").read_bytes()[-3000:].decode("utf-8", "replace")
            report["modes"][mode] = {"status": "could-not-measure", "reason": why, "log_tail": tail}
            proc.kill()
            continue
        cells = {"status": "measured", "load_s": round(time.time() - t_start, 1), "probes": []}
        # short warm-up and short-context decode
        for greedy in (True, False):
            cells["probes"].append(_probe(f"short/{'greedy' if greedy else 'sampled'}",
                                          "Explain in detail what an IDOR vulnerability is and how to fix it.",
                                          max_tokens, greedy))
        for n in approx_tokens:
            text = _corpus(int(n * 3.7))  # ~3.7 chars per token on this corpus (measured 2026-09-16)
            prompt = "Here is a source tree to review:\n\n" + text + "\n\nList the most security-relevant functions you saw and explain each."
            for greedy in (True, False):
                cells["probes"].append(_probe(f"ctx~{n}/{'greedy' if greedy else 'sampled'}", prompt, max_tokens, greedy))
        proc.terminate()
        try:
            proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            proc.kill()
        report["modes"][mode] = cells
        time.sleep(5)
    b = report["modes"].get("baseline", {}); m = report["modes"].get("draft-mtp", {})
    if b.get("status") == "measured" and m.get("status") == "measured":
        ratios = []
        for pb, pm in zip(b["probes"], m["probes"]):
            if pb["status"] == "measured" and pm["status"] == "measured" and pb["decode_tps"]:
                ratios.append({"label": pb["label"], "baseline_tps": pb["decode_tps"], "mtp_tps": pm["decode_tps"],
                               "speedup": round(pm["decode_tps"] / pb["decode_tps"], 2),
                               "acceptance": pm.get("draft_acceptance")})
        report["speedup"] = ratios
        report["status"] = "measured"
    else:
        report["status"] = "could-not-measure"
    return report


@app.local_entrypoint()
def main(contexts: str = "14000,60000", max_tokens: int = 256) -> None:
    report = bench.remote(contexts=contexts, max_tokens=max_tokens)
    print(json.dumps(report, indent=1, ensure_ascii=False))
    if report.get("status") != "measured":
        sys.exit(2)
