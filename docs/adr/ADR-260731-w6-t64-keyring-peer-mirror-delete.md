# ADR-260731: T6.4 remainder — keyring + peer delete cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after persist #240)
- Depends: #240 persist, preload contract (#233)

## Decision

1. **`murakumo.overlay.keyring`** drops all `mirror-*` / `try-oracle` /
   `oracle-str-const` / `oracle-i64-const` dual-source pure reimplementations.
   Pure seps, epoch, and preimage strings require shipped `:overlay-keyring`
   KIR via `oracle/require-ready!`. SHA-256 + map assembly stay host.
2. **`murakumo.overlay.peer`** drops the same dual-source pattern. Health/via
   tokens and `choose-via` require shipped `:overlay-peer` KIR. Catalog/remember
   map folds stay host.
3. **Preload guarantee** same as prior T6.4 mirror-delete hosts.

## Non-claims

- Other dual-source hosts (tunnel/report/driver/runtime/plan/…) still keep
  cljs mirrors
- T8.3 production AOT; W4 recursive values

## Evidence

- keyring + peer host tests + kotoba parity green
- No dual-source mirror bodies remain in either ns
