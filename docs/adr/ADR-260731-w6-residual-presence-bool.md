# ADR-260731: residual presence / option flags are profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: compiler#451, prior profile-5 bool cutovers, T6.4 mirror-delete complete
- WBS: residual 0/1 i64 param projections (post T6.4 XL)

## Decision

Convert remaining **presence / option-flag parameters** from `:i64` 0/1 to real
`:bool` across pure product shells:

| Param | Export | Module |
|-------|--------|--------|
| `pinned-exists` | `kotoba-bin`, `resolve-local-bin` | config |
| `pinned-wit-exists` | `resolve-wit-dir` | config |
| `has-rc` | `pick-exit` | tunnel |
| `present` | `health-from-present` | dash-state |
| `cache` | `rpc-server-cmd` | infer-engine |
| `present` | `opt-i64-flag`, `opt-str-flag` | infer-engine |
| `shared` | `verdict-name` | infer-moe |

Hosts pass Clojure booleans (`(boolean x)` / `(some? x)`). No more
`(oracle/as-i64 (if x 1 0))` at these call sites.

## Non-claims

- Numeric option values still use `[:option :i64]` / sentinel `-1` where the
  payload is a number-or-absent (e.g. join mem-bytes). Those are not presence
  bools.
- T8.3 production AOT network/secret; W4 recursive values remain open.

## Evidence

- Focused parity + unit + authority green after KIR regen for config, tunnel,
  dash-state, infer-engine, infer-moe.
- No residual `as-i64 (if … 1 0)` on the listed exports.
