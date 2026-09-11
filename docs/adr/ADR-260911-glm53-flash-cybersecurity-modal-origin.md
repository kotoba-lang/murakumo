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

### Second cold start, same day, with `PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True`

Request 12:00:41 JST, first 200 12:26:17 — **1,528 s ≈ 25.5 min**, i.e.
*slower*, on a different (slower) Modal host.  Raw: same JSON, key `cold2`.

| phase | run 1 | run 2 | note |
|---|---|---|---|
| stage Volume → NVMe | 221 | 323 | 0.88 → 0.60 GB/s: host variance, same code |
| vLLM process start → `Loading model from scratch` | 110 | 265 | same image; slower host |
| weight load | 480 | **382** | the allocator fix: 0 OOM-retry warnings (was thousands), 2.5 s/shard vs 3.8 |
| `init engine` (CUDA graph capture) | 361 | 539 | the vLLM cache Volume (deep_gemm, flashinfer autotune) did **not** shorten it |

So: the allocator fix is real and kept; the cache Volume is not a lever;
and the honest cold-start number is a **range, 1,190–1,530 s**, set by
which host Modal hands out.  Every downstream budget was sized from the
slower one plus margin, not the faster one:

- gateway (`cloud-murakumo-api` `src/modal_hosted_model.js`): holds the
  caller's request through the origin's 503 "model loading" for up to
  **2,400 s** (5 s poll), then 503 `model_loading` with `retry-after` —
  never a hang.  A 1,500 s budget sized from run 1 would have missed run 2
  by 29 s.
- Hermes profile `glm53-cyber` (root `scripts/hermes-murakumo-api.cljk`,
  `profile-overrides`): `request_timeout_seconds` and `stale_timeout_seconds`
  **2,400**; the fleet contract's 900 would drop a cold request at the
  client after paying for 15 minutes of GPU.

  ⚠ **Corrected 2026-09-11, the same day.** Those two YAML keys were never
  in force.  Hermes resolves them by `agent.provider`, which for a
  `providers:` entry that is not a built-in is the literal `custom`
  (`provider=custom` on every log line), so `providers.murakumo.*` is never
  read and the stream stale detector runs at its **180 s** env default
  (`HERMES_STREAM_STALE_TIMEOUT`).  Measured by running the profile against
  a cold origin: killed at 180 s, retried, killed at 180 s, fell back to
  `z-ai/glm-5.3-flash` on `openrouter-free`, and surfaced
  `HTTP 401: Missing Authentication header` to the user — the error the
  owner reported.  The 12:28 "verified" run had hit a warm origin (first
  token at 40 s) and could not have told.  Fix: the override now carries
  `:env {"HERMES_STREAM_STALE_TIMEOUT" 2400}`, rendered into the profile's
  `.env` (loaded with override=True before the agent starts) and checked by
  `--verify` as finding `profile-env-timeout`.  Re-measured with it in
  place, same cold origin: hermes held **232.6 s** to first token (past
  the 180 s that had killed it) and answered; a plain curl through the
  gateway on the same load reported `x-murakumo-cold-wait-ms: 472203`
  and 200.  The same is true of the
  fleet contract's 600/900: every profile has run at 180 s since the
  cutover.  That is a fleet-wide change and is left for its own measurement.

## Public surface (2026-09-11)

- `POST https://api.murakumo.cloud/v1/chat/completions` with
  `"model": "glm-5.3-flash-cybersecurity-w4a16"` — routed from the JS entry
  (the compiled worker.cljs cannot ship without a governed artifact refresh),
  `reasoning_effort` defaulted to `"low"`, response headers
  `x-murakumo-route-head: modal` and `x-murakumo-cold-wait-ms` (the load
  wait, 0 when warm).  Verified warm end-to-end: 200, correct answer.
- `GET /v1/models` lists it with `context_window 131072`,
  `scale_to_zero true`, `cold_start_budget_seconds 2400`.
- `GET /v1/token-limits?model=…` → 131072 / 16384, `serving-slot-default`.
- Hermes: profile `glm53-cyber` (no cron), one real turn verified: the model
  identified itself and answered with a tool call.
- `infer.edn` carries the registry entry with `:model/runtime :modal-vllm`
  so the fleet planner never tries to place 195 GB on a mac mini.

**Anonymous, like every fleet-hosted id.** That is a cost exposure the
qwen3.8 Modal profiles did not have at this size: one anonymous caller
keeping the origin warm is ~$9/h at list.  Gating it behind a credential is
a separate decision, not made here.

## Cost levers kept

- `min_containers=0`: idle is free.  `scaledown_window=120` (the Qwen3.8
  profile uses 300): every burst pays 2 minutes of tail, not 5 — on 2x H200
  that is $0.30 rather than $0.76 per burst.
- Cold start is the other lever and is now dominated by the Volume read;
  see the numbers above before deciding whether a warm replica is cheaper
  than the cold starts a workload actually incurs.  That decision needs a
  request inter-arrival trace, which does not exist yet.

### Stability through hermes, after the timeout fix (2026-09-11 14:24–14:47)

Driver: a scratch nbb script running `hermes -p glm53-cyber chat -Q -q`;
one row per call, exit code and wall seconds recorded.  Origin had been
reaped (idle > 120 s) before the first call.

| phase | calls | ok | seconds |
|---|---|---|---|
| cold, one held call | 1 | 1 | **1,347.9** (third cold start measured; inside the 1,190–1,530 range) |
| warm, sequential, 8 distinct prompts | 8 | 8 | 3.8–5.2 |
| warm, gateway direct (curl control) | 1 | 1 | 1.25, `x-murakumo-cold-wait-ms: 0` |
| warm, 3 hermes in parallel | 3 | 3 | 5.0 / 8.4 / 8.4 |
| **total** | **13** | **13** | |

