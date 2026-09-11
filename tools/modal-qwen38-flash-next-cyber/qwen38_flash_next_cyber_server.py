"""dealignai/Qwen3.8-Flash-Next-CYBERSECURITY-NVFP4 on one B200, with GPU memory snapshots.

    # 1. 135 GB of safetensors onto a Volume, once, on a CPU container
    modal run    tools/modal-qwen38-flash-next-cyber/qwen38_flash_next_cyber_server.py::download
    # 2. deploy (snapshots need a DEPLOYED app -- `modal run` disables them silently)
    modal deploy tools/modal-qwen38-flash-next-cyber/qwen38_flash_next_cyber_server.py
    # 3. measure: first call = snapshot creation; later calls after scale-down = restore
    modal run    tools/modal-qwen38-flash-next-cyber/qwen38_flash_next_cyber_server.py::smoke

Run from the repository root (origin_proxy.py is added by a relative path).

Why this model and this card (2026-09-11).  The GLM-5.3-Flash cyber origin
(tools/modal-glm53-cyber) is 194.7 GB and needs 2x H200; its cold start is
1,190-1,530 s, and Modal refuses GPU memory snapshots for any function with
more than one GPU.  This checkpoint is the same author's cyber build of
Qwen3.8-Flash-Next at NVFP4: 135.2 GB, the only CYBERSECURITY checkpoint of
theirs that fits one GPU -- B200 (180 GB), ~45 GB left for KV at 131k.  NVFP4
wants Blackwell anyway, and one B200 (~$6.3/h) is cheaper than 2x H200 (~$9/h).

Why this shape.  Modal's documented vLLM recipe for snapshots
(modal-examples llm-serving/vllm_low_latency.py): vLLM runs as a subprocess
with `--enable-sleep-mode` and VLLM_SERVER_DEV_MODE=1; `@modal.enter(snap=True)`
starts it, waits for /health, warms it, and POSTs /sleep?level=1 so the
weights sit in CPU memory where the snapshot captures them; Modal snapshots;
`@modal.enter(snap=False)` POSTs /wake_up on restore.  The gateway-facing
proxy (origin_proxy.py, bearer-gated, 503 while vLLM is not listening) is
started FIRST, before staging, so a starting container answers 503 from its
first seconds; its listening socket lives in the snapshot, like vLLM's own
in Modal's example.  (Started after the enter hooks, as the first version
did, Modal held the gateway's request open for 125 s and Cloudflare turned
that into a 524 -- measured 2026-09-11 18:41.)

Snapshots are per WORKER TYPE and "GPU Functions need 2-3 snapshots per GPU
type" (Modal docs): the first 2-3 cold starts after a deploy each build a
snapshot (~10 min to sleep + ~11 min to write) before restores become the
norm.  A config change is therefore not free; deploy rarely.

What this repository already knows about this lever, so it is not re-learned:
- 2026-08-20/21 (tools/modal-bench/qwen38_27b_snapshot.py, ADR-260821b):
  the first attempt used `modal run`, which silently disables snapshots and
  reported a clean-looking negative.  Deployed, snapshots were CREATED but
  never RESTORED across four probes -- unresolved, not refuted.  So the
  measurement here is not "did it deploy" but "did a later cold start log a
  restore and answer in seconds".  `smoke` prints per-call elapsed time; the
  container log says `post-snapshot enter` with the restore path's timing.
- Snapshots are per worker type and can take a few container starts to
  materialise: call `smoke` several times with gaps > scaledown_window and
  read every attempt, not the first.
- 2026-09-11, this file (ADR-260911b): four starts failed before the first
  snapshot -- a renamed vLLM flag, `--kv-cache-dtype fp8` (refused for this
  arch), the v0.29.0 release lacking the Engram `weight_scale` loader (needs
  the nightly pinned below), and Modal's 30-MINUTE STARTUP WALL for a
  snapshot-enabled container (`startup_timeout=3600` does not lift it; the
  FlashInfer autotune cache on the Volume is what keeps a start under it).
  The fifth start slept at 601 s, the snapshot took 11 min to write, and the
  restore woke vLLM in 3.9 s.

Unmeasured, and what the smoke decides: whether sleep-mode + NVFP4 + hybrid
linear/full attention + MTP survive checkpoint/restore on this driver.  If the
restored container answers 503 forever or dies, the log will say which.
"""

