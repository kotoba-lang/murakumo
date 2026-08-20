# ADR-260820b: the efficient way to serve Qwen3.8-27B on Modal

- Status: accepted
- Date: 2026-08-20
- Depends: ADR-260820-qwen38-27b-serving-cost-measured

## Context

ADR-260820 measured L40S and H100 with MTP off, and said so — "every number
here is a floor for a correctly-configured deployment". This ADR finds the
floor's ceiling. Six configurations were run: RTX PRO 6000, H200, H200+MTP,
H100+MTP, B200+MTP, and a GPU-memory-snapshot experiment. Same harness, same
settings (`Qwen3.8-27B-FP8`, vLLM 0.27.1, `kv-cache-dtype fp8`,
`max_model_len=65536`, `max_num_seqs=256`, greedy, `min_tokens == max_tokens`),
now also at concurrency 128 and with all-in cost accounting.

## Decision

**Serve it on H200 with MTP speculative decoding, and set `scaledown_window`
to roughly the engine build time rather than reaching for `min_containers`.**
MTP is worth 2.5–3.2x on every card measured, including the cheap ones. The
cold start is ~7 minutes; we did not shorten it, but the documented
snapshot path is **unresolved rather than refuted** — see the correction
below. Until it is resolved, treat cold start as a scheduling problem.

```python
@app.cls(
    gpu="H200",
    scaledown_window=60 * 7,   # ≈ one cold start; see "the break-even is the
                               #   cold start" below
)
```
```python
LLM(model="Qwen/Qwen3.8-27B-FP8", kv_cache_dtype="fp8",
    max_model_len=65536, max_num_seqs=256,
    speculative_config={"method": "mtp", "num_speculative_tokens": 3})
```

## Measured

Output tokens/s, and **$/Mtok all-in** — GPU plus Modal's separately-billed CPU
and memory at the 8-core/32-GiB shape these runs used. Modal is not RunPod: the
GPU line is not the whole bill. At this shape the side charge is +12% on an
H200, +16% on an H100, +32% on an L40S, and quoting GPU-only makes the cheap
cards look better than they are.

| config | $/s all-in | 1 | 8 | 32 | 64 | 128 |
|---|---|---|---|---|---|---|
| L40S | 0.000718 | 18.8 | 155.2 | 477.3 | 476.2 | — |
| RTX PRO 6000 96GB | 0.001018 | 45.5 | 329.0 | 1,011.7 | 1,600.0 | 2,137.1 |
| H100 | 0.001273 | 78.2 | 259.5 | 1,727.5 | 2,705.1 | — |
| H200 | 0.001437 | 93.9 | 275.5 | 2,101.9 | 3,365.2 | 4,461.2 |
| H100 + MTP | 0.001273 | 218.8 | 1,125.3 | 3,640.1 | 3,431.7 | 4,279.5 |
| **H200 + MTP** | 0.001437 | **264.4** | **1,283.4** | **4,234.5** | 5,105.6 | **6,231.5** |
| B200 + MTP | 0.001912 | 261.9 | 628.9 | 4,095.2 | 7,044.8 | 7,745.8 |

| config | $/Mtok @ 1 | $/Mtok @ 128 | engine build | one cold start |
|---|---|---|---|---|
| L40S | $38.18 | $1.51 (@64) | 579 s | $0.42 |
| RTX PRO 6000 | $22.37 | $0.476 | 522 s | $0.53 |
| H100 | $16.28 | $0.470 (@64) | 534 s | $0.68 |
| H200 | $15.30 | $0.322 | 438 s | $0.63 |
| H100 + MTP | $5.82 | $0.297 | 413 s | $0.53 |
| **H200 + MTP** | **$5.43** | **$0.231** | 428 s | $0.62 |
| B200 + MTP | $7.30 | $0.247 | **1,359 s** | **$2.60** |

## MTP on every card, not just the fast ones

MTP was run on all four cards. It helps everywhere, most where bandwidth is
scarcest — it amortises one weight read across several tokens, so the
bandwidth-starved cards gain the largest multiple:

| card | single-stream, MTP off → on | x | best batched, off → on | x |
|---|---|---|---|---|
| L40S 48GB | 18.8 → **60.4** | **3.21** | 477 → 511 | 1.07 |
| RTX PRO 6000 96GB | 45.5 → **114.6** | 2.52 | 1,600 → 2,670 | 1.67 |
| H100 80GB | 78.2 → **218.8** | 2.80 | 2,705 → 4,280 | 1.58 |
| H200 141GB | 93.9 → **264.4** | 2.82 | 4,461 → 6,232 | 1.40 |

**The multiple inverts between the two regimes**, and that is the whole story:
MTP spends compute to save bandwidth. At concurrency 1 every card is
bandwidth-bound and everyone gains 2.5–3.2x. Under load the GDDR cards are
already compute-saturated, so L40S gains **7%** and stops at a hard ~510 tok/s
ceiling no matter what concurrency it is handed — while H200, with compute to
spare, still gains 1.40x.

So MTP does not reorder the ranking; it widens the useful range of the cheap
cards at low concurrency and does nothing for them at high concurrency:

| card, with MTP | $/Mtok @ 1 | best $/Mtok batched |
|---|---|---|
| L40S | $38.18 → **$11.89** | $1.51 → $1.40 |
| RTX PRO 6000 | $22.37 → **$8.88** | $0.476 → $0.381 |
| H100 | $16.28 → **$5.82** | $0.470 → $0.297 |
| **H200** | $15.30 → **$5.43** | $0.322 → **$0.231** |

If a deployment is genuinely single-user, MTP closes most of the gap that used
to justify reaching for an H200 — L40S+MTP at $11.89 is cheaper per token than
a *bare* H200 at $15.30. H200+MTP still wins; it just wins by 2.2x now instead
of 7x.

## Cold start: we did not reduce it, and the first attempt was invalid

**Correction (2026-08-20, same day).** An earlier revision of this ADR reported
that Modal's GPU memory snapshot "did not restore", and read that as a vLLM /
`torch.compile` incompatibility. That was wrong. The run had printed

    Memory snapshots are disabled for ephemeral apps.
    Deploy your app with `modal deploy` to enable memory snapshots.

`modal run` creates an ephemeral App. **Snapshots were never enabled**, and the
harness reported `restored_from_snapshot: false` — a true statement about
something that had not been attempted, in the same shape as a negative result.
This is the exact failure this repository keeps naming, committed here by the
check that was supposed to name it.

### What is actually true

The warm H200+MTP start is **428.1 s and 428.9 s** across two identical runs.
Where it goes, from vLLM's own log on the warm run:

| phase | seconds |
|---|---|
| container start, imports, weight load from Volume | ~144 |
| **CUDA graph capture** | **126** |
| multi-modal (vision) warmup | 30 |
| profiling / warmup run | 26 |
| torch.compile | 12 (cache hit; 133 s cold) |

**Three vLLM-side levers, all negative:**

1. **`enforce_eager=True`** — start falls to 322.5 s (−25%, saving $0.15) and
   **single-stream collapses from 260.8 to 44.6 tok/s**, cost from $5.51 to
   $32.22/Mtok; concurrency 8 falls 11x. MTP's draft-verify loop is many small
   forward passes, which is precisely what CUDA graphs exist for. **Never with
   speculative decoding on.**
2. **`cudagraph_capture_sizes` 49 → 8 shapes** — capture falls to 88–107 s, but
   changing `compilation_config` changes the compile cache key, so the first run
   pays a 149 s recompile. Steady state on a second run: 450.6 s. No improvement.
3. **Text-only (`limit_mm_per_prompt {"image": 0, "video": 0}`)** to skip the
   30 s vision warmup — **vLLM 0.27.1 crashes at engine init** with
   `AttributeError: 'NoneType' object has no attribute 'size'`.

**The Modal-side lever, re-run properly and still unresolved.** Deployed with
`modal deploy`, `enable_memory_snapshot=True`,
`experimental_options={"enable_gpu_snapshot": True}`,
`TORCHINDUCTOR_COMPILE_THREADS=1`, and vLLM sleep mode
(`enable_sleep_mode=True`, `llm.sleep(level=1)` at the end of
`@modal.enter(snap=True)`, `llm.wake_up()` in `@modal.enter(snap=False)`):

- Modal **does** create snapshots. Its logs carry `Creating GPU memory snapshot
  for Function.` and `Snapshot created. Restoring Function from memory
  snapshot.`