`Stream stale` and `Fallback activated` in the profile log during the
window: 0.  Answers matched the prompts (`READY`, `42`, `STABLE`,
`PARALLEL0..2`, one-sentence CVE/nmap/SQLi/CVSS).  Of hermes's ~4 s warm
turn, ~1.2 s is the model; the rest is hermes's own startup and its ~13k
token fixed prefix.  This measures the `glm53-cyber` profile only; a
`/model` switch inside another profile (the `itonami` session that
surfaced the bug) still runs at hermes's 180 s default.

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

## Where this stands (session close, 2026-09-11)

Landed on default branches, all verified live:

| repo | commit | what |
|---|---|---|
| kotoba-lang/murakumo | `36534da` (PR #377 + API merge) | origin server, download/stage/smoke, allocator fix, `infer.edn` entry, this ADR |
| network-awai/cloud-murakumo-api | `933ba2c` (PR #251 + 2 API merges) | gateway route, catalogue entry, token limits, 2,400 s hold; `npm run deploy` repaired on main; **deployed**, Worker version `6f06bedb` |
| com-junkawasaki/root | `6b804c67` (API merge) | `scripts/hermes-murakumo-api.cljk` `profile-overrides`; fleet `--apply`'d, profile `glm53-cyber` rendered |
| root west pins | `b8657111` (murakumo), `6b87316a` (cloud-murakumo-api) | |
| com-junkawasaki/root | `8548efa3` (PR #3097, API merge) | `.env` timeout carried by the script; `profile-env-timeout` finding; applied on this machine |
| kotoba-lang/murakumo | `db7cacc4` (PR #378) + this update | ADR correction, gap 3, stability table |
| root west pin (murakumo) | `58f641e4` → advanced again after this merge | |

Modal: app `murakumo-glm53-flash-cyber` deployed; Volumes
`glm53-flash-cyber-hf-cache` (195 GB, v1) and `glm53-flash-cyber-vllm-cache`;
the v2 Volume trial was deleted.  The 753B FP8 file is committed and marked
NOT DEPLOYED.

## Open gaps, in priority order

1. **Anonymous access to a $9/h origin.** Same policy as the other
   fleet-hosted ids, but the exposure is 4x theirs.  Decision needed:
   gate `glm-5.3-flash-cybersecurity-w4a16` behind a Murakumo credential
   (the gateway already verifies passkey/Biscuit on the chat route), or
   accept.  Until decided, watch Modal spend.
2. **Quality is unmeasured.**  Nothing here says the Flash model answers
   the owner's tasks as well as the 753B FP8 would; the owner chose it on
   cost.  A 20–30 prompt comparison through the same gateway is the
   next measurement, and it costs one cold start per model.
3. **A client that gives up mid-load kills the load.**  The gateway polls
   the origin only while the caller's request is attached; once the caller
   disconnects (hermes at 180 s, curl at its `-m`), no input reaches the
   origin and `scaledown_window=120` reaps the container **while vLLM is
   still loading**, so the next call starts the 20–25 min from zero.  The
   shape was on the table 2026-09-11 (an `itonami` session's attempts at
   12:34, 12:43 and 13:27 each fell back within 9 min, and the origin was
   still loading shards at 13:50); whether the container was reaped
   between them was **not measured** — the origin log the CLI returns is
   the last ~100 lines.  The client-side fix above closes it for the
   profile; the
   origin-side lever — keep the container alive until the first `/health`
   200 regardless of inputs, or a larger `scaledown_window` — is untried.
4. **Cold start is 20–25 min and host-dependent.**  The levers still
   untried, in order of expected effect: (a) keep one container warm
   during working hours (`min_containers=1` on a schedule; $9/h while
   warm), (b) Modal GPU memory snapshots (experimental; unproven with
   vLLM TP=2), (c) `--enforce-eager` to skip the 361–539 s graph capture
   at a decode-speed cost.  Measure before choosing; the vLLM cache
   Volume already proved not to be one.
5. **fleet-manifest is not updated.** Its `verify-manifest.kotoba` calls
   `nbb build-manifest.cljs` (renamed away in its PR #1) and the committed
   `fleet.edn` is 130 profiles behind the machine.  `glm53-cyber` is on
   this machine only until that repo is repaired and rebuilt.
6. **Root nbb scripts requiring `scripts.nbb-compat` are broken** since
   the `.cljk` rename (`root-worktree`, `west-pin-put`, …): nbb resolves
   `.cljs`/`.cljc`, not `.cljk`.  This session worked around it with a
   temporary `.cljs` copy on the classpath.  Not this ADR's to fix.

## Resume point

```bash
# Is it still up and routed?  (warm: 200 in ~1 s; cold: expect up to 25 min)
curl -s -D - https://api.murakumo.cloud/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"glm-5.3-flash-cybersecurity-w4a16","messages":[{"role":"user","content":"Say OK."}],"max_tokens":8}' \
  | grep -i 'x-murakumo-cold-wait-ms\|"content"'
# Hermes, one turn on the profile
~/.hermes/hermes-agent/venv/bin/hermes -p glm53-cyber chat -q "Which model are you?"
# Origin directly (bearer stays inside Modal), cold or warm
modal run tools/modal-glm53-cyber/glm53_flash_cyber_server.py::smoke
# Conformance of the hermes fleet incl. the .env timeout (0 clean / 1 findings / 2 could-not-measure)
nbb scripts/hermes-murakumo-api.cljk --findings --no-probe
# Next measurement: gap 2 (quality), then gap 3/4 (keep-alive during load; a warm-window trial)
```
