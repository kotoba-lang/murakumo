# ADR-260731: T6.2 — precompiled KIR is the murakumo product artifact default

- Status: accepted
- Date: 2026-07-31
- WBS: T6.2
- Depends: ADR-260728-w6-product-shell-oracle-authority, ADR-reliability-t63

## Decision

For **murakumo**, T6.2 is closed:

1. **Authoring**: pure product logic lives in `kotoba/*_core.kotoba`.
2. **Ship artifact**: precompiled KIR under `resources/murakumo/oracle/*.kir.edn`
   (32 cores; full catalog in `murakumo.kotoba.oracle`).
3. **Runtime**: `kotoba-kir` / `ir/execute` only — **compiler is not a prod dep**
   (`deps.edn` `:deps` excludes `io.github.kotoba-lang/compiler`; it is `:test`
   extra-deps for parity + `murakumo.kotoba-oracle-gen`).
4. **CI gate**: catalog-wide drift
   `t62-all-product-shell-kir-do-not-drift` recompiles every core and compares
   to the shipped resource after gensym normalization (plus completeness +
   prod-deps assertions).

Regenerate:

```bash
clojure -M:test:gen
```

## Non-claims

- T6.4 (cljs/browser same artifact, drop pure mirrors) remains open.
- T8.3 production AOT network/secret providers remain open.

## Evidence

- `deps.edn`: compiler only under `:aliases :test`
- 32× `*_core.kotoba` ↔ 32× `*.kir.edn`
- Catalog-wide drift + completeness tests in
  `test/murakumo/kotoba_oracle_authority_test.clj`
