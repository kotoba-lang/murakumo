# ADR-260911: self-host GLM-5.3-Flash-CYBERSECURITY-W4A16 on Modal (2x H200, scale-to-zero)

- Status: accepted
- Date: 2026-09-11
- Deployment implementation: `tools/modal-glm53-cyber/glm53_flash_cyber_server.py`
- Not deployed, kept for reference: `tools/modal-glm53-cyber/glm53_cyber_server.py`
  (the 753B FP8 sibling on 8x H200; never run past `modal deploy`)

## Decision

Serve `dealignai/GLM-5.3-Flash-CYBERSECURITY-W4A16` (revision
`c95e33010569089226763057136edfcfba488065`) as a private Modal origin,
`https://junkawasakicom--glm53-flash-cyber.modal.run`, behind the same
bearer-checking `origin_proxy.py` as the Qwen3.8 throughput profile.  Public
model id at the origin: `glm-5.3-flash-cybersecurity-w4a16`.  The gateway
(`network-awai/cloud-murakumo-api`) is **not** yet wired to it — that is the
next step, not part of this ADR.

The owner asked for `dealignai/GLM-5.3-CYBERSECURITY-FP8` first, then asked
whether quantization could cut the running cost.  Three candidates were
sized from the Hub, not estimated:

| candidate | weights | smallest fit | Modal list price |
|---|---|---|---|
| GLM-5.3-CYBERSECURITY-FP8 (753B `glm_moe_dsa`) | 755.6 GB / 282 shards | 8x H200 (8x H100 = 640 GB does not hold it) | $36.3/h |
| self-quantized NVFP4 of the same | ~465 GB (the author's UNCENSORED-NVFP4 size) | 4x H200 / 4x B200 | $18.2 / $25.0/h |
| **GLM-5.3-Flash-CYBERSECURITY-W4A16** (`glm5_next`, 45 layers, 288 experts) | **194.7 GB / 120 shards** | **2x H200** | **$9.1/h** |

The owner chose the third.  The second was rejected: the author publishes no
cyber NVFP4, a 753B modelopt pass is hours on 8x H200 plus an unmeasured
quality delta, and NVFP4 MoE on Hopper goes through Marlin rather than
native kernels.

## What was measured (2026-09-11)

- `Glm5NextForConditionalGeneration` exists only in vLLM `main`, not in any
  0.2x release (checked v0.27.1 / v0.28.0 / v0.29.0 registries).  The image is
  the official `vllm/vllm-openai:glm53-flash-x86_64-cu130` tag the model card
  names (vLLM `0.28.1rc1.dev580+g385dce36b` inside).  It has no bare `python`,
  so Modal's own interpreter is added and runs the proxy; vLLM stays on the
  image's venv.
- Weights fit: `Model loading took 90.75 GiB` per rank on 141 GB H200s.
- **Reading the Volume through vLLM's loader was the cold-start cost**:
  shard-by-shard through the 9P mount ran at ~0.11 GB/s — **1,700 s** for the
  weights alone, with the CUDA caching allocator logging OOM-and-retry
  warnings continuously (the same warnings persist when loading from NVMe;
  they are not the Volume's fault and the load completes).
- Parallel reads of the same Volume: 1.39 GB/s (32 threads, CPU host),
  0.5–0.9 GB/s on the H200 host.  Volume v2 (beta) was not faster: 0.88 GB/s
  at 8 threads, 0.74 at 32, on a CPU host.  v1 is kept.
- **Running the parallel copy as threads inside the Modal function process
  killed the container.**  Modal's container runtime lives in that process —
  heartbeat and the HTTP forwarding to port 8000 — and was GIL-starved for
  70–100 s at a time (`Heartbeat attempt failed (attempt_dur=100.41)`), after
  which Modal cancelled the input (`Server has lost track of input`, 408
  `Missing request`) and stopped the container.  Reducing threads from 32 to 8
  did not help.  Moving the copy to a **subprocess** did: 194.7 GB staged in
  221 s (0.88 GB/s) with no heartbeat failure, then vLLM loads from NVMe at
  ~3.8 s/shard instead of ~14.
- `modal run file::smoke` builds an ephemeral twin of the app, and
  `serve.get_web_url()` inside it points at the twin — the first "cold"
  measurement started a second 2x H200 container that was SIGTERMed when
  the run ended.  `smoke` now resolves the deployed function with
  `modal.Function.from_name`.
- Modal routes to a `@web_server` only after the decorated function
  returns, so staging and the vLLM launch run on a thread and `serve()`
  returns at once; the proxy answers 503 "model loading" meanwhile, which
  the gateway already retries.

Cold-start and first-token numbers are in the section below, filled from the
smoke run against the deployed app.

## Cold start, measured

One cold start of the deployed app, request sent at 11:32:08 JST, first
200 at 11:52:01 — **1,187 s ≈ 19.8 min, ≈ $3.0 of 2x H200 at list price**.
Raw: `data/ADR-260911-glm53-flash-cyber-cold-start.json`.

| phase | seconds | note |
|---|---|---|
| container start → proxy listening | 12 | |
| stage 194.7 GB Volume → NVMe (subprocess, 16 readers) | 221 | 0.88 GB/s |
| vLLM process start → `Loading model from scratch` | 110 | imports, MTP config, NCCL |
| weight load from NVMe (120 shards) | 480 | 3.8 s/shard; allocator OOM-retry spam throughout |
| `init engine` (profile, KV cache, CUDA graph capture) | 361 | 51 piecewise graphs |
| server ready → first 200 | 5 | |

Warm, 51 s later: **200 in 1.0 s for 77 completion tokens** (single
request, MTP on, `reasoning_effort: "low"`, 0 reasoning tokens).  Both
requests answered correctly; unauthenticated `GET /v1/models` → 401.

Not measured: a *second* cold start.  `/root/.cache/vllm` is a Volume and
now holds `deep_gemm` and `flashinfer_autotune_cache` from this run, so the
361 s engine-init phase may shrink next time; there is no
`torch_compile_cache` entry, so do not assume it does.

## Cost levers kept

- `min_containers=0`: idle is free.  `scaledown_window=120` (the Qwen3.8
  profile uses 300): every burst pays 2 minutes of tail, not 5 — on 2x H200
  that is $0.30 rather than $0.76 per burst.
- Cold start is the other lever and is now dominated by the Volume read;
  see the numbers above before deciding whether a warm replica is cheaper
  than the cold starts a workload actually incurs.  That decision needs a
  request inter-arrival trace, which does not exist yet.

## Standing caveats

- **Quality was not measured.**  The model card's HarmBench / MMLU figures
  are the author's.  Nothing here says the Flash model answers the owner's
  actual tasks as well as the 753B one would.
- `reasoning_effort` on this checkpoint honours only `"low"` and `"high"`
  (everything else falls through to max); the smoke sends `"low"`.
- The CUDA allocator OOM-and-retry spam during weight loading is not
  understood.  It does not prevent startup; it does flood the container
  log and probably slows loading.
- The ephemeral disk is 512 GiB because that is Modal's minimum for the
  option; the model needs 195 GB of it.
