# ADR-260728: W6 host-shell pure — report table rows + tunnel parse digits

Status: accepted after report help/reconcile (#74) + tunnel string core (#54)

## Decision

Close remaining **pure** fragments of ops/host shells (SSH still host-forever):

### `report_core`

| export | role |
|---|---|
| `pad-to` | width-aware left pad (ASCII byte-length) |
| `nodes-row` | full `murakumo nodes` row |
| `status-down-row` / `status-row` | `murakumo status` down/ok rows |

### `tunnel_core`

| export | role |
|---|---|
| `batch-mode-opt` / `strict-host-key-opt` / `control-master-opt` | conn-opts fragments |
| `digit-val?` / `parse-digits` / `trim-ws` | parse-rc digit path after host line-split |

### Still host

- SSH/SCP process spawn, vector `conn-opts` assembly, line-split of stdout
- HMAC/token crypto, secret getenv

## Evidence

- `test/murakumo/report_kotoba_parity_test.clj`
- `test/murakumo/tunnel_kotoba_parity_test.clj`

## Related

- inventory Next: crypto/host shells
- murakumo#54 tunnel+report, #72 pad/header, #74 help/reconcile