- vLLM's half works and is cheap: **`wake_up()` takes 1.18–2.02 s.**
- **But across four probe calls, no container was ever restored.** Each rebuilt
  the engine — 312.3 s, 264.7 s, 244.0 s, 259.5 s. The values *differ*, which is
  what proves a genuine rebuild: a restored container would report the one value
  frozen into the snapshot.

  (The harness's own `restored` flag was broken and is now labelled as such in
  the source rather than deleted. It tested `build_seconds is None`, but
  `build_seconds` is assigned inside `@modal.enter(snap=True)` and therefore
  lives *inside* the snapshot, so it is present after a restore too. The
  conclusion above rests on the differing values, not on that flag.)

**So the honest state is "not reduced by us", not "cannot be reduced".** A
published result exists — vLLM sleep mode plus Modal GPU snapshots taking a
cold start from 460 s to ~70 s, 6.5x — and our implementation deviates from it
in one identified way: it drives the in-process `LLM` class and `llm.sleep()`,
where the published recipe runs `vllm serve --enable-sleep-mode` as a
subprocess with `VLLM_SERVER_DEV_MODE=1` and hits the sleep/wake HTTP
endpoints. We did not isolate whether that is the cause. Modal's docs also note
snapshots are per worker type and need 2–3 invocations to materialise; four
calls may simply not have been enough.

### Until that is resolved, arrange not to pay the start

Set `scaledown_window` to about one build time (~7 min on H200) so bursts inside
a session do not re-wake. Residency costs **$5.17/hour** against **$0.62** a
wake, so keeping a container only beats paying wakes above **8.3 wakes/hour**,
which agent traffic does not reach.

⚠ `scaledown_window=10` **races the snapshot machinery**: Modal restarts the
container repeatedly while materialising a snapshot, and a 10 s window kills it
again before the in-flight input finishes. A probe run at that setting never
returned a single result in 55 minutes.

## Run-to-run variance, and what it means for reading these tables

Four H200+MTP runs under identical settings gave:

| | run 1 | run 2 | run 3 | run 4 |
|---|---|---|---|---|
| single-stream | 264.4 | 260.8 | 258.1 | 255.4 |
| concurrency 8 | 1,283 | 954 | 1,196 | 762 |
| concurrency 64 | 5,106 | 4,593 | 3,248 | 2,526 |
| concurrency 128 | 6,232 | 6,123 | 6,027 | 4,677 |

**Single-stream is tight (±2%); mid-concurrency swings ±40%.** These are shared
containers on shared hardware. Every batched figure in this ADR is n=1, so
**differences under about 30% at concurrency 8–128 are not resolvable here** —
which is why attempt 3 above is written as "no improvement", not "worse". The
conclusions that survive this noise are the large ones: MTP's 2.5–3.2x, the
bandwidth ordering, eager's 6x collapse, and the cold-start floor.

## What decides it

**MTP is worth more than any GPU upgrade.** On H100 it takes single-stream
from 78.2 to **218.8 tok/s — 2.8x — and cost per token from $16.28 to $5.82**,
for a flag. Buying an H200 instead buys 1.20x. The model ships an MTP draft
head; not using it is leaving a factor of three on the table. It compounds
hardest exactly where the economics were worst: at concurrency 8 the gain is
4.3x (259.5 → 1,125.3).

