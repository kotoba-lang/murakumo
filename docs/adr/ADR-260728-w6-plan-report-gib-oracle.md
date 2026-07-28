# ADR-260728: W6 pure-planner oracle — plan report GiB + report pad/headers

Status: accepted after engine head-cmd (#71)

## Decision

### `infer_plan_core.kotoba` — plan/report GiB rows

| export | role |
|---|---|
| `bytes-to-gib-milli` / `bytes-to-gib-floor` | bytes → GiB scale |
| `mem-gib-milli` / `usable-gib-milli` / `est-gib-milli` | report row fields |
| `layers-range-str` | `"lo..hi"` for `:layers` |
| `digit-char` / `nat-str` / `i64-str` | string join for ranges |

### `report_core.kotoba` — table pad without printf

| export | role |
|---|---|
| `nodes-header` / `status-header` | exact format header strings |
| `status-down-suffix` | `"down"` field padded to 8 |
| `spaces` / `pad-right` / `field-*` | host supplies remaining pad width |

`string-length` is not admitted in guest ABI; pad width is computed on host
(`max(0, width - (count s))`) and applied purely.

### Still cljc

- `reconcile-lines` multi-join / reach-detail tables
- `command-help` long help text
- n≠3 plan map assembly with node ids

## Evidence

- `test/murakumo/infer_plan_kotoba_parity_test.clj`
- `test/murakumo/report_kotoba_parity_test.clj`

## Related

- murakumo#62 report ops-line extension
- murakumo#69 ok-mark
- inventory Next: report GiB rows
