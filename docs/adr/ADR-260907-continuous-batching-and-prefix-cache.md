# ADR-260907: Continuous batching pays on b70 for the pathology it was meant to fix, and prompt-prefix caching was never off

- Status: accepted
- Date: 2026-09-07

## Context

Two llama.cpp features were believed unused across the fleet: continuous
batching (`--parallel` above 1) and prompt-prefix reuse. Both ship in every
build we run. The premise for looking was that `b70` served `--parallel 1`,
that `xavier` served `--parallel 1`, and that `/slots` on b70 reported
`n_prompt_tokens_cache: 0` against a 13,553-token prompt — a transcript being
re-read from scratch every turn.

One of those two was true. The other was a misreading of a field, and this ADR
exists mostly to stop the next reader making it again.

## What the three heads were actually running

Measured 2026-09-07, by reading the processes rather than the units.

| head | reachable | unit state | what is actually running |
|---|---|---|---|
| b70 | `jun@100.119.10.43` | `active`, supervised | SYCL, `--ctx-size 32768 --parallel 1` |
| gad | `root@100.82.98.110` | `active` | **not the RPC ring** — see below |
| xavier | `xavier@100.87.226.80` | **`inactive`** | CUDA server running unsupervised for 9.1 days |

Two of the three findings here are about the gap between a unit file and a
process, which is the same failure `deploy/llama-server.service` recorded in
its own header a year of ADRs ago:

- **`gad` is not running the RPC ring.** `murakumo-ring.service` still defines
  the 10-participant `--rpc` invocation, but a drop-in
  (`/etc/systemd/system/murakumo-ring.service.d/qwen38.conf`, one of six in
  that directory) blanks both `ExecStartPre=` and `ExecStart=` and substitutes
  a single-node Vulkan `llama-server` on Qwen3.8-27B at `-c 524288
  --parallel 2`. The nine RPC workers still answer on `:50052`; nothing is
  talking to them. **The 7-day decode distribution attributed to "the ring"
  below is therefore a distribution over a mixture of configurations**, and
  should not be read as the ring's performance.
- **`gad` restarted 23 times on 2026-09-07 alone**, four of them within the
  hour this work began. Another agent is actively working on that head. It was
  measured and not touched.
- **`xavier`'s unit is `inactive`** while its server has been up 9.1 days, so
  the process is not under a supervisor and writes no journal. Its timings are
  **UNVERIFIED** — not "unchanged", and not "no load". A `systemctl restart`
  there would start a second process to contend for port 8090 with a live one,
  so it was left alone.

## Prompt-prefix caching was already on, and the zero is an idle-slot artifact

The `n_prompt_tokens_cache: 0` observation is real and is not evidence of a
cold cache. That field reports the *current* task's reuse and reads 0 on an
idle slot. The per-request figure is `timings.cache_n` in the completion
response body.

Measured on b70 with a nonce-prefixed prompt, so request 1 is guaranteed cold:

```
[1 cold ] prompt_n= 12111 cache_n=    0  prompt_ms= 12054.9
[2 warm ] prompt_n=  2053 cache_n=10059  prompt_ms=  2690.2
[3 warm ] prompt_n=  2052 cache_n=10060  prompt_ms=  2662.0
```

10,059 of 12,111 tokens reused; prefill 12.1 s → 2.7 s. That is the same ratio
the router's `demote-busy-endpoints` docstring prices at ~83 s cold against
7–13 s cached. **The feature is working, no flag was added for it, and the
`--cache-reuse` default of 0 was left alone.**

Two things this does *not* establish. It does not explain the ~2,052-token
tail that is re-evaluated on every warm request even though the two prompts
share a longer prefix than that; that gap is suspiciously close to
`--ubatch-size 2048` and was not chased. And it says nothing about whether
production traffic *arrives* in a shape that can hit the cache — a request
rotated to a different pool member keeps none of the conversation, and that is
a router question this ADR did not open.

## Continuous batching on b70: the arithmetic first

`--ctx-size` is the **total** KV budget; each of `--parallel N` slots gets
`ctx/N`. Two slots at an unchanged 32768 is therefore the same KV allocation
as one slot at 32768 — it is not a request for more memory. That mattered:
b70 has **15 GiB of system RAM with ~4 GiB available** and an iGPU carving out
of it. A change that needed more memory was not admissible here at all.

What the halved 16,384-token per-slot window has to survive, from 3,795
completed requests in the 24 h before the change:

| | |
|---|---:|
| prompt tokens p50 / p75 / p90 / p95 / p99 | 941 / 2050 / 2342 / 2570 / 12951 |
| largest prompt observed | 13,289 |
| largest generation observed | 1,198 |
| worst case prompt + generation | 14,487 |
| requests above 16,384 | **0 of 3,795** |

So the second slot truncates nothing this head has actually been asked to do,
and costs no memory. Confirmed after the change: available RAM went *up*
(4 → 6 GiB), server RSS 8.8 → 6.7 GiB, `/props` reports `total_slots 2` with
`n_ctx 16384` each.

`--parallel 4` would give 8,192 per slot and cut the p99 traffic in half. It is
**not** admitted by this measurement. Neither is a larger `--ctx-size`.

## The measurement that decided it, and the two that nearly misled

Three different comparisons were run. They do not agree, and the disagreement
is the interesting part.

