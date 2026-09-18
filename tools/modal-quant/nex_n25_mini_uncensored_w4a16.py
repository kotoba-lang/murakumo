"""AutoRound W4A16 (GPTQ int4, g128, sym) of orcarouter/Nex-N2.5-mini-Uncensored on Modal.

Why: the fleet's B70 box (aiueos-6600hs-1, 32 GiB) runs vLLM XPU at ~1,128 tok/s
aggregate on quant-mind/Nex-N2.5-mini-W4A16-AutoRound (root ADR-2609181615), but
that checkpoint is the STOCK model. orcarouter ships the Uncensored weights only as
BF16 (65 GiB), FP8 (34 GiB), NVFP4 and GGUF -- none fits 32 GiB on a non-Blackwell
card in a vLLM-loadable form. This job produces the missing int4 safetensors with
the recipe quant-mind published in its config.json (autoround 0.15.1, bits 4,
group_size 128, sym, iters 200, nsamples 128, seqlen 2048, NeelNanda/pile-10k,
mlp.gate + mlp.shared_expert_gate kept BF16, low_gpu_mem_usage, format auto_gptq),
so the two checkpoints differ in the base weights only.

Run (owner-billed Modal compute; H100 for the quantize step):
  modal run tools/modal-quant/nex_n25_mini_uncensored_w4a16.py
  modal run tools/modal-quant/nex_n25_mini_uncensored_w4a16.py --upload-repo com-junkawasaki/Nex-N2.5-mini-Uncensored-W4A16-AutoRound

Output lands on the Volume nex-n25-mini-uncensored-w4a16 under /vol/out, and with
--upload-repo in a PRIVATE HF repo (making it public is an owner decision).
"""
import modal

APP_NAME = "nex-n25-mini-uncensored-w4a16"
SOURCE = "orcarouter/Nex-N2.5-mini-Uncensored"
OUT_ROOT = "/vol/out"

image = (
    modal.Image.from_registry("nvidia/cuda:12.8.1-cudnn-devel-ubuntu22.04", add_python="3.12")
    .apt_install("git")
    .pip_install(
        "torch==2.8.0", "torchvision==0.23.0",
        extra_index_url="https://download.pytorch.org/whl/cu128",
    )
    .pip_install(
        "transformers==5.17.0", "auto-round==0.15.1", "accelerate", "datasets",
        "huggingface_hub[hf_transfer]", "sentencepiece", "tiktoken", "protobuf",
        "qwen-vl-utils", "pillow",
    )
    .env({"HF_HUB_ENABLE_HF_TRANSFER": "1", "HF_HOME": "/vol/hf",
          "PYTORCH_CUDA_ALLOC_CONF": "expandable_segments:True"})
)

vol = modal.Volume.from_name("nex-n25-mini-uncensored-w4a16", create_if_missing=True)
hf_secret = modal.Secret.from_name("hf-token")
app = modal.App(APP_NAME)


@app.function(image=image, volumes={"/vol": vol}, secrets=[hf_secret],
              cpu=8, memory=64 * 1024, timeout=3 * 3600)
def download() -> str:
    from huggingface_hub import snapshot_download
    p = snapshot_download(SOURCE, max_workers=16)
    vol.commit()
    return p


@app.function(image=image, volumes={"/vol": vol}, secrets=[hf_secret], gpu="H100",
              cpu=16, memory=200 * 1024, timeout=8 * 3600)
def quantize(model_path: str, iters: int = 200, nsamples: int = 128, seqlen: int = 2048) -> dict:
    import os, subprocess, time
    t0 = time.time()
    os.makedirs(OUT_ROOT, exist_ok=True)
    # The CLI is what quant-mind's config says it used (provider auto-round,
    # autoround_version 0.15.1). It detects the MLLM shape, quantizes the language
    # model's linear_attn / self_attn / experts / shared_expert and leaves the
    # router (mlp.gate), shared_expert_gate, mtp, lm_head, embeddings and the
    # visual tower in BF16 -- the same preserved set quant-mind's config lists.
    cmd = [
        "auto-round", "--model", model_path,
        "--scheme", "W4A16", "--group_size", "128",
        "--iters", str(iters), "--nsamples", str(nsamples), "--seqlen", str(seqlen),
        "--dataset", "NeelNanda/pile-10k",
        "--fp_layers", "mlp.gate,mlp.shared_expert_gate,mtp",
        "--low_gpu_mem_usage", "--enable_torch_compile",
        "--format", "auto_gptq", "--output_dir", OUT_ROOT,
    ]
    print("RUN", " ".join(cmd), flush=True)
    rc = subprocess.call(cmd)
    vol.commit()
    produced = sorted(os.listdir(OUT_ROOT))
    return {"rc": rc, "wall_s": round(time.time() - t0), "produced": produced}


@app.function(image=image, volumes={"/vol": vol}, secrets=[hf_secret],
              cpu=8, memory=32 * 1024, timeout=3 * 3600)
def upload(repo: str) -> str:
    import os
    from huggingface_hub import HfApi
    dirs = [d for d in sorted(os.listdir(OUT_ROOT)) if os.path.isdir(os.path.join(OUT_ROOT, d))]
    if len(dirs) != 1:
        raise SystemExit(f"expected exactly one output dir under {OUT_ROOT}, found {dirs}")
    api = HfApi()
    api.create_repo(repo, private=True, exist_ok=True)
    api.upload_large_folder(repo_id=repo, folder_path=os.path.join(OUT_ROOT, dirs[0]), repo_type="model")
    return f"https://huggingface.co/{repo} (private) <- {dirs[0]}"


@app.local_entrypoint()
def main(upload_repo: str = "", iters: int = 200, nsamples: int = 128, seqlen: int = 2048,
         skip_quant: bool = False):
    import json
    src = download.remote()
    print("source at", src)
    if not skip_quant:
        r = quantize.remote(src, iters=iters, nsamples=nsamples, seqlen=seqlen)
        print(json.dumps(r, indent=1))
        if r["rc"] != 0:
            raise SystemExit(f"auto-round exited {r['rc']}")
    if upload_repo:
        print(upload.remote(upload_repo))