import os
import subprocess

import modal

APP_NAME = "murakumo-qwen38-flash-next-cyber"
LABEL = "qwen38-flash-next-cyber"
MODEL_ID = "qwen3.8-flash-next-cybersecurity-nvfp4"
MODEL = "dealignai/Qwen3.8-Flash-Next-CYBERSECURITY-NVFP4"
MODEL_REVISION = "15ef113c7090844f97f355f400169791dc76c2e3"
HF_CACHE = "/root/.cache/huggingface"
SNAPSHOT = (
    f"{HF_CACHE}/hub/models--{MODEL.replace('/', '--')}/snapshots/{MODEL_REVISION}"
)
LOCAL_MODEL = "/tmp/model"
STAGE_WORKERS = 16
VLLM_PORT = 8001

image = (
    # NOT v0.29.0 (tagged 2026-09-08): it loads all 206 shards and then dies on
    # the last one --
    #   ValueError: There is no module or parameter named
    #   'ngram_embedding.weight_scale' in Qwen4ExpNGramEmbedding
    # (measured 2026-09-11, twice, 13 min of B200 each).  The checkpoint's
    # Engram table carries an FP8 scale; the code that registers that
    # parameter (qwen4_exp/nvidia/ngram_embedding.py, `weight_scale` via
    # create_fp8_scale_parameter) landed in #54371 on 2026-09-09, one day
    # after the tag.  So: the nightly, pinned by commit.
    modal.Image.from_registry(
        "vllm/vllm-openai:nightly-e7edf17cea217e52701f913cd8491fcacf2d9490",
        add_python="3.12",
    )
    .entrypoint([])
    .pip_install("huggingface_hub[hf_transfer]", "fastapi", "uvicorn", "httpx==0.28.1")
    .env(
        {
            "HF_XET_HIGH_PERFORMANCE": "1",
            "HF_HUB_ENABLE_HF_TRANSFER": "1",
            "FLASHINFER_CACHE_DIR": "/root/.cache/vllm/flashinfer",
            "PYTORCH_CUDA_ALLOC_CONF": "expandable_segments:True",
            # /sleep and /wake_up are dev-mode routes on the vLLM server.
            "VLLM_SERVER_DEV_MODE": "1",
            # Modal's snapshot guidance: inductor's worker pool does not
            # survive a snapshot.
            "TORCHINDUCTOR_COMPILE_THREADS": "1",
        }
    )
    .add_local_file("tools/modal-throughput/origin_proxy.py", "/root/origin_proxy.py")
)

