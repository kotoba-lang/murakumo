# ADR-260731: T6.4 first slice — same precompiled KIR on cljs load path

- Status: accepted (partial T6.4)
- Date: 2026-07-31
- WBS: T6.4
- Depends: T6.2 (murakumo#220), ADR-260728-w6-cljs-oracle-load

## Decision

T6.4 goal: cljs/browser executes the **same** pure artifacts as JVM product
shells, then drop pure mirrors where possible.

**This slice lands the “same artifact” proof**, not full mirror deletion:

1. Full 32-id catalog loads via classpath / `register-kir!` / `set-resource-loader!`
   (the nbb/cljs injection APIs).
2. Every 0-arity export executes identically through `oracle/call` and
   `ir/execute` on the loaded KIR document.
3. Bundler path: `register-kir!` full-catalog inject works with resource loader
   denied (fail-closed without inject).

Host `mirror-*` helpers **remain** as fail-closed fallback when oracle is not
ready (missing resource, load error, or cljs without preload). Deleting mirrors
is a follow-up once every cljs entrypoint guarantees preload.

## Non-claims

- Browser/webpack packaging of KIR resources (still use `register-kir!`)
- Wholesale mirror deletion
- wasm-webcomponent browser path (separate)

## Evidence

- `test/murakumo/kotoba_oracle_cljs_load_test.clj` — `t64-*` tests
- `kotoba-kir` pin advanced for bool execute boundary (#27)
