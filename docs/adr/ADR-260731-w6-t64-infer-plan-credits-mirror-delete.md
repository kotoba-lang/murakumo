# ADR-260731: T6.4 remainder — infer plan + credits delete cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after schedule/rebalance/engine #247)
- Depends: #233 preload contract; #226 fleetwide try-oracle JVM

## Decision

1. **`murakumo.infer.plan` and `murakumo.infer.credits` drop all `mirror-*`
   pure reimplementations and dual-source `try-oracle` / `oracle-i64-const` /
   `oracle-ratio-const` fallbacks.** Pure helpers call shipped `:infer-plan` /
   `:infer-credits` KIR on **every** platform via `oracle/require-ready!`.
2. **Host-only remains:**
   - plan: layer partition walk, float quotas, plan maps, strategy :why table
   - credits: float settle folds, transfer/spend, balances, job-cost
3. **Preload guarantee (same as prior waves):** nbb `resources/`,
   `register-kir!` / `set-resource-loader!`, JVM classpath.

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values

## Evidence

- Focused plan/credits unit + parity + authority green
- No `mirror-*` / `try-oracle` remain in plan.cljc or credits.cljc
