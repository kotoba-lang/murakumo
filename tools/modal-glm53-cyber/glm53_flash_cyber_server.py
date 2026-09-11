"""Self-host dealignai/GLM-5.3-Flash-CYBERSECURITY-W4A16 on Modal (TP2, 2x H200).

    # 1. pull the 195 GB of safetensors onto a Volume once, on a CPU container
    modal run tools/modal-glm53-cyber/glm53_flash_cyber_server.py::download
    # 2. deploy the scale-to-zero vLLM origin
    modal deploy tools/modal-glm53-cyber/glm53_flash_cyber_server.py

Run both from the repository root: the origin proxy is added by a path
relative to it.  The public boundary stays api.murakumo.cloud; this private
origin accepts only the gateway-held bearer (origin_proxy.py, shared with
tools/modal-throughput) and keeps vLLM on localhost.

Why this model and these numbers.  It is the same author's CRACK of the
smaller GLM-5.3-Flash base (glm5_next: 45 layers, 288 routed experts, KDA
linear attention + DeepSeek-sparse attention, MTP head, vision tower) at
W4A16 — 194.7 GB across 120 shards — which fits 2x H200 (282 GB) at a quarter
of the 8x H200 the 753B FP8 sibling needs.  The architecture is only in vLLM
main, not in any 0.2x release, so the image is the official
vllm/vllm-openai:glm53-flash tag the model card names as its production
image.  The serve flags are the card's recommended production profile (MTP
works on this arch, unlike the 753B one).
"""

import os
import subprocess
import threading

import modal

APP_NAME = "murakumo-glm53-flash-cyber"
MODEL_ID = "glm-5.3-flash-cybersecurity-w4a16"
MODEL = "dealignai/GLM-5.3-Flash-CYBERSECURITY-W4A16"
MODEL_REVISION = "c95e33010569089226763057136edfcfba488065"
HF_CACHE = "/root/.cache/huggingface"
SNAPSHOT = (
    f"{HF_CACHE}/hub/models--{MODEL.replace('/', '--')}/snapshots/{MODEL_REVISION}"
)
LOCAL_MODEL = "/tmp/model"
STAGE_WORKERS = 16

image = (
    # The image has no bare `python`; Modal ships its own interpreter, which
    # runs the proxy and the download.  vLLM stays on the image's venv.
    modal.Image.from_registry(
        "vllm/vllm-openai:glm53-flash-x86_64-cu130", add_python="3.12"
    )
    .entrypoint([])
    .pip_install(
        "huggingface_hub[hf_transfer]", "fastapi", "uvicorn", "httpx==0.28.1"
    )
    .env(
        {
            "HF_XET_HIGH_PERFORMANCE": "1",
            "HF_HUB_ENABLE_HF_TRANSFER": "1",
            "FLASHINFER_CACHE_DIR": "/root/.cache/vllm/flashinfer",
            # The first cold start logged thousands of CUDACachingAllocator
            # OOM-and-retry warnings during weight load (20 MiB allocations
            # failing with 15 MiB free while the pool was fully reserved):
            # fragmentation, which expandable segments is the documented fix
            # for.  Measured effect is in ADR-260911.
            "PYTORCH_CUDA_ALLOC_CONF": "expandable_segments:True",
        }
    )
    .add_local_file(
        "tools/modal-throughput/origin_proxy.py", "/root/origin_proxy.py"
    )
)

