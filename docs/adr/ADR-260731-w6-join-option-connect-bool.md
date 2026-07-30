# ADR-260731: join mem option-i64 + connect plane flags as :bool

- Status: accepted
- Date: 2026-07-31
- Depends: compiler#451, murakumo#257 residual presence bools
- WBS: residual sentinel / option-as-bool cleanups after T6.4

## Decision

1. **`infer-join` `clamp-resident` mem parameter is `[:option :i64]`.** Host passes
   `(oracle/option-i64 mem-bytes)` (nil → none). Drop the `-1` sentinel that
   previously meant "absent → use tier max".
2. **`connect` `serves-read?` / `serves-live?` / `serves-plane?` take real `:bool`
   flags** for http?/common? instead of `[:option :i64]` presence tags. Host
   projects set membership as `(boolean …)`.

## Non-claims

- `infer-plan` `usable-bytes` still uses wired `-1` sentinel (follow-up).
- task slots still use negative means absent in some capacity paths (by design
  ordinal / budget codes — separate from presence bools).
- T8.3 production AOT; W4 recursive values remain open.

## Evidence

- join + connect parity/unit + authority green (83 tests / 1500 assertions)
- KIR regenerated for `infer_join_core` + `connect_core` (deterministic
  `__kotoba_binding-some_1` temps)
