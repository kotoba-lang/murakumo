# ADR-260728: W6 overlay_driver scheme/kind/reason tokens pure oracle

Status: accepted after reconcile flag/action tokens pure (#174)

## Decision

Expand `kotoba/overlay_driver_core.kotoba` with residual **endpoint scheme,
kind, command, and dial-reason tokens**, dual-sourced on
`murakumo.overlay.driver`:

| export family | role |
|---|---|
| `scheme-quic`…`scheme-relay` | endpoint URL prefixes |
| `kind-quic`…`kind-unknown` | endpoint-kind result strings |
| `flag-dash-prefix` / `cmd-dial` | option / dial command tokens |
| `reason-ok` / `reason-unknown-command` / `reason-missing-options` | dial-ok-reason |
| recomposed `endpoint-kind` / `option-name` / `command-is-dial?` / `dial-ok-reason` | from tokens |

parse-argv loops and session maps stay host.

## Evidence

- regenerated `overlay_driver_core.kir.edn`
- overlay-driver parity + authority green

## Related

- ADR-260728-w6-overlay-driver-runtime-oracle-authority
- ADR-260728-w6-cloud-cmd-tokens-pure-oracle
- murakumo#174 reconcile flag/action tokens pure
