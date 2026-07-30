# ADR-260731: infer-plan usable-bytes wired is [:option :i64]

- Status: accepted
- Date: 2026-07-31
- Depends: compiler#451, murakumo#258 join option-i64 pattern
- WBS: residual sentinel cleanup after T6.4 / residual presence bools

## Decision

1. **`usable-bytes` and `usable-gib-milli` take `wired` as `[:option :i64]`.**
   None ⇒ no wired-memory cap (use mem−os only). Some(w) ⇒ min(w, mem−os)
   before headroom subtract.
2. **Host** passes `(oracle/option-i64 wired-limit-bytes)` — nil becomes none.
   Drop the `-1` sentinel previously meaning "absent".

## Non-claims

- Other `-1` values in plan (pick indices, bump scores) remain ordinal / no-pick
  codes, not presence sentinels.
- T8.3 production AOT network/secret; W4 recursive values remain open.

## Evidence

- infer-plan parity + infer-test + authority green (93 tests / 1547 assertions)
- KIR regenerated for `infer_plan_core` (deterministic binding-some temps)
