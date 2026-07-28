# ADR-260728: W6 overlay_peer health/via tokens pure oracle expansion

Status: accepted after overlay_runtime tokens pure (#176)

## Decision

Harden `kotoba/overlay_peer_core.kotoba` health/via tokens as dual-sourced
SSoT on `murakumo.overlay.peer`, and recompose `choose-via` from those tokens:

| export | role |
|---|---|
| `health-unknown` / `health-seen` / `health-down` | peer health labels |
| `via-direct` / `via-relay` | path via labels |
| `choose-via` | recomposed from tokens (no bare `"down"`/`"direct"`/`"relay"`) |

Host `choose-path` / `candidate-paths` / `peer-record` / `remember` use the
dual-sourced constants. Catalog/remember map folds stay host.

## Evidence

- regenerated `overlay_peer_core.kir.edn`
- overlay-peer parity + authority green

## Related

- ADR-260728-w6-overlay-runtime-tokens-pure-oracle
- murakumo#176 overlay_runtime scheme/adapter tokens pure
