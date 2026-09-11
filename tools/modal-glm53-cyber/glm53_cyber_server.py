"""Self-host dealignai/GLM-5.3-CYBERSECURITY-FP8 on Modal (TP8, 8x H200).

NOT DEPLOYED (2026-09-11).  The owner chose the Flash-W4A16 sibling on
2x H200 instead (glm53_flash_cyber_server.py, ADR-260911).  This file has
never been run past `modal deploy`: the download, serve and cost figures in
it are sized from the Hub, not measured.  If it is ever used, port the
NVMe-staging subprocess and the thread-launched serve from the Flash file
first — the Volume read path here is the one measured at 0.11 GB/s.

    # 1. pull the 755 GB of safetensors onto a Volume once, on a CPU container
    modal run tools/modal-glm53-cyber/glm53_cyber_server.py::download
    # 2. deploy the scale-to-zero vLLM origin
    modal deploy tools/modal-glm53-cyber/glm53_cyber_server.py

Run both from the repository root: the origin proxy is added by a path
relative to it.  The public boundary stays api.murakumo.cloud; this private
origin accepts only the gateway-held bearer (origin_proxy.py, shared with
tools/modal-throughput) and keeps vLLM on localhost.

Why these numbers.  The checkpoint is 753B-parameter glm_moe_dsa at FP8 —
755.6 GB across 282 shards — so 8x H100-80GB (640 GB) cannot hold the weights
and the smallest fit is 8x H200 (1,128 GB).  The serve flags are the model
card's TP8/H200 profile: --enforce-eager is required by the DeepSeek-sparse
attention path under concurrency, MTP speculative decoding is non-functional
on stock vLLM for this arch, and 131k context is the measured ceiling.
"""

import os
import subprocess

import modal

APP_NAME = "murakumo-glm53-cyber"
MODEL_ID = "glm-5.3-cybersecurity-fp8"
MODEL = "dealignai/GLM-5.3-CYBERSECURITY-FP8"
MODEL_REVISION = "5915c1b88f998a9c1e1a0c83688e285a08ae3ca5"
HF_CACHE = "/root/.cache/huggingface"

image = (
    modal.Image.from_registry(
        "nvidia/cuda:13.0.1-devel-ubuntu24.04", add_python="3.12"
    )
    .pip_install("vllm==0.28.0", "hf_transfer", "httpx==0.28.1")
    .env(
        {
            "HF_XET_HIGH_PERFORMANCE": "1",
            "HF_HUB_ENABLE_HF_TRANSFER": "1",
            "CUDA_HOME": "/usr/local/cuda",
            "FLASHINFER_CACHE_DIR": "/root/.cache/vllm/flashinfer",
        }
    )
    .add_local_file(
        "tools/modal-throughput/origin_proxy.py", "/root/origin_proxy.py"
    )
)

hf_cache = modal.Volume.from_name("glm53-cyber-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("glm53-cyber-vllm-cache", create_if_missing=True)
origin_secret = modal.Secret.from_name("murakumo-modal-origin")
app = modal.App(APP_NAME)


@app.function(
    image=image,
    cpu=16,
    memory=65536,
    volumes={HF_CACHE: hf_cache},
    timeout=6 * 3600,
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


@app.function(
    image=image,
    gpu="H200:8",
    cpu=32,
    memory=262144,
    volumes={
        HF_CACHE: hf_cache,
        "/root/.cache/vllm": vllm_cache,
    },
    secrets=[origin_secret],
    min_containers=0,
    max_containers=1,
    scaledown_window=600,
    timeout=3600,
)
@modal.concurrent(max_inputs=24, target_inputs=24)
@modal.web_server(port=8000, startup_timeout=3600, label="glm53-cyber")
def serve() -> None:
    if not os.environ.get("MURAKUMO_MODAL_ORIGIN_TOKEN"):
        raise RuntimeError("MURAKUMO_MODAL_ORIGIN_TOKEN is required")

    subprocess.Popen(
        [
            "vllm",
            "serve",
            MODEL,
            "--revision",
            MODEL_REVISION,
            "--served-model-name",
            MODEL_ID,
            "--host",
            "127.0.0.1",
            "--port",
            "8001",
            "--tensor-parallel-size",
            "8",
            "--gpu-memory-utilization",
            "0.90",
            "--enforce-eager",
            "--disable-custom-all-reduce",
            "--enable-prefix-caching",
            "--max-num-seqs",
            "24",
            "--max-model-len",
            "131072",
            "--reasoning-parser",
            "glm45",
            "--tool-call-parser",
            "glm47",
            "--enable-auto-tool-choice",
            "--safetensors-load-strategy",
            "prefetch",
        ]
    )
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
