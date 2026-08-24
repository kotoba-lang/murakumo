"""Deploy the aggregate-throughput Qwen3.8-27B profile on Modal.

    modal deploy tools/modal-throughput/qwen38_a100_server.py

The public boundary is api.murakumo.cloud.  This private origin accepts the
gateway-held bearer through origin_proxy.py and keeps vLLM on localhost.
"""

import os
import subprocess

import modal

APP_NAME = "murakumo-qwen38-throughput"
MODEL_ID = "qwen3.8-27b-throughput"
MODEL = "gratex/Qwen3.8-27B-W4A16-g128-sym-GPTQ"
MODEL_REVISION = "349f65375fc2b85c289c9c0604f7a4ec26c6b17f"

image = (
    modal.Image.from_registry(
        "nvidia/cuda:13.0.1-devel-ubuntu24.04", add_python="3.12"
    )
    .pip_install("vllm==0.27.1", "hf_transfer", "httpx==0.28.1")
    .env(
        {
            "HF_XET_HIGH_PERFORMANCE": "1",
            "CUDA_HOME": "/usr/local/cuda",
            "FLASHINFER_CACHE_DIR": "/root/.cache/vllm/flashinfer",
        }
    )
    .add_local_file(
        "tools/modal-throughput/origin_proxy.py", "/root/origin_proxy.py"
    )
)

hf_cache = modal.Volume.from_name("qwen38-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("qwen38-vllm-cache", create_if_missing=True)
origin_secret = modal.Secret.from_name("murakumo-modal-origin")
app = modal.App(APP_NAME)


@app.function(
    image=image,
    gpu="A100-40GB",
    cpu=8,
    memory=32768,
    volumes={
        "/root/.cache/huggingface": hf_cache,
        "/root/.cache/vllm": vllm_cache,
    },
    secrets=[origin_secret],
    min_containers=0,
    max_containers=2,
    scaledown_window=300,
    timeout=3600,
)
@modal.concurrent(max_inputs=64, target_inputs=64)
@modal.web_server(port=8000, startup_timeout=1200, label="throughput")
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
            "--max-model-len",
            "262144",
            "--max-num-seqs",
            "128",
            "--max-num-batched-tokens",
            "8192",
            "--kv-cache-dtype",
            "fp8",
            "--gpu-memory-utilization",
            "0.92",
            "--safetensors-load-strategy",
            "prefetch",
            "--limit-mm-per-prompt",
            '{"image":0,"video":0}',
            "--trust-remote-code",
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
