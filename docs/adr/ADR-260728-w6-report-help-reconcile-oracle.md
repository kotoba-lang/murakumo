# ADR-260728: W6 pure-planner oracle — command-help + reconcile-lines pure

Status: accepted after report pad/headers (#72)

## Decision

Extend `report_core.kotoba` with the remaining pure report surfaces:

### command-help
Full CLI help as one pure string (byte-equal to cljc `str/join` over the
canonical line vector).

### reconcile-lines pure fragments

| export | role |
|---|---|
| `reconcile-title` | `reconcile <fleet>  @ <ts>` |
| `reconcile-col-header` | column header line |
| `cid-display` | nil → `—`, else host-truncated cid |
| `action-detail` | place/satisfied/reason detail |
| `field-i64-7` / `nat-len` | `%-7d` without printf |
| `reconcile-app-row` / `reconcile-app-line` | app table row (host pads app/cid/action) |
| `reach-line` / `drift-line` | secondary detail lines |

Host still mapcats apps and joins CSV for targets/running/reach/misplaced.

## Evidence

- `test/murakumo/report_kotoba_parity_test.clj` (4 tests / 71 assertions)

## Related

- murakumo#62 / #72 report string/pad path
- inventory Next: command-help / reconcile-lines
