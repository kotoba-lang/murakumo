# ADR-260728: W6 QUIC cert/key path refs (no PEM-in-env)

- Status: Accepted
- Date: 2026-07-28

## Context

`MURAKUMO_QUIC_CERT` / `MURAKUMO_QUIC_KEY` were read via raw `System/getenv`
in `quic_driver/cert-files`. Audit treated them as secret material and asked
for path refs under host custody instead of PEM bodies in the environment.

## Decision

| name | env | meaning |
|---|---|---|
| `murakumo-quic-cert-path` | `MURAKUMO_QUIC_CERT` | absolute path to cert PEM file |
| `murakumo-quic-key-path` | `MURAKUMO_QUIC_KEY` | absolute path to key PEM file |

`murakumo.secret/valid-path-ref?` rejects relative paths, wildcards, and
inline `-----BEGIN` PEM bodies. Missing/invalid refs fall back to
`cert/ensure-quic-material!` (local kagi-compatible store).

## Consequences

- High-priority secret getenv list no longer includes QUIC env as open.
- Operators export paths under a secured directory, not PEMs.
