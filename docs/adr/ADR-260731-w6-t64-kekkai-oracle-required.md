# ADR-260731: T6.4 remainder — kekkai.gate oracle-required on JVM

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror reduction)
- Depends: T6.2 (#220), T6.4 same-artifact (#221)

## Decision

For `murakumo.kekkai.gate`:

1. **JVM (`:clj`)**: pure helpers **require** the shipped `:kekkai-gate` KIR.
   Missing oracle throws — no host pure reimplementation on the prod path.
2. **cljs/nbb (`:cljs`)**: keep private `mirror-*` only as fail-closed fallback
   when `register-kir!` / resource load is unavailable.
3. Token string constants (`status-authorized`, denial fragments, cli flags)
   are load-time oracle reads on JVM (same artifact as T6.2).

This is the first host ns to drop dual-source pure mirrors on JVM after the
catalog same-artifact proof.

## Non-claims

- Other 30+ host ns still carry `mirror-*` dual-source
- cljs entrypoints still need preload for oracle-first behavior

## Evidence

- gate unit + oracle authority + parity green on JVM
