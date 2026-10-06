# ADR-261003: Chat inference is routed on live signals, not on a fixed origin

Status: Accepted — 2026-10-03

## Context

`murakumo.infer.schedule` / `rebalance` / `capacity` place **media jobs** and size
**pools**. None of them routes a chat request. The chat path
(`api.murakumo.cloud` → `infer.murakumo.cloud` → one origin on `gad`) is a
verbatim passthrough, so:

- 2026-10-02 ~13:30 JST `gad` stopped answering (not on Tailscale, not on the LAN:
  ARP FAILED from a same-LAN peer). Every chat model returned 502
  `mishima_unreachable` for hours. Ten Mac minis were serving Mishima on `:8094`
  the whole time, and nothing sent traffic to them.
- A second origin was stood up on the operator Mac as a stopgap
  (`~/.murakumo/mishima-local-router`, `127.0.0.1:8095`) and exposed three more
  failure modes that `/health` cannot see:
  1. **Degenerate output with a healthy `/health`.** `asher` answered
     `0000000000…` after a Metal library (`libggml-metal.0.21.0.dylib`,
     `00c411138361`) from a canary experiment was left in place. Restoring the
     fleet-wide build (`652fa132723a`) fixed it; a restart did not.
  2. **Latency that swings with co-resident work.** `judah` shares memory with the
     itonami controller, an inga witness and Postgres; the first request after
     eviction took 23 s, the next 1 s.
  3. **A node that is up on the LAN but absent from the tailnet** (`simeon`,
     locked after a reboot) must simply stop receiving traffic.
- The same router also showed that a single model slot per node (`--parallel 1`,
  32768 context) needs an explicit eligibility rule: the fleet-wide alias is
  131072 tokens, a mini can serve 32768.

## Decision

1. **One pure routing core.** `murakumo.infer.chat-route` owns the decision
   (eligibility, ordering, health verdict, latency smoothing). It is data → data:
   same inputs, same pick; no clock, no I/O. Any host (the operator-Mac router
   today, the gateway Worker later) calls it rather than re-deriving it.
2. **Liveness is a verified answer, not a port.** A node is `alive` only if it
   answers a tiny fixed question correctly (`2+3は? 数字だけで答えて。` → a short
   answer of `5`, shorter than 12 characters; `15`, `50` and `0.5` are rejected)
   with thinking disabled. TCP/HTTP health alone
   never admits a node. A node whose probe fails is out of rotation within one
   probe interval and returns on its own when the probe passes.
3. **Eligibility.** A node is eligible iff it is alive, has a free slot
   (`inflight < slots`), and its context window covers
   `prompt-tokens + max-tokens`. The caller supplies the measured prompt estimate.
   If output size is omitted, the node must declare its actual backend
   `:default-max-tokens`; routing reserves that budget instead of zero. Unknown
   context/budgets and negative or noninteger counts fail closed. An explicit
   request output budget takes precedence over the backend default.
   If no node is eligible the core returns
   `:wait` when a slot could free up, `:too-long` when no node can ever fit the
   request (callers fall through to the next provider, e.g. the `gad` origin),
   and `:none` when nothing is alive.
4. **Ordering is `[manual-low-priority, latency-tier, last-used]`.** Latency tier
   comes from an exponentially weighted moving average (α = 0.3) of the **probe**
   time only — the question is fixed, so the times are comparable across nodes.
   Request time is not used: it is dominated by prompt length (a 9.7 K-token bot
   prompt takes ~190 s on every healthy node). ≤ 5 s tier 0, ≤ 15 s tier 1,
   otherwise tier 2. A node whose
   EWMA is in tier 2 is demoted automatically; an operator may also pin a node
   low-priority. Ties go to the least recently used node so load spreads instead
   of favouring the first name.
5. **Thinking is off by default** on this path (`enable_thinking: false`) unless
   the caller sets it. Without it the Qwen3.8-based Mishima spent 2000 tokens
   (174 s) reasoning about `ping` and returned no content.
6. **Interim host.** The Python router on the operator Mac stays as the transport
   until the gateway embeds the core, and mirrors it exactly (same tiers, same
   probe, same ordering). It is a fallback origin: hermes profiles list it first
   in `fallback_providers`; the primary stays `api.murakumo.cloud`.
7. **Out of scope here** (separate ADRs): moving co-resident services off
   `judah` (the controller keeps secrets in the login Keychain and has no
   documented relocation procedure); distributing hermes cron execution;
   replacing the single public entry with DID-authenticated direct paths.

## Consequences

- Chat survives loss of the `gad` origin, at mini speed (~11.6 tok/s per slot,
  ten slots) and within 32768 tokens. Prompts above that still need `gad`.
- A degenerate node is detected by output, not by status, and nodes are no longer
  tuned by hand.
- Fleet-wide a mini's slot is occupied for the length of a bot turn. Bot system
  prompts are ~9.6 K tokens, so a fallback turn costs ~190 s of prompt
  evaluation at ~51 tok/s. Capacity planning must count fallback bots against the
  slot total.
- The probe consumes the node's only slot briefly; it is skipped while the node
  is serving a request.

## Evidence

- 2026-10-02: `api.murakumo.cloud` 502 on `murakumo/free` and `mishima`;
  `infer.murakumo.cloud` 530; `gad` offline since 04:30 UTC; `ping` from `xavier`
  to `192.168.1.16` unreachable.
- `asher` degenerate output reproduced 4×; fixed by restoring `652fa132723a`;
  reproduced again deliberately to prove the probe removes the node while four
  concurrent requests still succeeded elsewhere.
- Router at 10 concurrent long generations used 10 distinct nodes; at 9 it used
  none of the low-priority node.
- A hermes profile (`aozora`) answered through the router while the primary
  returned 502 (9,659-token prompt, 188.9 s).

## Open items

- `simeon` rebooted and is locked at the macOS login window (needs a local
  password unlock; the password is never stored).
- `gad` needs a physical power check; its MAC is not recorded in `fleet.edn`, so
  Wake-on-LAN cannot be sent.
- The gateway Worker source is not in this repository; moving the core into it is
  the end state of this ADR.
