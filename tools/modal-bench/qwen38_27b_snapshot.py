"""Cold start for Qwen3.8-27B on Modal, via vLLM sleep mode + GPU memory snapshot.

This replaces an earlier version of this file that concluded snapshots "did not
restore".  That conclusion was wrong, and wrong in the way this repository keeps
finding: the run printed

    Memory snapshots are disabled for ephemeral apps.
    Deploy your app with `modal deploy` to enable memory snapshots.

and then reported `restored_from_snapshot: false` -- a true statement about a
thing that had never been attempted, shaped exactly like a negative result.
`modal run` creates an ephemeral App.  Snapshots need `modal deploy`.

The documented recipe is: build the engine inside @modal.enter(snap=True), warm
it, put vLLM to sleep so the weights sit in CPU memory where a snapshot can
capture them, let Modal snapshot, and call wake_up() on restore.

    modal deploy tools/modal-bench/qwen38_27b_snapshot.py
    python tools/modal-bench/qwen38_27b_snapshot.py probe 8

Snapshots are per worker type and take 2-3 invocations to materialise, so the
probe calls repeatedly and prints every attempt.  Reading only the first is how
you conclude it does not work.
"""

import json
import sys
import time

import modal

MODEL = "Qwen/Qwen3.8-27B-FP8"
APP_NAME = "qwen38-27b-snapshot-v2"

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
            # The engine must live in the process being snapshotted, so the
            # CUDA context holding the weights is the one that gets captured.
            "VLLM_ENABLE_V1_MULTIPROCESSING": "0",
            # Modal's own snapshot guidance: inductor's worker pool does not
            # survive a snapshot.
            "TORCHINDUCTOR_COMPILE_THREADS": "1",
        }
    )
)

hf_cache = modal.Volume.from_name("qwen38-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("qwen38-vllm-cache", create_if_missing=True)

app = modal.App(APP_NAME)


@app.cls(
    image=vllm_image,
    gpu="H200",
    volumes={"/root/.cache/huggingface": hf_cache, "/root/.cache/vllm": vllm_cache},
    # Bounded: a call that hangs while Modal is still settling the snapshot
    # should die in minutes, not burn an hour of H200.
    timeout=10 * 60,
    cpu=8,
    memory=32768,
    enable_memory_snapshot=True,
    experimental_options={"enable_gpu_snapshot": True},
    # Measured 2026-08-20: scaledown_window=10 races the snapshot machinery.
    # Modal restarts the container several times while materialising the
    # snapshot, and a 10 s window kills it again before the in-flight input
    # finishes, so the first call never returns.  60 s leaves room to settle
    # while still guaranteeing the probe's next call (120 s later) is cold.
    scaledown_window=60,
)
class Engine:
    @modal.enter(snap=True)
    def build(self):
        from vllm import LLM, SamplingParams

        t = time.time()
        self.llm = LLM(
            model=MODEL,
            max_model_len=65536,
            max_num_seqs=64,
            kv_cache_dtype="fp8",
            # Headroom for the snapshot machinery itself.
            gpu_memory_utilization=0.85,
            trust_remote_code=True,
            enable_sleep_mode=True,
            speculative_config={"method": "mtp", "num_speculative_tokens": 3},
            compilation_config={"cudagraph_capture_sizes": [1, 2, 4, 8, 16, 32]},
        )
        # Warm the graphs before sleeping, so the snapshot captures a state that
        # is ready to serve rather than one that still has to compile.
        self.llm.generate(
            ["warm"] * 2,
            SamplingParams(temperature=0.0, max_tokens=8, min_tokens=8,
                           ignore_eos=True),
        )
        self.build_seconds = round(time.time() - t, 1)
        # Weights move to CPU memory, where the snapshot can hold them.
        self.llm.sleep(level=1)
        print(f"[snap] built in {self.build_seconds}s, asleep", flush=True)

    @modal.enter(snap=False)
    def wake(self):
        t = time.time()
        self.llm.wake_up()
        self.wake_seconds = round(time.time() - t, 2)
        print(f"[snap] woke in {self.wake_seconds}s", flush=True)

    @modal.method()
    def ping(self) -> str:
        from vllm import SamplingParams

        t = time.time()
        res = self.llm.generate(
            ["The fleet control plane assigns work to nodes by measured cost."],
            SamplingParams(temperature=0.0, max_tokens=64, min_tokens=64,
                           ignore_eos=True),
        )
        gen = sum(len(o.token_ids) for r in res for o in r.outputs)
        return json.dumps(
            {
                "generated": gen,
                "generate_seconds": round(time.time() - t, 2),
                "wake_seconds": getattr(self, "wake_seconds", None),
                # Present only when enter(snap=True) ran in THIS container --
                # i.e. it was built, not restored.
                "build_seconds_this_container": getattr(self, "build_seconds", None),
            }
        )


def probe(n: int = 5, gap: int = 120):
    """Call the deployed class n times, cold each time, and print every one."""
    Engine_ = modal.Cls.from_name(APP_NAME, "Engine")
    eng = Engine_()
    rows = []
    for i in range(n):
        t = time.time()
        try:
            r = json.loads(eng.ping.remote())
            wall = round(time.time() - t, 1)
            r["attempt"] = i + 1
            r["wall_seconds"] = wall
            # NOT a reliable restore signal, and left here labelled rather
            # than deleted: build_seconds is assigned inside
            # @modal.enter(snap=True), so it lives INSIDE the snapshot and is
            # present after a restore too.  What actually distinguishes the
            # cases is that a rebuilt container reports a *fresh* build time
            # while a restored one would report the same value every time.
            r["restored_flag_unreliable"] = r["build_seconds_this_container"] is None
            r["restored"] = None
            rows.append(r)
            print(
                f"  #{i+1}: wall={wall}s  restored={r['restored']}  "
                f"wake={r['wake_seconds']}  build={r['build_seconds_this_container']}",
                flush=True,
            )
        except Exception as e:  # noqa: BLE001
            rows.append({"attempt": i + 1, "status": "could-not-measure",
                         "reason": f"{type(e).__name__}: {e}"[:400]})
            print(f"  #{i+1}: FAILED {type(e).__name__}: {e}"[:200], flush=True)
        if i < n - 1:
            time.sleep(gap)

    # A restore shows up as a repeated identical build_seconds (the snapshot
    # carries one fixed value) together with a short wall time.
    builds = [r.get("build_seconds_this_container") for r in rows
              if r.get("build_seconds_this_container") is not None]
    ok = [r for r in rows
          if r.get("wall_seconds") and builds.count(
              r.get("build_seconds_this_container")) > 1]
    out = {
        "app": APP_NAME,
        "gpu": "H200",
        "attempts": rows,
        "restored_any": bool(ok),
        "best_restored_wall_seconds": min((r["wall_seconds"] for r in ok),
                                          default=None),
    }
    print(json.dumps(out, indent=2))
    with open("/tmp/q-snapshot-v2.json", "w") as f:
        f.write(json.dumps(out, indent=2) + "\n")
    if not any(r.get("wall_seconds") for r in rows):
        print("REFUSING TO REPORT A RESULT: no attempt completed", file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "probe":
        probe(int(sys.argv[2]) if len(sys.argv) > 2 else 5)
    else:
        print(__doc__)