hf_cache = modal.Volume.from_name("glm53-flash-cyber-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("glm53-flash-cyber-vllm-cache", create_if_missing=True)
origin_secret = modal.Secret.from_name("murakumo-modal-origin")
app = modal.App(APP_NAME)


@app.function(
    image=image,
    cpu=16,
    memory=32768,
    volumes={HF_CACHE: hf_cache},
    timeout=4 * 3600,
)
def download() -> None:
    """Populate the HF cache Volume so the GPU container never downloads."""
    from huggingface_hub import snapshot_download

    path = snapshot_download(
        MODEL,
        revision=MODEL_REVISION,
        allow_patterns=["*.safetensors", "*.json", "*.jinja", "*.txt", "*.py"],
        max_workers=16,
    )
    hf_cache.commit()
    shards = [f for f in os.listdir(path) if f.endswith(".safetensors")]
    total = sum(os.path.getsize(os.path.join(path, f)) for f in shards)
    print(f"downloaded {len(shards)} shards, {total / 1e9:.1f} GB -> {path}")


STAGE_SCRIPT = """
import os, shutil, sys, time
from concurrent.futures import ThreadPoolExecutor
src, dst, workers = sys.argv[1], sys.argv[2], int(sys.argv[3])
os.makedirs(dst, exist_ok=True)
files = sorted(os.listdir(src))
def copy(name):
    s, d = os.path.join(src, name), os.path.join(dst, name)
    if not name.endswith(".safetensors"):
        shutil.copyfile(s, d)
        return os.path.getsize(d)
    n = 0
    with open(s, "rb", buffering=0) as i, open(d, "wb") as o:
        while chunk := i.read(64 << 20):
            o.write(chunk)
            n += len(chunk)
    return n
t0 = time.monotonic()
with ThreadPoolExecutor(workers) as ex:
    total = sum(ex.map(copy, files))
dt = time.monotonic() - t0
print(f"staged {len(files)} files, {total / 1e9:.1f} GB in {dt:.0f}s "
      f"({total / 1e9 / dt:.2f} GB/s) -> {dst}", flush=True)
"""


def stage_weights() -> None:
    """Copy the snapshot from the Volume to local NVMe with parallel readers.

    Measured 2026-09-11: vLLM reading shard-by-shard through the 9P Volume
    mount loaded at ~0.11 GB/s (1,700 s for 195 GB, GPU allocator thrashing
    the whole time); parallel readers pull the same Volume at 0.5-1.4 GB/s.

    The copy runs in a *subprocess*.  When it ran as threads inside this
    process, Modal's container runtime (same process: heartbeat and the HTTP
    forwarding to port 8000) was GIL-starved for 70-100 s at a time, Modal
    declared the container dead mid-stage, and requests died with 408/500.
    """
    import sys

    subprocess.run(
        [sys.executable, "-c", STAGE_SCRIPT, SNAPSHOT, LOCAL_MODEL,
         str(STAGE_WORKERS)],
        check=True,
    )


@app.function(
    image=image,
    gpu="H200:2",
    cpu=32,
    memory=131072,
    ephemeral_disk=512 * 1024,
    volumes={
        HF_CACHE: hf_cache,
        "/root/.cache/vllm": vllm_cache,
    },
    secrets=[origin_secret],
    min_containers=0,
    max_containers=1,
    scaledown_window=120,
    timeout=3600,
)
@modal.concurrent(max_inputs=128, target_inputs=128)
@modal.web_server(port=8000, startup_timeout=1800, label="glm53-flash-cyber")
def serve() -> None:
    if not os.environ.get("MURAKUMO_MODAL_ORIGIN_TOKEN"):
        raise RuntimeError("MURAKUMO_MODAL_ORIGIN_TOKEN is required")

    # Proxy first, so the gateway sees 503 "model loading" (which it retries)
    # during staging and load rather than a held connection.  Modal only
    # routes to port 8000 once this function returns, so staging and the
    # vLLM launch run on a thread and the function returns at once.
    subprocess.Popen(
        [
            "python",
            "-m",
            "uvicorn",
            "origin_proxy:app",
            "--app-dir",
            "/root",
            "--host",
            "0.0.0.0",
            "--port",
            "8000",
        ]
    )

    def stage_then_serve() -> None:
        stage_weights()
        subprocess.Popen(
            [
                "vllm",
                "serve",
                LOCAL_MODEL,
                "--served-model-name",
                MODEL_ID,
                "--host",
                "127.0.0.1",
                "--port",
                "8001",
                "--tensor-parallel-size",
                "2",
                "--max-num-seqs",
                "128",
                "--max-model-len",
                "131072",
                "--reasoning-parser",
                "glm45",
                "--tool-call-parser",
                "glm47",
                "--enable-auto-tool-choice",
                "--enable-prefix-caching",
                "--speculative-config",
                '{"method":"mtp","num_speculative_tokens":1}',
                "--limit-mm-per-prompt",
                '{"image":0,"video":0}',
            ]
        )

    threading.Thread(target=stage_then_serve, daemon=True).start()


@app.function(image=image, secrets=[origin_secret], timeout=3600)
def smoke(prompt: str = "In one sentence, what does nmap -sV do?") -> dict:
    """Exercise the deployed origin with the gateway bearer, from inside Modal.

    The token never leaves Modal.  Times the whole request so a cold start
    (Volume -> 2x H200 weight load) is measured rather than estimated:
    `modal run ...::smoke` right after deploy is the cold number, a second
    run within scaledown_window is the warm one.  It targets the deployed
    app (see below), so deploy first.
    """
    import json
    import time

    import httpx

    # `modal run` builds an ephemeral twin of this app; `serve.get_web_url()`
    # would point at that twin and spin up a second 2x H200 container.
    url = modal.Function.from_name(APP_NAME, "serve").get_web_url()
    token = os.environ["MURAKUMO_MODAL_ORIGIN_TOKEN"]
    body = {
        "model": MODEL_ID,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 256,
        "reasoning_effort": "low",
    }
    t0 = time.monotonic()
    # 503 "model loading" is the proxy's answer while vLLM is still starting.
    with httpx.Client(timeout=None) as client:
        while True:
            r = client.post(
                f"{url}/v1/chat/completions",
                headers={"authorization": f"Bearer {token}"},
                json=body,
            )
            if r.status_code != 503:
                break
            time.sleep(5)
    elapsed = time.monotonic() - t0
    try:
        unauth = httpx.get(f"{url}/v1/models", timeout=120).status_code
    except httpx.HTTPError as e:
        unauth = f"error: {type(e).__name__}"
    out = {
        "status": r.status_code,
        "elapsed_s": round(elapsed, 1),
        "unauthenticated_models_status": unauth,
    }
    if r.status_code == 200:
        j = r.json()
        msg = j["choices"][0]["message"]
        out["content"] = msg.get("content")
        out["reasoning_chars"] = len(msg.get("reasoning") or msg.get("reasoning_content") or "")
        out["usage"] = j.get("usage")
    else:
        out["body"] = r.text[:500]
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return out
