# ADR-261007: A Node-hosted overlay RPC, so the distributed CI/CD can run on kbb

- Status: Proposed
- Date: 2026-10-07
- Related: ADR-0001 (Murakumo-native distributed CI/CD); `docs/murakumo-cicd-runbook.md`

## Context

ADR-0001 makes Murakumo the CI/CD authority (coordinator, runners, quorum, CAS, capability-gated
CD) and GitHub Actions no part of it. The coordinator, runners and CD nodes talk over the overlay
RPC, and the only implementation of that RPC is `murakumo.overlay.quic-driver`, which is JVM-only
(kwik). The runbook says so: on kbb "serving or dialling the overlay fails". Since kbb is the
supported host, the distributed CI/CD cannot be started where the code runs.

The RPC is small: a request envelope is one EDN line, the response is one EDN line. Everything that
matters for trust is above it (signed runner and issuer identities, leases, capabilities checked at
the destination, CIDs recomputed by the coordinator); the QUIC driver itself trusts any certificate.
The callers (`ci.worker`, `ci.broker-service`, `cd.*`, `artifact-replication`) reach it through two
functions, `request!` (synchronous) and `serve-rpc!`.

## Decision

1. `murakumo.overlay.rpc` is the one place callers go. It holds the pure parts (the envelope, the
   responder core) and resolves the driver named by `(:transport request)` on first use:
   `:quic` -> `quic-driver` (unchanged), `:tcp` -> `tcp-driver`. A request that names no transport
   is QUIC, as before.
2. `murakumo.overlay.tcp-driver` implements `request!` / `serve-rpc!` on Node: one EDN line over a
   TCP connection. It adds **no authentication of its own**, as the QUIC driver adds none, so it
   binds only to loopback or the Tailnet range 100.64.0.0/10 (whose WireGuard layer authenticates
   and encrypts the link) and refuses any other endpoint. It is bounded: 8 MiB per request line, 30 s
   idle, 64 concurrent connections.
3. `request!` stays synchronous because its callers are. It runs the socket I/O in a short-lived
   `node` child process (request on stdin, result on stdout) and waits. The cost is a process start
   per call (tens of milliseconds); heartbeats and lease RPCs do not care, bulk artifact transfer
   (48 KiB chunks) does, and is the first thing to batch or to make asynchronous if it matters.
4. The config validators (`coordinator`, `runner-daemon`, `cd.node-daemon`) accept `:tcp` as well as
   `:quic`; the CD fleet adapter and artifact replicator take the transport from the target they
   dial and default to `:quic`.

## Not decided here

- Whether a QUIC driver for kbb is still wanted (Node has no stable QUIC). The router makes it a
  drop-in.
- The remaining JVM-only code on the CI/CD path (below).

## Remaining gaps on kbb, measured (not caused by this change: identical before it)

Of the CI/CD, artifact and overlay test namespaces, 33 pass on kbb with and without this change.
Failing on both:

- `ci.artifact-upload`, `artifact-replication`, `ci.worker` (artifact step), `cd.automation`,
  `cd.controller`: the CAS and upload code still use `clojure.java.io` coercions
  (`:as-file ... Coercions`) and `java.util.Arrays`, which kbb does not provide. They need the same
  port to `kotoba.io` / `kotoba.bytes` the rest of the repository already had.
- `overlay.runtime`: a stack overflow in the relay listen-spec derivation.
- The pure overlay namespaces (`overlay.adapter`, `dial`, `relay`, `transport`, `forward`, `cert`,
  `crypto`, ...) and `cd-quic-live` do not load on kbb (JVM sockets, BouncyCastle).
- `ci.github-status` needs the synchronous JVM HTTP client; not needed when GitHub is only an event
  source and status is read from Murakumo.

## Verification

`overlay-tcp-driver-test`: 5 tests / 37 assertions over real sockets (the server in a child
process, because the client blocks): EDN round trip with nested data, a throwing handler answers an
error, an unreadable and an oversized request are refused, a closed port is a stream error, a silent
peer is a timeout and not a hang, the bind policy, and the router. Nothing has yet run a coordinator
and a runner over it: that needs the artifact path above.
