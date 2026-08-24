"""Deploy HauhauCS Qwen3.8-27B Aggressive FastMTP on Modal.

The acceleration is implemented by a draft GGUF plus a small, pinned
llama.cpp patch.  A generic vLLM deployment can load the target model but does
not use this FastMTP sidecar.

    modal run tools/modal-fastmtp/qwen38_fastmtp_server.py::download_models
    modal deploy tools/modal-fastmtp/qwen38_fastmtp_server.py

The endpoint is OpenAI-compatible.  It scales to zero after five idle minutes;
api.murakumo.cloud is the public boundary and supplies the origin API key.
"""

import hashlib
import os
import subprocess
from pathlib import Path

import modal

APP_NAME = "murakumo-qwen38-fastmtp"
MODEL_ID = "qwen3.8-27b-fastmtp-aggressive"
HF_REPO = "HauhauCS/Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-MTP-GGUF"
HF_REVISION = "993a5971fda8f30dd1b7eb2654792ba4415c7460"
TARGET_FILE = "Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-Q4_K_P.gguf"
DRAFT_FILE = "Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-FastMTP-32K.gguf"
VISION_FILE = "mmproj-Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-BF16.gguf"
TARGET_SHA256 = "ba36dc3c2b2ff5e0aa5d71092a8894546996a6a119ae391803dda07cdc08516d"
DRAFT_SHA256 = "115e618e1f73cb50817ed5856f0551c6bf9c3d94df96f440eaca78dc63b8968b"
VISION_SHA256 = "5681b690bcb8eb10cd28d62d078cb4e01521a3ea4880a3fc7d54de72de2dd142"
LLAMA_CPP_COMMIT = "4df29be4f4c3673f428170fda944a5b19f743bb8"
PATCH_URL = (
    "https://huggingface.co/" + HF_REPO + "/resolve/" + HF_REVISION
    + "/HauhauCS-FastMTP-llama.cpp.patch"
)

MODEL_DIR = Path("/models")
volume = modal.Volume.from_name("qwen38-fastmtp-models", create_if_missing=True)
origin_secret = modal.Secret.from_name("murakumo-modal-origin")

image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.8.1-devel-ubuntu24.04", add_python="3.12"
    )
    .apt_install(
        "build-essential", "ca-certificates", "cmake", "curl", "git",
        "libcurl4-openssl-dev",
    )
    .pip_install("huggingface_hub==0.34.4")
    .run_commands(
        "git clone https://github.com/ggml-org/llama.cpp /opt/llama.cpp",
        f"cd /opt/llama.cpp && git checkout {LLAMA_CPP_COMMIT}",
        f"curl -fsSL '{PATCH_URL}' -o /tmp/fastmtp.patch",
        "cd /opt/llama.cpp && git apply --check /tmp/fastmtp.patch",
        "cd /opt/llama.cpp && git apply /tmp/fastmtp.patch",
        "cmake -S /opt/llama.cpp -B /opt/llama.cpp/build "
        "-DGGML_CUDA=ON -DLLAMA_CURL=ON -DCMAKE_BUILD_TYPE=Release "
        # This deployment is intentionally RTX PRO 6000-only.  Avoid spending
        # several image-build minutes compiling kernels for five unused GPU
        # generations; Blackwell workstation/server parts use sm_120a here.
        "-DCMAKE_CUDA_ARCHITECTURES=120a-real "
        # Modal image builders have the CUDA toolkit but no live driver.
        # libggml-cuda resolves these driver symbols when the GPU container
        # starts, so permit them to remain unresolved at image link time.
        "-DCMAKE_EXE_LINKER_FLAGS='-Wl,--allow-shlib-undefined'",
        "cmake --build /opt/llama.cpp/build --config Release -j --target llama-server",
    )
)

app = modal.App(APP_NAME)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@app.function(
    image=image,
    volumes={str(MODEL_DIR): volume},
    cpu=2,
    memory=4096,
    timeout=7200,
)
def download_models() -> dict[str, object]:
    """Pin, download and checksum target, draft, and vision GGUFs."""
    from huggingface_hub import hf_hub_download

    expected = {
        TARGET_FILE: TARGET_SHA256,
        DRAFT_FILE: DRAFT_SHA256,
        VISION_FILE: VISION_SHA256,
    }
    result = {}
    for filename, expected_sha in expected.items():
        downloaded = Path(
            hf_hub_download(
                repo_id=HF_REPO,
                filename=filename,
                revision=HF_REVISION,
                local_dir=MODEL_DIR,
            )
        )
        actual_sha = _sha256(downloaded)
        if actual_sha != expected_sha:
            raise RuntimeError(f"checksum mismatch for {filename}")
        result[filename] = {"bytes": downloaded.stat().st_size, "sha256": actual_sha}
    volume.commit()
    return {"revision": HF_REVISION, "files": result}


@app.function(
    image=image,
    gpu="RTX-PRO-6000",
    cpu=4,
    memory=32768,
    volumes={str(MODEL_DIR): volume},
    secrets=[origin_secret],
    min_containers=0,
    max_containers=2,
    scaledown_window=300,
    timeout=3600,
)
@modal.concurrent(max_inputs=1)
@modal.web_server(port=8000, startup_timeout=1200, label="openai")
def serve() -> None:
    api_key = os.environ["MURAKUMO_MODAL_ORIGIN_TOKEN"]
    command = [
        "/opt/llama.cpp/build/bin/llama-server",
        "--host", "0.0.0.0", "--port", "8000",
        "--model", str(MODEL_DIR / TARGET_FILE),
        "--mmproj", str(MODEL_DIR / VISION_FILE),
        "--alias", MODEL_ID,
        "--api-key", api_key,
        "--n-gpu-layers", "all",
        "--split-mode", "none",
        "--ctx-size", "32768",
        "--parallel", "1",
        "--batch-size", "2048",
        "--ubatch-size", "512",
        "--flash-attn", "on",
        "--no-mmap",
        "--jinja",
        "--spec-draft-model", str(MODEL_DIR / DRAFT_FILE),
        "--spec-draft-ngl", "all",
        "--spec-type", "draft-mtp",
        "--spec-draft-n-max", "3",
        "--spec-draft-p-min", "0",
    ]
    subprocess.Popen(command)
