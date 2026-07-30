# ADR-260731: residual char/eq gates return profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#217 (join/gc/action bool), compiler#451

## Decision

Finish pure-bool surface on remaining non-ranking helpers:

1. **digit-val?** → `:bool` in tunnel / reconcile / deploy / dash; parse loops use
   `(if (digit-val? c) …)`.
2. **alnum-char?** → `:bool` in provision (`take-alnum` uses `(if (alnum-char? c) …)`).
3. **constant-time-eq** → `:bool` (token); host uses `oracle/bool->host`.
   `ct-scan` stays `:i64` mismatch accumulator.
4. **weight-pos?** → `:bool` (internal to plan + rebalance); call sites project
   `(if (weight-pos? w) 1 0)` when feeding arithmetic / ok-for flags.
5. **move-needed** → `:bool` (rebalance placement diff).

## Evidence

- KIR regenerated for affected cores
- Focused parity + unit green (see commit message)

## Still :i64 by design (ranking / pick codes)

`better-score?`, `better-bump?`, `better-fill?`, `better-mem?`, `better-target?`,
`rank-better?`, `challenger-wins?`, `before-pool?` — comparison pick codes, not
pure booleans for product hosts.
