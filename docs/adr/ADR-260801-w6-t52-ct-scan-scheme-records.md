# ADR-260801: T5.2 native guest record — ct-scan + URL scheme/host residual

- Status: accepted
- Date: 2026-08-01
- Depends: string-scan + digit-scanner residual (91d98453)
- WBS: T5.2 residual multi-arg pure (token ct-scan + runtime URL scan)

## Decision

Fold remaining multi-arg scan loops into single guest records:

| Module | Export | Schema |
|--------|--------|--------|
| token | `ct-scan` | `:token/ct-scan` (`a`/`b`/`i`/`n`/`acc`) |
| overlay-runtime | `find-scheme-end` | `:runtime/scan` (`s`/`i`/`n`) |
| overlay-runtime | `find-host-end` | `:runtime/scan` (same shape) |

`constant-time-eq` / `scheme-prefix-host` pack via `record-new`. Host paths
still call the single-arg/single-record public entry points.

## Non-claims

- T8.3 nested EDN still W4-gated
- T8.4 L5 / Node sockets remain open
- Other single-arg pure remains scalar (by design)

## Evidence

- KIR regenerated for `token_core` + `overlay_runtime_core`
- Focused token/runtime parity + authority green
