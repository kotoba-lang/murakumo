# ADR-260911b — Qwen3.8-Flash-Next-CYBERSECURITY-NVFP4 on one B200 with GPU memory snapshots

**Status:** accepted (measured 2026-09-11, cold restore from zero included)
**Owner decisions:** 2026-09-11 — "5 分リクエストがなければ停止"; after the cold-restore
measurement, "今の 3 分を許容します" (a warm replica at ~$6.3/h and a smaller model were
both declined; see "Why 30 s is not reachable")
**Related:** ADR-260911 (GLM-5.3-Flash cyber on 2×H200; cold start 1,190–1,530 s), ADR-260821b
(the first GPU-snapshot attempt on Qwen3.8-27B: created, never restored)

## Decision

Serve `dealignai/Qwen3.8-Flash-Next-CYBERSECURITY-NVFP4` (135.2 GB, 206 shards) from
`tools/modal-qwen38-flash-next-cyber/qwen38_flash_next_cyber_server.py` on **one B200** with
Modal CPU+GPU memory snapshots (`enable_memory_snapshot=True`,
`experimental_options={"enable_gpu_snapshot": True}`) and vLLM sleep mode, app
`murakumo-qwen38-flash-next-cyber`, endpoint label `qwen38-flash-next-cyber`.  Idle
tail: **`scaledown_window=300`** (owner: stop after 5 min without a request).

## Why (the chain of measurements that led here, all 2026-09-11)

1. The GLM-5.3-Flash cyber origin pays 20–25 min per cold start.  The lever that removes the
   cold start — GPU snapshots — is refused by Modal for that model at deploy time:
   `GPU memory snapshots are not supported for Functions with more than one GPU.`
   194.7 GB does not fit one H200 (141 GB) or one B200 (180 GB).
2. Of the same author's checkpoints, the only CYBERSECURITY build that fits one GPU is this
   one (HF sizes read via the API: GLM-5.3 cyber FP8 755.6 GB; GLM-5.3-Flash cyber W4A16
   194.7 GB; Qwen3.8-Flash-Next cyber NVFP4 **135.2 GB**).  NVFP4 wants Blackwell anyway;
   one B200 (~$6.3/h list) is cheaper than 2×H200 (~$9/h).
3. Modal's own vLLM snapshot recipe (modal-examples `llm-serving/vllm_low_latency.py`):
   vLLM subprocess with `--enable-sleep-mode` + `VLLM_SERVER_DEV_MODE=1`;
   `@modal.enter(snap=True)` starts it, waits for `/health`, warms, POSTs `/sleep?level=1`;
   `@modal.enter(snap=False)` POSTs `/wake_up`.  The bearer-gated proxy (origin_proxy.py)
   starts first, before staging, so a starting container answers 503 at once (v6; see below).

## What it took to get a snapshot (four failed starts, each a real B200 bill)

| start | image | died on | lesson |
|---|---|---|---|
| v1/v2 (16:01–16:15) | vllm-openai:v0.29.0 | `vllm: error: unrecognized arguments: --disable-log-requests` | flag renamed; Modal restarts a failed `enter` with the same input, so one bad flag = a loop of staging runs until the caller is killed |
| v3 (16:27) | v0.29.0 | `NotImplementedError: Qwen4Exp QSA requires a BF16 main KV cache` | the model card's `fp8_e4m3` KV is SGLang's; vLLM refuses `--kv-cache-dtype fp8` for this arch |
| v3 again (16:59, 17:05) | v0.29.0 | after **all 206 shards**: `ValueError: There is no module or parameter named 'ngram_embedding.weight_scale' in Qwen4ExpNGramEmbedding` | the Engram table's FP8 scale loader (`qwen4_exp/nvidia/ngram_embedding.py`) landed in vLLM #54371 on 2026-09-09, one day after the v0.29.0 tag. **This checkpoint needs the nightly.** Pinned: `vllm/vllm-openai:nightly-e7edf17cea217e52701f913cd8491fcacf2d9490` |
| v4 first (17:24) | nightly | `Runner heartbeat timeout: 1800 seconds` at 17:54:10, **63 s after `/sleep` returned** | a snapshot-enabled container's startup phase has a **30-minute wall**; `startup_timeout=3600` does not lift it. That start took 1,784 s to sleep: staging 195 s, shard load 590 s, **FlashInfer autotune 420 s** (`trtllm_bf16_moe` etc., 44 configs), KV/graphs ~60 s, sleep 41 s |
| v4 retry (17:54) | nightly | — | **succeeded**: staged 159 s, healthy at 516 s, asleep at 601 s. The shard load rode the page cache (3:06 vs 9:49) and the autotune cache on the `vllm_cache` Volume was found (`Using FlashInfer autotune cache file`), so the 7 min vanished |

Numbers of the successful start, container log:

```
18:02:04  Graph capturing finished in 16 secs           (GLM origin: 350-539 s)
18:02:04  GPU KV cache size: 882,005 tokens, max concurrency for 131,072 tokens: 6.73x
18:04:14  CuMemAllocator: sleep freed 153.52 GiB, 127.35 GiB backed up in CPU, 26.17 GiB discarded
18:04:14  snapshot-load: staged 159 s, healthy at 516 s, asleep at 601 s; snapshot follows
18:15:11  Snapshot created. Restoring Function from memory snapshot.     (11 min to write it)
18:16:10  post-snapshot enter: wake_up -> 200, healthy after 3.9 s
18:16:24  smoke: 200, "nmap -sV performs version detection on open ports…", 79 completion tokens
```

