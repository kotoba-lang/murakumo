# ADR-260728: W6 overlay_runtime scheme/adapter tokens pure oracle

Status: accepted after overlay_driver tokens pure (#175)

## Decision

Expand `kotoba/overlay_runtime_core.kotoba` with residual **endpoint scheme,
kind, and adapter name tokens**, dual-sourced on `murakumo.overlay.runtime`:

| export family | role |
|---|---|
| `scheme-quic`…`scheme-webtransport` | URL prefixes |
| `kind-quic`…`kind-other` | endpoint-kind / port-kind strings |
| `adapter-relay`…`adapter-relay-client` | known adapter names |
| `adapter-kind-*` | adapter-kind result strings |
| recomposed `known-adapter?` / `adapter-kind` / `endpoint-kind` / `default-port-for-kind` | from tokens |

Adapter registry map assembly and full URL regex parse stay host.

## Evidence

- regenerated `overlay_runtime_core.kir.edn`
- overlay-runtime parity + authority green

## Related

- ADR-260728-w6-overlay-driver-tokens-pure-oracle
- murakumo#175 overlay_driver scheme/kind/reason tokens pure
