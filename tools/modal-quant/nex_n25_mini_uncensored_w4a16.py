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
  modal run tools/modal-quant/nex_n25_mini_uncensored_w4a16.py --upload-repo com-kotobalabs/Nex-N2.5-mini-Uncensored-W4A16-AutoRound --public
  (publish-only, after a finished quantize):  ... --skip-quant --upload-repo <repo> --public

Output lands on the Volume nex-n25-mini-uncensored-w4a16 under /vol/out; --upload-repo
pushes it to HF (private unless --public; owner instruction 2026-09-18: publish under
https://huggingface.co/com-kotobalabs).
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


MODEL_CARD = """---
license: apache-2.0
base_model: orcarouter/Nex-N2.5-mini-Uncensored
tags:
- auto-round
- w4a16
- int4
- gptq
- vllm
- qwen3_5_moe
- uncensored
- abliterated
pipeline_tag: text-generation
---

# Nex-N2.5-mini-Uncensored-W4A16-AutoRound

W4A16 (INT4 weights, group size 128, symmetric, BF16 activations) quantization of
[orcarouter/Nex-N2.5-mini-Uncensored](https://huggingface.co/orcarouter/Nex-N2.5-mini-Uncensored)
(the abliterated Nex-N2.5-mini, a Qwen3.5-MoE 35B-A3B vision-language model) in
`auto_gptq` format, loadable by vLLM (Marlin / XPU) and SGLang.

## Why this exists

orcarouter publishes the Uncensored weights as BF16 (65 GiB), FP8 (34 GiB, Hopper+),
NVFP4 (22 GiB, Blackwell only), MLX (Apple) and GGUF (llama.cpp). None of those loads
in vLLM on a 32 GiB non-Blackwell card. [quant-mind/Nex-N2.5-mini-W4A16-AutoRound]
(https://huggingface.co/quant-mind/Nex-N2.5-mini-W4A16-AutoRound) filled that gap for
the STOCK model; this repository applies the same published recipe to the Uncensored
weights, so the two differ in base weights only. Built for the murakumo fleet's
Intel Arc Pro B70 (32 GiB) head, where vLLM XPU + XPU graph measured ~1,128 tok/s
aggregate on the stock W4A16 checkpoint vs ~137 tok/s for llama.cpp IQ4_XS on the same
card (root ADR-2609181615, com-junkawasaki/root).

## Recipe (identical to quant-mind's config.json)

- Intel AutoRound 0.15.1, `--scheme W4A16 --group_size 128` (sym), `--iters 200`,
  `--nsamples 128 --seqlen 2048`, calibration `NeelNanda/pile-10k`, `--low_gpu_mem_usage`
- Kept in BF16: `mlp.gate` (router), `mlp.shared_expert_gate`, `mtp`, `lm_head`,
  embeddings, the visual tower
- Quantized: `linear_attn.*_proj`, `self_attn.{q,k,v,o}_proj`, all 256 `mlp.experts.*`,
  `mlp.shared_expert.*`
- Export format: `auto_gptq`
- Built on Modal (H100) by the job `tools/modal-quant/nex_n25_mini_uncensored_w4a16.py`
  in kotoba-lang/murakumo.

## Use

```bash
vllm serve com-kotobalabs/Nex-N2.5-mini-Uncensored-W4A16-AutoRound \
    --reasoning-parser qwen3 --max-model-len 65536 --gpu-memory-utilization 0.90
```

The model thinks before answering; without `--reasoning-parser qwen3` the reasoning
leaks into `content`.

## Caveats

- Abliterated / uncensored derivative: it will not refuse. Deploy behind your own policy
  layer; the murakumo gateway runs it only behind governed organisms.
- Quality vs the BF16 source is not evaluated here beyond a coherence probe; the
  quantization error profile is the one AutoRound publishes for this recipe.
- License follows the base: Apache-2.0 (nex-agi/Nex-N2.5-mini) as relicensed by orcarouter.
"""


@app.function(image=image, volumes={"/vol": vol}, secrets=[hf_secret],
              cpu=8, memory=32 * 1024, timeout=3 * 3600)
def upload(repo: str, public: bool = False) -> str:
    import os
    from huggingface_hub import HfApi
    dirs = [d for d in sorted(os.listdir(OUT_ROOT)) if os.path.isdir(os.path.join(OUT_ROOT, d))]
    if len(dirs) != 1:
        raise SystemExit(f"expected exactly one output dir under {OUT_ROOT}, found {dirs}")
    src = os.path.join(OUT_ROOT, dirs[0])
    with open(os.path.join(src, "README.md"), "w") as f:
        f.write(MODEL_CARD)
    # upload_large_folder keeps its progress in <src>/.cache/huggingface and would treat a second
    # target repo as already uploaded (measured 2026-09-18: the public repo received only
    # README + .gitattributes). Drop the marker so every target gets the full folder.
    import shutil
    shutil.rmtree(os.path.join(src, ".cache"), ignore_errors=True)
    api = HfApi()
    api.create_repo(repo, private=not public, exist_ok=True)
    api.upload_large_folder(repo_id=repo, folder_path=src, repo_type="model")
    if public:
        api.update_repo_settings(repo_id=repo, private=False)
    return f"https://huggingface.co/{repo} ({'public' if public else 'private'}) <- {dirs[0]}"


@app.local_entrypoint()
def main(upload_repo: str = "", iters: int = 200, nsamples: int = 128, seqlen: int = 2048,
         skip_quant: bool = False, public: bool = False):
    import json
    src = download.remote()
    print("source at", src)
    if not skip_quant:
        r = quantize.remote(src, iters=iters, nsamples=nsamples, seqlen=seqlen)
        print(json.dumps(r, indent=1))
        if r["rc"] != 0:
            raise SystemExit(f"auto-round exited {r['rc']}")
    if upload_repo:
        print(upload.remote(upload_repo, public=public))
