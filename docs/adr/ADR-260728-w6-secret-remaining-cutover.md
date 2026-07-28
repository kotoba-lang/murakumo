# ADR-260728: W6 remaining secret getenv cutover

- Status: Accepted
- Date: 2026-07-28

## Context

First ops cutover (murakumo#48) wired `MURAKUMO_TOKEN_SECRET` through
`murakumo.secret`. Audit (`w6-secret-getenv-audit`) listed remaining
**secret** sites: service token, metrics token, overlay auth-key env.

## Decision

| secret name | env | consumers |
|---|---|---|
| `murakumo-token` | `MURAKUMO_TOKEN_SECRET` | `cmd-token` (already) |
| `murakumo-service-token` | `MURAKUMO_SERVICE_TOKEN` | `infer.relay-server` |
| `murakumo-metrics-token` | `MURAKUMO_METRICS_TOKEN` | `infer.media` model-map push |

Overlay auth key: config declares env **name** (`:overlay/auth-key-env`);
`murakumo.secret/resolve-exact-env` reads that single var (validated, no
wildcard / dump).

## Non-goals

- Config URLs/paths (`MURAKUMO_CLOUD`, bins, ckpt) stay exact getenv.
- QUIC cert/key path material — later path-ref under scoped-fs.
- Live kagi wire — optional `fn-fetch` inject remains host-side.

## Consequences

High-priority secret getenv call-sites on the audit list are closed for
the named tokens + overlay auth-key path.
