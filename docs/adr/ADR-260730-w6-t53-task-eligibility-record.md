# ADR: T5.3 (task) — eligibility flags become a record

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after assign packs (#203); mirrors schedule eligibility (#195)

## Context

`task-eligible?` packed five booleans into one i64:

```
1 online | 2 labels-ok | 4 roles-ok | 8 not-excluded | 16 allowlist-ok
```

plus `mem-bytes` and `min-mem`. Max-parameters is 5, so six values forced the
pack. Schedule's analogous flag word was already converted to
`:schedule/eligibility` (murakumo#195); task lagged.

Host product path (`murakumo.task.plan/eligible?`) projects set/map membership
and calls the oracle on JVM — this is a live dual-source boundary, not a
test-only pack.

## Decision

Named schema:

```
:task/eligibility [[:online :i64] [:labels-ok :i64] [:roles-ok :i64]
                   [:not-excluded :i64] [:allowlist-ok :i64]]
```

`task-eligible?` takes that record + `mem-bytes` + `min-mem` (arity 3).
`bit?-safe` / `rem2` deleted. Host builds the record via `oracle/record`.

## Evidence

- task parity: 10 tests / 101 assertions / 0 failures
- authority suite: 66 / 1174 / 0 failures
- KIR regenerated

## Non-goals

- bool-typed fields (still `:i64` 0/1; language opt-in slice)
- cljs host dual-source of eligible? (still mirror on cljs)
