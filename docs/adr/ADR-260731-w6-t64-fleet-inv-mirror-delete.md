# ADR-260731: T6.4 remainder — fleet.inventory deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after tunnel #242)
- Depends: #242 tunnel, preload contract (#233)

## Decision

1. **`murakumo.fleet.inventory` drops all `mirror-*` / `try-oracle` /
   `oracle-str-const` / `oracle-i64-const` dual-source pure reimplementations.**
   Pure tokens + resolve-port / health-url / selector / line-has-offline?
   require shipped `:fleet-inventory` KIR via `oracle/require-ready!`.
2. **Host-only remains:** vector filter/map folds over fleet nodes, tailscale
   stdout line split, enrich merge.
3. **Preload guarantee** same as prior T6.4 mirror-delete hosts.
   `ops.cljs` / `task.cljs` already preload `:fleet-inventory`.

## Non-claims

- Shell execution of tailscale/ssh stays in `murakumo.fleet`
- Other dual-source hosts (report/config/plan/driver/runtime/…) still keep
  cljs mirrors
- T8.3 production AOT; W4 recursive values

## Evidence

- fleet-inventory host + kotoba parity green
- No dual-source mirror bodies remain in `src/murakumo/fleet/inventory.cljc`