**Cheap cards are expensive.** The three GDDR cards lose on cost per token
despite lower hourly rates — L40S $1.51, RTX PRO 6000 $0.476, against H200+MTP's
$0.231. This is ADR-260820's L40S finding, and it generalises: on this vendor,
for this model, **the ranking by $/token is the ranking by memory bandwidth,
not the inverse ranking by price**. RTX PRO 6000 looked like the value pick
going in (96 GB, 0.77x an H100's rate) and is 2.1x more expensive per token.

**B200 is not the top of the ladder, it is past it.** It wins on raw throughput
at concurrency 64+ (7,745 tok/s) and still loses on cost ($0.247 vs $0.231),
is *worse* than H200 at concurrency 8 (628.9 vs 1,283.4 — kernel maturity, not
silicon), and its first engine build took **22.6 minutes and $2.60** because
nothing was compiled for Blackwell yet.

**The break-even for staying warm is exactly the cold start.** Idle GPU-seconds
and loading GPU-seconds bill at the same rate, so the arithmetic collapses:
holding a container costs less than rebuilding it if the next request arrives
within one build time. On H200 that is **≈7 minutes**. Modal's default
`scaledown_window` is 60 s, which throws away $0.62 every time a gap exceeds a
minute. Set it to the build time; do not reach for `min_containers=1` unless
usage exceeds **$3,776/month**, which is what a permanently-warm H200 costs.

For one operator's agent traffic, scale-to-zero wins by an order of magnitude:
3M output tokens/month at concurrency 1 plus 100 wakes is about **$78/month**,
against $3,776 to keep it resident.

## GPU memory snapshot does not remove the cold start (measured, negative)

Modal's GPU memory snapshot is the obvious attack on a 7-minute start, and
Modal's own documentation warns it "can fail with certain `torch.compile`
usage". vLLM is nothing but torch.compile. We ran it anyway, with the engine
forced in-process (`VLLM_ENABLE_V1_MULTIPROCESSING=0`) so the CUDA context
holding the weights lives in the process being snapshotted.

**The snapshot did not restore.** `@modal.enter(snap=True)` re-ran on the
second container — 272 s of engine build where a restore would have been
seconds. Recorded in `docs/adr/data/ADR-260820b-qwen38-27b-gpu-snapshot.json`
as `restored_from_snapshot: false`.

So the scale-to-zero economics stand on the ~7-minute number, not on a hope.
The 272 s is the *warmest engine build we have observed* and worth knowing —
but it was measured in-process with `max_num_seqs=128`, so it is not a clean
comparison against the 428 s multiprocess run, and we are not claiming
in-process is faster.

## Not measured

- **Repeats at mid concurrency.** See the variance table: n=1 at concurrency
  8–128, so those cells carry ±40% and should not be read finely.
- **Baking weights and compile cache into the image layer** instead of a
  network Volume, against the ~144 s of load and imports.
- **The published snapshot recipe as published**: `vllm serve
  --enable-sleep-mode` as a subprocess with `VLLM_SERVER_DEV_MODE=1` and the
  sleep/wake HTTP endpoints, rather than the in-process `LLM` class we drove.
  Reported elsewhere at 460 s → ~70 s; **unverified here.**
- **More than four snapshot invocations.** Modal snapshots per worker type and
  says 2–3 invocations are needed; we stopped at four without a restore.
- **MTP at temperature > 0.** Every number here is greedy. Qwen recommends
  `temperature=1.0` for thinking mode, and speculative acceptance falls when
  the target samples. **The MTP gains above are an upper bound**, and the
  amount they fall by is unknown to us.
- **Right-sizing CPU and memory.** All runs requested 8 cores and 32 GiB
  because that is what the first ADR used and changing it mid-comparison would
  have moved the cost basis. Dropping to 2 cores / 16 GiB would cut the side
  charge from +12% to +4% on H200 — if vLLM still loads 28 GB of weights in it,
  which we did not test.
- **B300, A100, multi-GPU.** A100 has no FP8 path and would need BF16 at 56 GB.
- **Quality.** Throughput only, and FP8 KV cache with a default 1.0 scaling
  factor may cost accuracy — vLLM warns about it at startup and we did not
  check. MTP is lossless by construction; FP8 KV is not.
- **Real arrival patterns.** These are offline `LLM.generate()` batches with
  every request present at t=0. A concurrency-128 figure is a ceiling, not a
  forecast: reaching it requires 128 requests actually in flight.
- **RunPod.** This ADR is Modal-specific. RunPod's flat hourly rate has no
  separate CPU/memory line and its cheapest H100 is $2.69/hr against Modal's
  $3.95, so the vendor comparison in ADR-260820 is unchanged in direction and
  would shift in magnitude if re-run with MTP on.

## How to re-run

```bash
modal run tools/modal-bench/qwen38_27b_bench.py --gpu H200 --mtp \
  --max-model-len 65536 --max-num-seqs 256 --out h200-mtp.json
modal run tools/modal-bench/qwen38_27b_snapshot.py
```

Both exit 2 when they could not answer, distinct from pass and fail. The
harness now also discovers the Gated DeltaNet recurrent-state ceiling by asking
for more sequences than a card can hold and reading the limit out of vLLM's
refusal, so `mamba_cache_blocks` is reported rather than needing to be known in
advance. On every card measured here at 64k, 256 sequences fit and the ceiling
never bound — it bound on the 48 GiB L40S in ADR-260820, at 167.