**Restore → serving: 3.9 s** (weights back from CPU, KV re-created).  The whole smoke was
3,148 s only because it had been waiting through the failed start and the snapshot creation.

## Cold restore from zero (18:29 onward)

The 18:29 request, 13 min after the last one, did **not** restore: the container built a
second snapshot from scratch (staging + load again).  Modal's guide says why: *"Memory
Snapshots are specific to the underlying worker type that created them … GPU Functions need
2–3 snapshots per GPU type."*  The first 2–3 cold starts after any deploy each cost ~21 min
of B200; only then do restores become the norm.  A redeploy resets this.

Through `api.murakumo.cloud` (gateway route landed 18:37, Worker `63e3d22a`) the first cold
request came back **524 after 125 s**: with the proxy started only after the enter hooks,
nothing answered on the port during a snapshot-creating start, Modal held the connection,
and Cloudflare's subrequest limit turned that into a 524 the gateway cannot poll.  Fixed in
v6: the proxy starts before staging (503 from the first seconds; its socket is in the
snapshot, as vLLM's own is in Modal's example), and the gateway treats 524 like 503.

## Steady state through `api.murakumo.cloud` (v6, 18:45–20:50)

| state | n | end-to-end | origin log |
|---|---|---|---|
| cold, worker already has the snapshot | 2 | **189.6 s, 175.7 s** | `Restoring Function from memory snapshot` → 164–181 s → `wake_up … healthy after 3.0–4.4 s` |
| cold, right after a snapshot was written on that host | 1 | — | restore 87 s + wake 4.4 s |
| warm (< 5 min since last request) | 2 | **0.97 s** (curl), 2.0 s first token in hermes | cold-wait 0 |
| cold, worker without a snapshot yet (first 2–3 after a deploy) | 3 | 958 s, 1,565 s, ~1,600 s | staging + load + sleep + 11–12 min snapshot write + restore |

The gateway budget was raised 1,500 → 2,000 s after the 1,565 s case returned `503 model_loading`
5 s before the origin could answer.  Hermes profile `qwen38-cyber` (root
`scripts/hermes-murakumo-api.cljk`) carries 2,000 s the same way; one turn measured 2.0 s warm.

## Why 30 s is not reachable with this model (owner asked; answered 2026-09-11 20:45)

The cold path is Modal moving ~130 GB of snapshot (127 GiB of weights parked in CPU RAM by
sleep level 1) back to the host: 164–181 s from remote, 87 s when the host still has it, i.e.
0.8–1.5 GB/s.  30 s would need ≥ 4.5 GB/s.  Modal's guide says it directly: *"If the majority
of your initialization latency is spent loading weights, GPU Memory Snapshots will generally
not improve your cold start times."*  Its published wins are 0.5B–8B models (45 s → 5 s).
Sleep level 2 (drop weights, reload from NVMe on wake) and snapshotting with the weights on
the GPU move the same bytes.  The remaining levers each break a stated requirement: a ~20 GB
model (no cyber build exists at that size), `min_containers=1` (~$6.3/h, not "only when a
request comes"), or a predictive pre-ping from the client (hides, does not shorten).  The
owner accepted 3 min.

## Standing caveats

- "3.9 s" is the vLLM wake at the end of a restore, not the cold start; the cold start is
  the ~3 min table above.  The first report of the day said 3.9 s before the from-zero
  number existed — corrected the same evening.
- `scaledown_window=300` per the owner.  Every redeploy creates a **new snapshot** on its
  first start (~10 min to sleep + ~11 min to write), so config changes are not free.
- The first start of any new deploy must reach `/sleep` within 30 min.  What keeps it
  under: the autotune cache Volume (commit it explicitly after `/health`), NVMe staging,
  and the limited `cudagraph_capture_sizes`.  If the Volume is lost, the first start is
  ~1,780 s and will be killed — recreate the cache with a plain (non-snapshot) run first.
- Quality is unmeasured (same as the GLM one).  The model card's numbers are the author's.
  MTP + hybrid layers disables cross-request prefix caching (vLLM warning at start).
- Routed on `api.murakumo.cloud` as `qwen3.8-flash-next-cybersecurity-nvfp4`
  (network-awai/cloud-murakumo-api `src/modal_hosted_model.js`, `coldStartBudgetMs` 2,000 s
  to cover a snapshot-creating start; 303/524 treated as loading).  Anonymous, like the GLM
  id — same cost exposure.  Hermes profile `qwen38-cyber` on this machine.
- `modal run …::smoke` builds an ephemeral twin of the app and prints
  `Memory snapshots are disabled for ephemeral apps` — that line is about the twin, not the
  deployed origin the smoke talks to.

## Resume point

```bash
# cold restore from zero: run ≥ scaledown_window after the last request, read elapsed_s
modal run tools/modal-qwen38-flash-next-cyber/qwen38_flash_next_cyber_server.py::smoke
# container log: "Restoring Function from memory snapshot" + "post-snapshot enter: … healthy after N s"
modal app logs murakumo-qwen38-flash-next-cyber --timestamps | grep -E "Restor|post-snapshot|snapshot-load"
# one turn through hermes
~/.hermes/hermes-agent/venv/bin/hermes -p qwen38-cyber chat -q "Which model are you?"
# next: a 20-30 prompt quality comparison against glm-5.3-flash-cybersecurity-w4a16 (gap 2 of ADR-260911)
```
