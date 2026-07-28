# ADR-260728: W6 pure-planner oracle — kekkai gate string core

Status: accepted first cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the **string-only** portable core of `murakumo.kekkai.gate` to
`kotoba/kekkai_gate_core.kotoba` and gate it with a KIR parity test against the
cljc oracle:

| function | notes |
|---|---|
| `default-ledger-path` | constant |
| `parse-status-out` | newline strip + empty → `"unknown"` |
| `denial-line-of` | visible denial text |
| `default-kekkai-dir-under` | path under `$HOME` |
| `authorized?` | status predicate (`:i64` 0/1 in guest profile) |

### Explicitly not ported (this slice)

- `partition-nodes` — list/map reduce remains cljc
- `ledger-path` / `kekkai-dir` getenv injection — host shell
- `cli-argv` — process argv vector stays host

## Evidence

- `test/murakumo/kekkai_gate_kotoba_parity_test.clj`
- Offline + parity: 9 tests / 28 assertions green

## Related

- kotoba-lang `lang/w6-murakumo-path-inventory.edn` first-cutover-slice
- Design-system form-A oracle pattern (css `kotoba_parity_test`)
