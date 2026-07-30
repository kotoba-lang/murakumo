# ADR-260731: connect plane flags + join clamp-resident drop option-i64 / -1 sentinels

- Status: accepted
- Date: 2026-07-31
- Depends: compiler#451, murakumo#257 residual presence bool
- WBS: residual product-shell option / sentinel cutover (post T6.4 + presence)

## Decision

1. **`connect_core` `serves-read?` / `serves-live?` / `serves-plane?`** take real
   `:bool` for `http?` / `common?` instead of `[:option :i64]` presence
   (`some(1)` / `none`). Host projects transport membership as
   `(boolean (some …))` / `(boolean (seq (intersection …)))`.
2. **`infer_join_core` `clamp-resident`** takes `mem [:option :i64]` instead of
   the `-1` absent sentinel. Host uses `(oracle/option-i64 mem-bytes)`.
3. Shipped KIR regenerated for `connect_core` + `infer_join_core`.

## Non-claims

- Other `[:option :i64]` payloads that encode real numbers-or-absent stay
  (token exp, inventory ports, task exit, …).
- T8.3 production AOT network/secret; W4 recursive values remain open.

## Evidence

- connect / join parity + unit + reconcile + authority green
- 99 tests / 1545 assertions (focused suite)
