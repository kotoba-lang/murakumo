# ADR-260731: T5.2 native guest record wire — token + can-retry

- Status: accepted
- Date: 2026-07-31
- Depends: T5.2 eligibility pilot (murakumo#277), T5.2 call-record waves #261–#276
- WBS: T5.2 remainder — **native guest record parameter wire** (expansion #2)

## Decision

Expand the native guest record wire beyond schedule/task eligibility so selected
multi-arg pure exports take a **single named record** argument. Host builds one
`oracle/record` and projects it via `call-record` with a single `:raw` field.

| Guest export | Record schema | Fields |
|--------------|---------------|--------|
| `token_core/encode-claims-json` | `:token/claims` | sub, scope, iat, exp |
| `token_core/wire-token` | `:token/wire` | payload, sig |
| `token_core/constant-time-eq` | `:token/eq` | a, b |
| `token_core/scope-allows?` | `:token/scope-check` | token-scope, required |
| `task_plan_core/can-retry?` | `:task/retry` | attempt, max-attempts |

## Non-claims / deferred

- **Option-bearing** pure exports stay multi-arg positional `call-record`
  (`expired?`, `parts-present?`, `failed?`) — compiler option-in-record gap;
  do not fold `[:option …]` fields into guest records until that lands.
- Does not convert all remaining multi-arg pure exports (CLI lines, report rows, etc.).
- T8.3 nested EDN still W4-gated.

## Side fix

Authority live-execute fixtures for schedule/task `eligible?` / `task-eligible?`
still used pre-#277 3-arg arity. Updated to single-arg records with free/mem on
the eligibility schema (residual from the pilot PR).

## Evidence

- token/task parity + oracle-call-record + kotoba-oracle-authority focused suites
- KIR regenerated for `token_core` + `task_plan_core` only
- 112 tests / 1818 assertions green
