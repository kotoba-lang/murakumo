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

## What the CI/CD path needed besides the driver (done)

The artifact path used `clojure.java.io` coercions and `java.util.Arrays`, which kbb does not
provide, so five CI/CD test namespaces failed on kbb before this work (`ci.artifact-upload`,
`artifact-replication`, `ci.worker`, `cd.automation`, `cd.controller`). Ported to `kotoba.io`:
`ci.artifact-upload`, `artifact-remote` (staging files: `kio/temp-file`, `kio/write-bytes` with
`:append`, `kio/delete`), `artifact-replication` (chunks by `.slice`, a copy), and
`artifact-store` (the CAS; reads go through `durable-file/regular-file?`, which does not follow
symbolic links, and `read-bytes`, which returns an Int8Array as `kotoba.bytes` does). The five
namespaces now pass.

The router requires the TCP driver statically: on kbb `requiring-resolve` finds only namespaces
that are already loaded, so the call sites (`ci.worker`, `ci.broker-service`, `cd.*`,
`artifact-replication`) now `:require murakumo.overlay.rpc` instead of resolving it by name. QUIC
stays resolved lazily, since loading its namespace anywhere but the JVM fails.

## The lease token

The broker's default lease token was built with `java.security.SecureRandom`. That was not a
blocker, and it needed no replacement to work: the kbb host backs `SecureRandom` with
`crypto.randomFillSync` (nbb `jvm/security.cljs`), a CSPRNG, and the end-to-end test already ran on
the default. It is nonetheless now written directly against `node:crypto` (`randomBytes 32`, 64 hex
characters, `broker-service/token`) so the credential does not depend on an emulated JVM class, and
`ci-broker-token-test` pins its shape, uniqueness and per-position variation; a constant, a
time-based and a 64-bit mutant each fail it.

## Still not done

- `overlay.runtime`: a stack overflow in the relay listen-spec derivation (4 errors, same on `main`).
- The pure overlay namespaces (`adapter`, `dial`, `relay`, `transport`, `forward`, `cert`, `crypto`,
  the witness set) and `cd-quic-live` do not load on kbb (JVM sockets, BouncyCastle). Nothing on the
  CI/CD path needs them once the TCP driver is used.
- `ci.github-status` needs the synchronous JVM HTTP client; not needed when GitHub is only an event
  source and status is read from Murakumo.
- Bulk artifact transfer pays a child-process start per 48 KiB chunk.
- Nothing has run a coordinator and runners on separate machines, nor a pipeline in the sandbox.

## Verification

`overlay-tcp-driver-test`: 5 tests / 37 assertions over real sockets (the server in a child process,
because the client blocks). `ci-tcp-e2e-test`: a runner and a CI broker, both real, in separate
processes over the TCP overlay: lease, start, heartbeat, a 9-chunk artifact upload into the
coordinator CAS (verified by CID), completion, and the run's status; a finished run is not offered
again. Of the CI/CD, artifact and overlay namespaces, 39 now pass on kbb (33 before; the other
23 that do not load are the JVM-only overlay, unchanged).
