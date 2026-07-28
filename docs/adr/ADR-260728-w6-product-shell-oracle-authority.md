# ADR-260728: W6 product-shell oracle authority (first vertical)

Status: accepted first product-shell dual-source cutover  
Date: 2026-07-28

## Context

W6 landed many `kotoba/*_core.kotoba` pure planners gated by KIR parity tests
(`compiler/compile-source` + `kotoba.kir/execute`). Product `*.cljc` still
reimplemented the same pure functions. Maturity goal: cut the product shell so
**kotoba is authority** (or dual-source with kotoba as SSoT).

Full cutover of every pure planner into a guest runtime is blocked by:

- compiler remains **test-only** (heavy; not a production dep)
- no kbb production path that loads `.kotoba` in-process for the control plane
- cljs/nbb shells must not hard-require the JVM KIR resource loader yet
- list/map reduce and host I/O stay on the host

## Decision (Option A — thinnest real wiring)

Ship **one** high-traffic pure path on dual-source authority:

| layer | role |
|---|---|
| `kotoba/kekkai_gate_core.kotoba` | SSoT pure definitions |
| `resources/murakumo/oracle/kekkai_gate_core.kir.edn` | precompiled KIR product artifact |
| `murakumo.kotoba.oracle` | load + `ir/execute` (needs `kotoba-kir` only) |
| `murakumo.kekkai.gate` (JVM) | public API **delegates** to oracle |
| cljs host-mirror fns | fallback + semantic documentation |

### Existing compile path (not a new runtime)

Parity tests already compile with `kotoba.compiler.core/compile-source` target
`:wasm32-kotoba-v1` and execute via `kotoba.kir`. Artifact generation
(`murakumo.kotoba-oracle-gen`) is the **same** path, writing the `:kir` map as
EDN. Production never depends on the compiler — only on `kotoba-kir` + the
checked-in resource.

### Regenerating artifacts

```bash
clojure -M:test -m murakumo.kotoba-oracle-gen
```

CI drift test `murakumo.kotoba-oracle-authority-test` fails if the resource
differs from a live compile of the `.kotoba` source.

### Explicitly not done

- No new app DSL
- No ambient getenv
- No legacy `kotoba wasm emit` language ceiling
- No production compiler dependency
- No nbb/cljs oracle resource load in this slice
- No cutover of remaining `*_core.kotoba` planners (token, report, infer, …)

## Evidence

- `src/murakumo/kotoba/oracle.cljc`
- `src/murakumo/kekkai/gate.cljc` (JVM oracle delegation)
- `resources/murakumo/oracle/kekkai_gate_core.kir.edn`
- `test/murakumo/kotoba_oracle_authority_test.clj`
- `test/murakumo/kotoba_oracle_gen.clj`
- Existing parity: `test/murakumo/kekkai_gate_kotoba_parity_test.clj`

## Consequences

- First product call path (`parse-status`, `denial-line`, `authorized?` via
  partition, ledger/dir defaults) answers from kotoba KIR, not a hand mirror.
- Pattern is copyable: add catalog entry + regenerate + wire one public API.
- Full fleet of pure planners still dual-implemented until each is wired the
  same way (or a bulk generate step lands).

## Blockers for full cutover

| blocker | next step |
|---|---|
| Compiler test-only | Keep precompile-to-resource; do not pull compiler into prod |
| Many cores still cljc-mirrored | Repeat catalog+wire per high-traffic pure helper |
| cljs/nbb classpath | Add resource paths + kotoba-kir to nbb only if a cljs shell needs oracle |
| Map/list folds beyond scalar guest ABI | Continue host projection + guest pure step pattern from rebalance oracles |
| kbb in-process `.kotoba` load | Future: optional live compile when kbb ships; resource path remains default |

## Related

- `docs/adr/ADR-260728-w6-kekkai-gate-kotoba-oracle.md` (first parity slice)
- kotoba-lang `lang/w6-murakumo-path-inventory.edn` pure-planners-v1
