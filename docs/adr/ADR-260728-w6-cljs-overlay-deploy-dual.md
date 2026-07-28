# ADR-260728: cljs dual-source for overlay.runtime/driver + deploy.plan

Status: accepted after overlay keyring/peer/stream + schedule cljs dual-source (#130)

## Decision

Expand incremental cljs host rewire:

| host | oracle id | dual-source |
|---|---|---|
| `murakumo.overlay.driver` | `:overlay-driver` | option-name, blank?, endpoint-kind, command-is-dial?, dial-ok-reason |
| `murakumo.overlay.runtime` | `:overlay-runtime` | default ports, adapter-kind, known-adapter?, scheme-prefix-host, port-for-kind |
| `murakumo.deploy.plan` | `:deploy-plan` | constants, manifest-dir, app-manifest-path, publish-selector, localhost-url, command-output |

parse-argv loops / session maps / adapter registry maps / regex extract / argv vectors / node folds stay host.

### Related prior

- #122–#130 cljs dual-source trail

### Still host / incremental

- remaining infer/* (plan/engine/gc/join/moe/rebalance/relay/credits)
- report (JVM-only clj shell)
- network·secret caps contract-only

## Evidence

- overlay driver/runtime + deploy_plan parity/unit green
- nbb smoke: ready? + endpoint-kind/known-adapter?/manifest-dir/localhost-url

## Related

- inventory Next: incremental cljs rewire (infer/* residual)
