# ADR-260731: T5.2 native guest record — classify-run-flags

- Status: accepted
- Date: 2026-07-31
- Depends: option-string-in-record (compiler `98b56bdb` + murakumo product unlock)
- WBS: T5.2 residual multi-option-string pure

## Decision

Fold `classify-run-flags` five `[:option :string]` inputs into one guest record:

| Export | Schema | Fields |
|--------|--------|--------|
| `classify-run-flags` | `:rebalance/run-flags` | images, video, audio, swarm, tokens |

Class codes unchanged: 0 none, 1 text, 2 image, 3 video, 4 audio, 5 postproc.

Host `demand-from-runs` now projects unit/kind presence into the record and
delegates classification to the guest (dual-source; fold structure stays host).

## Non-claims

- Digit-scanner multi-arg internals remain multi-arg.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `infer_rebalance_core`
- Focused rebalance parity + authority green
