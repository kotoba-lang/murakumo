# ADR-260728: W6 overlay stream type tokens pure oracle expansion

Status: accepted after config path-suffix tokens pure (#186)

## Decision

Expand `kotoba/overlay_stream_core.kotoba` with residual **stream map type tokens
and initial next-seq**, dual-sourced on `murakumo.overlay.stream`:

| export | role |
|---|---|
| `type-stream` | open-stream `:type` |
| `type-frame` | frame `:type` |
| `type-ack` | ack `:type` |
| `initial-next-seq` | open-stream `:next-seq` (0) |

Host open-stream / frame / ack dual-source `:type` and initial seq. stream-id
hashing + map assembly stay host.

## Evidence

- regenerated `overlay_stream_core.kir.edn`
- stream parity + authority green

## Related

- ADR-260728-w6-overlay-pure-kotoba-oracle
- murakumo#186 config path-suffix tokens pure
