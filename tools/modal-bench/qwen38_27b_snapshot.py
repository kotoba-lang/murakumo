"""Does Modal's GPU memory snapshot remove the Qwen3.8-27B cold start?

The measured cold start is ~7 minutes with every cache warm (weights on a
Volume, torch.compile artifacts on a Volume).  On H200 that is $0.62 of
all-in compute before the first token, paid every time a container starts,
which is what decides whether scale-to-zero is cheaper than staying warm.

Modal's GPU memory snapshot (alpha) restores a container to a post-init state.
Its own documentation says it "can fail with certain torch.compile usage" --
and vLLM is nothing but torch.compile.  So this is a real experiment with a
real chance of a negative result, and a negative result is worth recording:
it pins the scale-to-zero economics to the 7-minute number instead of a hope.

    modal run tools/modal-bench/qwen38_27b_snapshot.py

Prints the second-start latency.  Exit 2 if it could not answer.
"""

import json
import sys
import time

import modal

MODEL = "Qwen/Qwen3.8-27B-FP8"

vllm_image = (
    modal.Image.from_registry(
        "nvidia/cuda:13.0.1-devel-ubuntu24.04", add_python="3.12"
    )
    .pip_install("vllm==0.27.1", "hf_transfer")
    .env(
        {
            "HF_HUB_ENABLE_HF_TRANSFER": "1",
            "VLLM_USE_V1": "1",
            "CUDA_HOME": "/usr/local/cuda",
            "FLASHINFER_CACHE_DIR": "/root/.cache/vllm/flashinfer",
            # The engine normally runs in a child process.  A snapshot has to
            # capture the CUDA context that actually holds the weights, so the
            # engine has to live in the process being snapshotted.
            "VLLM_ENABLE_V1_MULTIPROCESSING": "0",
        }
    )
)

hf_cache = modal.Volume.from_name("qwen38-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("qwen38-vllm-cache", create_if_missing=True)

app = modal.App("qwen38-27b-snapshot")


@app.cls(
    image=vllm_image,
    gpu="H200",
    volumes={"/root/.cache/huggingface": hf_cache, "/root/.cache/vllm": vllm_cache},
    timeout=60 * 60,
    cpu=8,
    memory=32768,
    enable_memory_snapshot=True,
    experimental_options={"enable_gpu_snapshot": True},
    scaledown_window=60,
)
class Engine:
    @modal.enter(snap=True)
    def load(self):
        from vllm import LLM

        t = time.time()
        self.llm = LLM(
            model=MODEL,
            max_model_len=65536,
            max_num_seqs=128,
            kv_cache_dtype="fp8",
            gpu_memory_utilization=0.92,
            trust_remote_code=True,
            speculative_config={"method": "mtp", "num_speculative_tokens": 3},
        )
        self.load_seconds = round(time.time() - t, 1)
        print(f"[snap] engine built in {self.load_seconds}s", flush=True)

    @modal.method()
    def ping(self) -> str:
        """One short generation, so the number includes reaching the weights."""
        from vllm import SamplingParams

        t = time.time()
        res = self.llm.generate(
            ["The fleet control plane assigns work to nodes by measured cost."],
            SamplingParams(
                temperature=0.0, max_tokens=32, min_tokens=32, ignore_eos=True
            ),
        )
        gen = sum(len(o.token_ids) for r in res for o in r.outputs)
        return json.dumps(
            {
                "generated": gen,
                "generate_seconds": round(time.time() - t, 2),
                "load_seconds_this_container": getattr(self, "load_seconds", None),
            }
        )


@app.local_entrypoint()
def main():
    eng = Engine()

    # First call: builds the snapshot (or fails trying).
    t = time.time()
    try:
        first = json.loads(eng.ping.remote())
    except Exception as e:  # noqa: BLE001
        print(
            json.dumps(
                {
                    "status": "could-not-measure",
                    "reason": f"{type(e).__name__}: {e}"[:900],
                },
                indent=2,
            )
        )
        print("REFUSING TO REPORT A RESULT: snapshot path did not run",
              file=sys.stderr)
        raise SystemExit(2)
    first_wall = round(time.time() - t, 1)

    # Let the container scale down, then call again: this is the number that
    # matters -- a *restored* start, not a warm one.
    print(f"first call {first_wall}s ({first}); sleeping past scaledown…",
          flush=True)
    time.sleep(150)

    t = time.time()
    second = json.loads(eng.ping.remote())
    second_wall = round(time.time() - t, 1)

    out = {
        "status": "measured",
        "gpu": "H200",
        "first_call_wall_seconds": first_wall,
        "first_call": first,
        "second_call_wall_seconds": second_wall,
        "second_call": second,
        # If load_seconds is absent on the second call, @modal.enter(snap=True)
        # did not re-run -- i.e. the container was restored, not rebuilt.
        "restored_from_snapshot": second.get("load_seconds_this_container") is None,
    }
    print(json.dumps(out, indent=2))
    with open("/tmp/q-snapshot.json", "w") as f:
        f.write(json.dumps(out, indent=2) + "\n")
