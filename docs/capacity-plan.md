# Capacity plan — how the fleet decides residency, slot geometry and cadence

**Decisions are computed from measurements, not tuned.** The chain is:

```
resources/murakumo/capacity-observations.edn   what was measured, with :how and :at
        │  murakumo.infer.capacity               pure data → data (tests pin today's numbers)
        ▼
resources/murakumo/capacity-plan.edn           GENERATED; the plan, with the objective
        │  scripts/capacity-plan.cljk            print / --write / --publish
        ▼
POST https://api.murakumo.cloud/infer/placement   the fleet's record (:placement/version 2)
```

What the planner answers, and from what:

| question | inputs | output |
|---|---|---|
| which models stay loaded on a shared-memory node | node memory − headroom, model footprints (measured resident bytes), requests/day per model, `:exclusive?`, `:model/process?` + the one policy constant `restart-floor-calls` | `:resident` / `:on-demand` / `:preempting` / `:refused` with the floor arithmetic |
| how many text slots, how large a request, what output ceiling | head ctx-total, overhead, tok/s, per-call prompt & completion quantiles, the gateway's **deployed** admission estimator and measured bytes/token | `max-tokens` = 2^k ≥ 1.5·p99 completion; geometry scored by served calls/day; **ties keep the current geometry**; the value of fixing the estimator in calls/day |
| how often each Bot class may run | resident max-active, run p50, roles per class, recent outcome mix | one scale factor k = requested/capacity over a fixed priority order (probe 15 / ops 60 / strategy 360), rounded to 5 min |

Run it:

```sh
L=<superproject>/scripts/cljk-classpath.cljk
MCP=$(nbb $L "src:<superproject>/orgs/kotoba-lang/text/src")
nbb --classpath "$MCP" scripts/capacity-plan.cljk            # summary + EDN
nbb --classpath "$MCP" scripts/capacity-plan.cljk --write    # resources/murakumo/capacity-plan.edn
nbb --classpath "$MCP" scripts/capacity-plan.cljk --publish  # + POST /infer/placement
```

## 2026-09-11 — the plan that came out, and what it changed

Measured: b70 (two 16,384-token slots, 53 tok/s) admitted `bytes/2 + max_tokens ≤ 15360`
while bot requests are ~8.5 bytes/token; the resident asked `max_tokens 16384` although
no completion in 375 runs exceeded 3,014; gad held three ComfyUI instances (38 GB in one)
and its text ring had been stopped since 09-10 after 14 crash loops on a 524k context.

Plan: `max_tokens 4096`; b70 geometry unchanged; ring resident on gad with ctx 65,536
(2 × 32,768, derived from prompt p99 13,608 + 4,096 + 1,024) and q8 KV — 18.1 GB measured;
ComfyUI bounded with `--reserve-vram 24` (38 → 8 GB); image checkpoints take the APU
class so they serialise with video; MiniMax-H3 preempts the ring (5/day × ~38 min);
cadence ×5.7 (85 / 340 / 2035 min). Served text calls/day: 1,403 on b70 + 983 on the
ring of 4,853 asked; **fixing the gateway estimator is worth +3,377/day** and is the next
change (cloud-murakumo-api `readiness/b70-request-eligible?`, blocked today by the
`.cljk` rename until its build has a loader).

Every number above is in the corpus with its provenance; if you disagree with the plan,
dispute a measurement or the one policy constant, then re-run.
