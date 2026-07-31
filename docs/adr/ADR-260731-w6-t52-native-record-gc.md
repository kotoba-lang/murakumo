# ADR-260731: T5.2 native guest record wire — infer.gc policy math

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#277 eligibility pilot, #278 token+retry, #279 credits+rebalance
- WBS: T5.2 native guest record wire expansion

## Decision

Fold remaining multi-scalar pure inputs on `infer_gc_core` into **single
named guest records** (same pattern as eligibility / token / credits):

| Export | Schema | Fields |
|--------|--------|--------|
| `need-bytes` | `:gc/need` | target, free |
| `free-after` | `:gc/free-after` | free, reclaimed |
| `target-met?` | `:gc/target` | free, reclaimed, target |
| `rank-better?` | `:gc/rank` | atime1, bytes1, atime2, bytes2 |
| `comfy-evictable?` | `:gc/comfy` | atime-days, keep-days |

Host builds `oracle/record` and projects via `call-record` with a single
`:raw` field. Zero-arg defaults (`gib`, `default-*`) stay scalar.

## Non-claims

- Plan fold (filter/sort/reduce over entries) remains host.
- Does not convert other product multi-arg pure exports in this change.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `infer_gc_core` only
- gc parity + unit + oracle-call-record + authority focused suites green
  (101 tests / 1678 assertions)
