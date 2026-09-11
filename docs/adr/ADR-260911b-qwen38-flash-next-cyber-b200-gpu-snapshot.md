# ADR-260911b — Qwen3.8-Flash-Next-CYBERSECURITY-NVFP4 on one B200 with GPU memory snapshots

**Status:** accepted (measured 2026-09-11; cold-restore-from-zero number pending, see "Resume point")
**Owner decision:** 2026-09-11 — "3.9 s なら十分 cold start で成り立つ. 5 分リクエストがなければ停止"
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
   starts in the `@modal.web_server` method after the enter hooks.

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

## Standing caveats

- The 3.9 s above is a restore **on the host that had just created the snapshot**.  The
  number that matters for scale-to-zero — restore after the container count reached 0 —
  was scheduled for 18:29 (600 s after the last request) and is recorded under "Resume
  point" when it lands.  Until then this ADR claims restore *works*, not what an idle
  cold start costs.
- `scaledown_window=300` per the owner.  Every redeploy creates a **new snapshot** on its
  first start (~10 min to sleep + ~11 min to write), so config changes are not free.
- The first start of any new deploy must reach `/sleep` within 30 min.  What keeps it
  under: the autotune cache Volume (commit it explicitly after `/health`), NVMe staging,
  and the limited `cudagraph_capture_sizes`.  If the Volume is lost, the first start is
  ~1,780 s and will be killed — recreate the cache with a plain (non-snapshot) run first.
- Quality is unmeasured (same as the GLM one).  The model card's numbers are the author's.
  MTP + hybrid layers disables cross-request prefix caching (vLLM warning at start).
- Not yet routed on `api.murakumo.cloud`: the gateway's `modal_hosted_model.js` needs an
  entry for `qwen3.8-flash-next-cybersecurity-nvfp4` with a `coldStartBudgetMs` sized from
  the measured restore, and hermes `profile-overrides` a matching profile.  Until then the
  origin is reachable only with the origin bearer (from inside Modal via `smoke`).
- `modal run …::smoke` builds an ephemeral twin of the app and prints
  `Memory snapshots are disabled for ephemeral apps` — that line is about the twin, not the
  deployed origin the smoke talks to.

## Resume point

```bash
# cold restore from zero: run ≥ scaledown_window after the last request, read elapsed_s
modal run tools/modal-qwen38-flash-next-cyber/qwen38_flash_next_cyber_server.py::smoke
# container log: "Restoring Function from memory snapshot" + "post-snapshot enter: … healthy after N s"
modal app logs murakumo-qwen38-flash-next-cyber --timestamps | grep -E "Restor|post-snapshot|snapshot-load"
# next: gateway route (cloud-murakumo-api src/modal_hosted_model.js) + hermes profile, then a
# 20-30 prompt quality comparison against glm-5.3-flash-cybersecurity-w4a16
```