hf_cache = modal.Volume.from_name("qwen38-flash-next-cyber-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("qwen38-flash-next-cyber-vllm-cache", create_if_missing=True)
origin_secret = modal.Secret.from_name("murakumo-modal-origin")
app = modal.App(APP_NAME)


@app.function(image=image, cpu=16, memory=32768, volumes={HF_CACHE: hf_cache}, timeout=4 * 3600)
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


# Same staging as the GLM origin: vLLM reading shard-by-shard through the 9P
# Volume mount measured ~0.11 GB/s; parallel readers onto NVMe 0.5-1.4 GB/s.
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

VLLM_ARGS = [
    "vllm", "serve", LOCAL_MODEL,
    "--served-model-name", MODEL_ID,
    "--host", "127.0.0.1", "--port", str(VLLM_PORT),
    "--max-model-len", "131072",
    "--max-num-seqs", "64",
    # NOT --kv-cache-dtype fp8: the model card's fp8 KV is SGLang's; vLLM
    # v0.29.0 refuses it here -- `Qwen4Exp QSA requires a BF16 main KV cache`
    # (measured 2026-09-11, EngineCore init).  Only 12 of 48 layers are full
    # attention, so BF16 KV is affordable.
    # headroom for the snapshot machinery itself (prior experiment used 0.85)
    "--gpu-memory-utilization", "0.88",
    "--reasoning-parser", "qwen3",
    "--tool-call-parser", "hermes",
    "--enable-auto-tool-choice",
    "--enable-prefix-caching",
    "--enable-sleep-mode",
    "--speculative-config", '{"method":"mtp","num_speculative_tokens":1}',
    "--limit-mm-per-prompt", '{"image":0,"video":0}',
    # fewer graphs to capture: the snapshot amortises them anyway, but the
    # FIRST start still pays capture time
    "--compilation-config", '{"cudagraph_capture_sizes":[1,2,4,8,16,32,64]}',
]


def _vllm_health(client) -> bool:
    import httpx

    try:
        return client.get(f"http://127.0.0.1:{VLLM_PORT}/health").status_code == 200
    except httpx.HTTPError:
        return False


@app.cls(
    image=image,
    gpu="B200",
    cpu=32,
    # sleep level 1 parks the weights in CPU memory: 135 GB plus working set
    memory=204800,
    ephemeral_disk=512 * 1024,
    volumes={HF_CACHE: hf_cache, "/root/.cache/vllm": vllm_cache},
    secrets=[origin_secret],
    min_containers=0,
    max_containers=1,
    # Owner decision 2026-09-11: stop after 5 min without a request.  A
    # restore is ~4 s (ADR-260911b), so the idle tail is the whole cost.
    # (2026-08-20 on the 27B experiment: a 10 s window raced the snapshot
    # machinery; 300 is far from that.)
    scaledown_window=300,
    timeout=3600,
    startup_timeout=3600,
    enable_memory_snapshot=True,
    experimental_options={"enable_gpu_snapshot": True},
)
@modal.concurrent(max_inputs=64, target_inputs=64)
class Origin:
    @modal.enter(snap=True)
    def load(self) -> None:
        """Stage, start vLLM, wait for /health, warm, sleep.  Once per snapshot."""
        import sys
        import time

        import httpx

        if not os.environ.get("MURAKUMO_MODAL_ORIGIN_TOKEN"):
            raise RuntimeError("MURAKUMO_MODAL_ORIGIN_TOKEN is required")
        # The proxy FIRST, before staging, so that from the first seconds of a
        # snapshot-creating start the origin answers 503 "model loading"
        # (vLLM not listening) instead of Modal holding the connection open:
        # measured 2026-09-11 18:41, with the proxy started only after the
        # enter hooks, api.murakumo.cloud's fetch to this origin sat for 125 s
        # and came back as Cloudflare's 524, not a 503 the gateway would poll.
        # A listening socket in the snapshot is fine -- Modal's own vLLM
        # snapshot example snapshots with vLLM itself listening.
        self.proxy = subprocess.Popen(
            ["python", "-m", "uvicorn", "origin_proxy:app", "--app-dir", "/root",
             "--host", "0.0.0.0", "--port", "8000"]
        )
        t0 = time.monotonic()
        subprocess.run(
            [sys.executable, "-c", STAGE_SCRIPT, SNAPSHOT, LOCAL_MODEL, str(STAGE_WORKERS)],
            check=True,
        )
        t_staged = time.monotonic()
        self.vllm = subprocess.Popen(VLLM_ARGS)
        with httpx.Client(timeout=10) as client:
            while not _vllm_health(client):
                if self.vllm.poll() is not None:
                    raise RuntimeError(f"vllm exited with {self.vllm.returncode} before /health")
                time.sleep(3)
            t_ready = time.monotonic()
            # The FlashInfer autotune (44 configs, ~420 s on 2026-09-11) writes its
            # cache under /root/.cache/vllm; commit it NOW.  The first start of
            # v4 was killed by Modal's 30-minute startup wall 63 s after /sleep,
            # and the retry only fit because this cache had survived -- do not
            # leave that to the implicit commit on exit, which a killed
            # container never reaches.
            vllm_cache.commit()
            # warm: one real completion so the graphs the snapshot captures
            # are the ones that serve
            r = client.post(
                f"http://127.0.0.1:{VLLM_PORT}/v1/chat/completions",
                json={"model": MODEL_ID, "messages": [{"role": "user", "content": "Say OK."}],
                      "max_tokens": 8},
                timeout=300,
            )
            print(f"snapshot-load: warm request -> {r.status_code}", flush=True)
            client.post(f"http://127.0.0.1:{VLLM_PORT}/sleep?level=1", timeout=600).raise_for_status()
        print(
            f"snapshot-load: staged {t_staged - t0:.0f} s, healthy at {t_ready - t0:.0f} s, "
            f"asleep at {time.monotonic() - t0:.0f} s; snapshot follows", flush=True,
        )

    @modal.enter(snap=False)
    def wake(self) -> None:
        """Every start, including a restore: wake vLLM and say how long it took."""
        import time

        import httpx

        t0 = time.monotonic()
        with httpx.Client(timeout=600) as client:
            r = client.post(f"http://127.0.0.1:{VLLM_PORT}/wake_up")
            while not _vllm_health(client):
                time.sleep(1)
        print(f"post-snapshot enter: wake_up -> {r.status_code}, healthy after "
              f"{time.monotonic() - t0:.1f} s", flush=True)

    @modal.web_server(port=8000, startup_timeout=120, label=LABEL)
    def serve(self) -> None:
        # The proxy was started in load() and lives in the snapshot; a restored
        # container still has it.  Only a container whose enter hooks somehow
        # ran without it gets a fresh one.
        p = getattr(self, "proxy", None)
        if p is None or p.poll() is not None:
            self.proxy = subprocess.Popen(
                ["python", "-m", "uvicorn", "origin_proxy:app", "--app-dir", "/root",
                 "--host", "0.0.0.0", "--port", "8000"]
            )

    @modal.exit()
    def stop(self) -> None:
        for name in ("vllm", "proxy"):
            p = getattr(self, name, None)
            if p is not None:
                p.terminate()


@app.function(image=image, secrets=[origin_secret], timeout=3600)
def smoke(prompt: str = "In one sentence, what does nmap -sV do?") -> dict:
    """One request through the deployed origin, timed end to end, bearer inside Modal.

    Read the container log too: `post-snapshot enter: ... healthy after N s`
    is the restore number; `snapshot-load: ...` means this start built from
    scratch (no snapshot restored).
    """
    import time

    import httpx

    url = f"https://junkawasakicom--{LABEL}.modal.run"
    token = os.environ["MURAKUMO_MODAL_ORIGIN_TOKEN"]
    body = {
        "model": MODEL_ID,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 256,
    }
    t0 = time.monotonic()
    with httpx.Client(timeout=None) as client:
        while True:
            r = client.post(f"{url}/v1/chat/completions",
                            headers={"authorization": f"Bearer {token}"}, json=body)
            # 503: the proxy says vLLM is not listening.  303/502/504: Modal's
            # own answer while the container is still in enter() and nothing
            # listens on 8000 at all (the snapshot-creating start).
            if r.status_code not in (303, 502, 503, 504):
                break
            time.sleep(5)
    out = {"status": r.status_code, "elapsed_s": round(time.monotonic() - t0, 1)}
    if r.status_code == 200:
        j = r.json()
        msg = j["choices"][0]["message"]
        out["content"] = msg.get("content")
        out["reasoning_chars"] = len(msg.get("reasoning") or msg.get("reasoning_content") or "")
        out["usage"] = j.get("usage")
    else:
        out["body"] = r.text[:500]
    print(out, flush=True)
    return out
