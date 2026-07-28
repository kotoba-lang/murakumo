# ADR-260728: cljs dual-source for overlay keyring/peer/stream + infer.schedule

Status: accepted after reconcile/component-authority cljs dual-source (#129)

## Decision

Expand incremental cljs host rewire:

| host | oracle id | dual-source |
|---|---|---|
| `murakumo.overlay.keyring` | `:overlay-keyring` | rotation seconds, epoch, key-id/derive preimages |
| `murakumo.overlay.peer` | `:overlay-peer` | health/via constants, `choose-via` |
| `murakumo.overlay.stream` | `:overlay-stream` | window size, advance-seq, ack-accepted? |
| `murakumo.infer.schedule` | `:infer-schedule` | eligible?, score-queue/free, queue-inc-if |

SHA-256 / stream-id maps / catalog folds / pick sort-by stay host.

### Related prior

- #122–#129 cljs dual-source trail

### Still host / incremental

- overlay.runtime / overlay.driver
- remaining infer/* (plan/engine/gc/join/moe/rebalance/relay/credits)
- deploy.plan

## Evidence

- overlay keyring/peer/stream + schedule parity/unit/authority green
- nbb smoke: ready? + epoch/choose-path/advance/eligible

## Related

- inventory Next: incremental cljs rewire (infer/overlay)
