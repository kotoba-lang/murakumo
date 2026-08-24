# ADR-260819 — One slot is deliberate, and `infer.edn` said otherwise

**Status:** superseded by the 2026-08-24 qualification below · **Date:** 2026-08-19

## What happened

Half of `cloud-itonami-app`'s resident ticks were exceeding its 120-second
request limit. Tracing that back reached this repo, and the first conclusion
drawn here was wrong in a way worth recording: `infer.edn` said
`:infer/parallel 2` with a comment describing a live qualification, the head
was running `--parallel 1`, and that looked exactly like a deployment which had
drifted from its config.

It had not. `deploy/llama-server.service` withdrew the two-slot measurement on
2026-08-15, four days earlier:

> Production deliberately starts at one 32768-token slot. Dense Qwen3.8 has a
> different KV/memory profile from the retired Qwen3.6 MoE, so the old 524288
> total-context measurement must not be carried forward as if it applied.
> Raise this only after a long-context memory and latency qualification run.

`infer.edn` was the stale document. It had gone on asserting a retired
measurement, confidently, in the file a reader opens first — and the reader it
misled came within one command of restarting the production head to "fix" a
setting that was correct.

## Decision

`:infer/parallel` is 1 and `:infer/ctx` is 262144, which is what the head runs.
Both keys are consumed by `murakumo.infer` when it builds the llama-server
command, so a future start through this repo now matches the deliberate
production choice rather than the withdrawn one. Nothing was restarted.

The three values are left visible rather than reconciled by guesswork:

| source | ctx | parallel |
|---|---|---|
| `infer.edn` before today | 524288 | 2 |
| `deploy/llama-server.service` | 32768 | 1 |
| **the head, measured 2026-08-19** | **262144** | **1** |

The service unit's own header already says why this keeps happening — "Config
that lives only on a box is config nobody can review" — and its `Verify:` line
is the answer:

```
curl -s https://infer.murakumo.cloud/props | jq '.default_generation_settings.n_ctx, .total_slots'
```

## What one slot costs

Recorded so that the qualification run, when it happens, has a number to beat.

Ten identical 16-token requests through the public endpoint:

| | |
|---|---|
| server compute | **1.35–1.68s**, every time |
| wall clock | **2.7s – 23.0s** |
| queue (wall − compute) | **1.3s – 21.6s** |
| slot occupancy, 60 samples over 298s | **45% busy** |

All of the variance is queue, against a slot that is idle more than half the
time. That is head-of-line blocking rather than saturation: one 24-second
generation stalls every short request behind it, and nothing overlaps because
there is nothing to overlap with.

Demand was measured too, because the obvious culprit was assumed rather than
checked. The Hermes bot profiles — 23 of them, all running — made **17 API
calls in six hours**, 8 of those to murakumo. `cloud-itonami-app` made about
**1 request a minute**. Every busy sample carried `temperature 0.2`, which is
that application's signature and nobody else's. The fleet of bots that looked
like the competitor for this slot is not one.

## Not decided here

Whether two slots would help. It has never been qualified on dense Qwen3.8, the
2026-08-15 note is explicit that the MoE numbers do not carry over, and four
slots failed an earlier qualification outright — the RPC ring admitted every
request and produced no first token within 120 seconds.

The head was not restarted to find out. `systemctl is-active llama-server`
returns inactive, so the running process is not under a supervisor that would
bring it back; the VRAM figures in this file do not reconcile with the 34.5 GiB
of 48 observed in use; and the process was started deliberately at 10:19 the
same morning. A qualification run is the way to answer this, with someone
watching.

## 2026-08-24 qualification: two slots accepted

The missing qualification was performed against the live dense Qwen3.8 27B
Vulkan service, using the guarded model-switch command that restores the prior
drop-in if real generation does not recover. Production now runs total context
524288 with `--parallel 2`; `/props` reports two slots with `n_ctx=262144` each,
so increasing concurrency did not reduce the maximum request context.

Observed before and after the switch:

| evidence | one slot | two slots |
|---|---:|---:|
| single-request decode | 13.12 tok/s | 12.82 tok/s |
| simultaneous request A | queued behind the only slot | HTTP 200 in 4.993s |
| simultaneous request B | queued behind the only slot | HTTP 200 in 3.559s |
| `/ready` request capacity | busy 1 / available 0 during load | idle 0 / available 2 after load |

Both concurrent responses returned their exact requested canary text. The
public model registry was updated to `parallel: 2`, `context: 262144`, with a
fresh verification timestamp. This supersedes the one-slot operational choice;
the earlier evidence remains here because it explains why the change required
measurement rather than a configuration edit by assumption. Four slots remain
unqualified and are not admitted by this decision.