**1. Identical-shape burst — 2 simultaneous requests, ~1,155-token prompts,
n_predict=34 (b70's real median generation), 6 trials each:**

| | burst wall, ms (min / median / max) |
|---|---|
| `--parallel 1` | 4304 / **4636** / 4890 |
| `--parallel 2` | 4378 / **5052** / 5238 |

Two slots are ~8% *slower*, with overlapping ranges. Both requests need
prefill at once and split it. This is the case batching helps least.

**2. Sustained decode — solo vs 2 concurrent, 200-token generations,
interleaved 8 rounds to cancel drift:**

```
solo decode tok/s     : n=8 min 31.4 median 32.3 max 33.7
dual aggregate tok/s  : n=8 min 34.2 median 35.5 max 37.3   -> 1.10x
```

Two slots are ~10% *faster*, and won in 8 of 8 rounds.

An earlier run of this same comparison at n=1 returned the opposite verdict,
because the single solo sample landed at the bottom of a range that spans
31–47 tok/s. **The first version of this ADR would have said "batching costs
throughput" on the strength of one sample.** The interleaving and the n are
what changed the answer, not the fleet.

**3. Head-of-line blocking — a short request arriving 0.4 s behind a 400-token
generation, 5 rounds each.** This is the pathology ADR-260819 measured on gad
("all of the variance is queue, against a slot that is idle more than half the
time"), and the one `--parallel` exists to fix:

| | short-request latency, s (min / median / max) | the long generation |
|---|---|---|
| `--parallel 1` | 15.37 / **16.54** / 24.79 | ~12.95 s |
| `--parallel 2` | 4.63 / **5.12** / 5.24 | ~20.2 s |

**3.2× better, and the ranges do not overlap** — the worst case with two slots
(5.24 s) is still three times better than the best case with one (15.37 s).

## Decision

**b70 runs `--parallel 2` at an unchanged `--ctx-size 32768`.** The unit file
in this repo is the change; the header carries the arithmetic so the next
reader does not have to rediscover why the context was not raised alongside it.

The decision rests on comparison 3, not on aggregate throughput. Comparison 1
is a real 8% regression on same-shaped bursts and is accepted as the price:
production traffic is a mixture of short and long requests against a head that
ADR-260819 measured as idle 55% of the time, and in that mixture one long
generation was stalling everything behind it for 16 s. The router at
`api.murakumo.cloud` has been publishing `max_concurrency 2` against a head
that could serve 1; that is now true rather than aspirational.

**The long generation pays for this** — 12.95 s → 20.2 s median. Nothing here
makes the head faster at generating; it makes it stop monopolising itself.

## What was not established

- **No production before/after.** The journal window after the change holds 16
  completed tasks and is contaminated by this session's own concurrent load
  tests. It is reported nowhere in this ADR as a comparison, because it is not
  one. The evidence is the controlled probes.
- **xavier was not changed and cannot be compared.** Its `-c 8192` is smaller
  than b70's own p99 prompt (12,951 tokens), so requests that b70 serves today
  would not fit on it at all — worth knowing before the router treats the two
  as interchangeable. Unsupervised process; needs someone watching.
- **gad was not changed.** It is mid-work by another agent, and its `-c 524288`
  on a *single* node deserves its own look: its 7-day decode median is 10.28
  tok/s against b70's 46.41, and one observed task spent 81 s of prefill to
  produce 67 tokens at 1.81 tok/s.
- **`--cache-reuse` was not tuned**, and the ~2,052-token warm-request tail was
  not explained.

## Reproduction

Numbers above are from 2026-09-07 and are *not* to be quoted as current. Re-run:

```bash
# distributions from a head's own journal (never one sample)
ssh jun@100.119.10.43 'journalctl -u murakumo-b70-llama.service --since "24 hours ago" --no-pager' \
  > /tmp/b70.log
nbb scripts/llama-timing-percentiles.cljs --label b70 < /tmp/b70.log

# does this head overlap concurrent requests?
ssh -N -f -L 18090:127.0.0.1:8090 jun@100.119.10.43
nbb scripts/llama-concurrency-probe.cljs --base http://127.0.0.1:18090 --n 2 --n-predict 96

# what the slot window has to survive
grep -oE 'prompt eval time =[^/]*/ *[0-9]+ tokens' /tmp/b70.log \
  | grep -oE '[0-9]+ tokens' | grep -oE '[0-9]+' | sort -n | uniq -c

# is the prefix cache hitting? read timings.cache_n, NOT /slots
curl -s http://127.0.0.1:18090/props | jq '.default_generation_settings.n_ctx, .total_slots'
```

`llama-timing-percentiles.cljs` excludes samples the clock could not resolve
(zero tokens or zero elapsed ms) and prints how many it dropped beside every
distribution. That filter is not cosmetic: the same degenerate condition —
1 token in 0.00 ms — is reported by gad's build as `1000000.00 tokens per
second` and by b70's as `0.00`, so an unmeasurable sample arrives as the
fastest observation on one head and the slowest on the other. On empty input
the script exits 2 rather than reporting a clean distribution over nothing.

## Verification of record

- `curl -s http://127.0.0.1:8090/props` on b70 → `n_ctx 16384`, `total_slots 2`
- exact-text canary after restart → `CANARY`
- b70 restarted 2026-09-07 02:24:35Z, 02:32:51Z (to 1, to measure), 02:36:11Z
  (to 2, final). Slot confirmed idle before each; `xavier` held a free slot in
  the router pool throughout.
