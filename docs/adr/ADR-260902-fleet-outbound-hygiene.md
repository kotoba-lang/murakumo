# ADR-260902 — Fleet outbound hygiene: one node must not be able to starve itself

> Status: accepted · 2026-09-02 · owner question「構造的な問題? こういった burst などが
> 発生しないようにする全体アーキテクチャなどを検討して整えて」

## What happened (measured, 2026-09-02)

| node | symptom | cause |
|---|---|---|
| benjamin | `kotoba-server` up 60 days, libp2p peered (18 links), `/health` unreachable since 2026-07-06 | 32,479 TIME_WAIT sockets in a 32,768-port ephemeral range; every `connect()` from the node failed `EADDRNOTAVAIL` |
| simeon | `kotoba-server` restarted every 2 min for hours (`watchdog.log`: "health failed twice — killing") | same exhaustion (16,277 / 16,384); the watchdog's *own* probe failed, so it killed a healthy server |

The TIME_WAIT set was byte-identical ten seconds apart and did not shrink after
the producer was stopped for 60 s: the kernel had stopped reaping TIME_WAIT
(both nodes 60 days of uptime). The producer was `com.murakumo.edge-join`
(`infer-join.cljs` → `murakumo.infer.poll-worker`): its poll loop printed
`[join] poll error: TypeError: fetch failed` and retried on its fixed 5 s
cadence, so the range never had a chance to drain. `sysctl -w
net.inet.ip.portrange.first=16384` restored `/health` to 200 on both nodes
within one second — the server had been fine throughout.

## Why this is structural, not an incident

A fleet node today is a *collection of independent daemons* — `edge-join`,
`kotoba-mesh` + its watchdog, `rpc-worker`, `desired-agent`, `hajime-tick`,
`comfyui`, `edge-server` — each with its own HTTP client, its own polling
cadence, and its own idea of "retry". Nothing on the node knows the node's
total outbound budget, nothing distinguishes "the gateway is down" from "I
cannot open a socket", and the one component that acts on health (the
watchdog) measured its own client side and drew a conclusion about the server.

Three properties made the failure self-sustaining:

1. **No backoff.** A loop that fails at cadence *c* keeps the range full at
   rate 1/*c* forever. Backoff is the only thing that lets a saturated
   resource drain.
2. **Wrong observer.** The watchdog's probe shares the fate of the thing it
   is diagnosing. A probe that cannot tell *its own* failure from the
   target's will kill healthy targets exactly when the node is in trouble.
3. **No pressure signal.** `ops status` showed HEALTH=down and nothing else,
   so the operator's next move ("restart kotoba-server") was the watchdog's
   wrong move at human speed.

## Decision

Per-node outbound hygiene is a contract every resident daemon on a murakumo
node follows. Its four rules, and where each now lives:

| rule | where |
|---|---|
| **Back off, bounded, with jitter.** A poller that fails waits 2× longer each time up to 5 min, resets on success, and jitters so the fleet does not re-synchronise on the gateway. | `murakumo.infer.backoff` (pure `.cljc`, JVM-tested) used by `poll-worker`'s poll and heartbeat loops |
| **Classify before retrying.** `EADDRNOTAVAIL` / `EMFILE` / `ENOBUFS` mean *this host* is starved; the loop waits the whole ceiling and says so in the log. Refused / DNS / timeout mean the *gateway*; back off geometrically. | `backoff/classify`, `poll-worker/schedule-after-failure!` |
| **A watchdog may only act on the failure it can prove.** Before a strike it asks `nc` for a plain TCP connect: `EADDRNOTAVAIL` → log "this host is out of ports", no strike; refused → KeepAlive's job; connect OK + HTTP dead twice → the 2026-07-02 wedge, kill. The log line names which case it saw. | `deploy/com.murakumo.kotoba-mesh-watchdog.plist.tmpl` |
| **Headroom is provisioned, and pressure is visible.** A root LaunchDaemon sets `net.inet.ip.portrange.first=16384` (49,152 ports) at boot; `ops status` gains a PORTS column (`TIME_WAIT/range %`) and prints `PORT-EXHAUSTED` at ≥90% so "health=down" is read as the node's client side. | `deploy/com.murakumo.sysctl-baseline.plist.tmpl`, `provision/plan.cljc`, `core.clj` provision, `ops.cljs` |

Headroom is not the fix — it is the margin that keeps one daemon's fault from
becoming the node's. The fix is rule 1; rule 2 makes rule 1 honest; rule 3
stops the fault from being amplified; rule 4 lets a person see it.

## What this ADR does not do (the next architectural step)

- **One node agent, one outbound channel.** The durable answer to "N daemons
  × N HTTP clients" is a single per-node agent that owns the connection pool
  (HTTP keep-alive to `api.murakumo.cloud`) and multiplexes the pollers over
  it, with the desired-state agent's pull model and KSE push replacing
  per-daemon polling. That is `murakumo.task.plan` / `murakumo.fleet.inventory`
  territory (root ADR-2608111721 already puts placement there) and is not
  attempted in this change; this ADR makes every existing daemon survivable
  until then.
- **Reboot policy for stuck TIME_WAIT.** The kernel not reaping TIME_WAIT
  after ~60 days is a macOS condition we can observe (PORTS column) but not
  clear from userland. A node whose TIME_WAIT count does not fall after its
  producers are stopped needs a reboot; that is an operator decision the
  column now makes measurable. Not automated here.
- The `sysctl-baseline` label is a host literal in `provision/plan.cljc`;
  the sibling labels live in `kotoba/provision_plan_core.kotoba` under the
  parity test. Moving it there is a constant relocation, recorded as
  follow-up.

## Evidence trail

- `ssh benjamin netstat -an -p tcp | grep -c TIME_WAIT` → 32479; range
  32768–65535; `nc -zv 127.0.0.1 8077` → "Can't assign requested address";
  `lsof` showed the listener present the whole time.
- `ssh simeon ~/.murakumo/watchdog.log` → a kill every 120 s from 05:26Z.
- `com.murakumo.edge-join` bootout on both nodes → TIME_WAIT unchanged after
  60 s (kernel not reaping).
- `sysctl -w net.inet.ip.portrange.first=16384` → `/health` 200 on both.
- Root record: com-junkawasaki/root ADR-2609021200 (cloud-agent landing), this
  file for the fleet contract.
