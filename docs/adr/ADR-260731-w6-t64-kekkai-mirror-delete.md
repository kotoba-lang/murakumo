# ADR-260731: T6.4 remainder — kekkai.gate deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion pilot)
- Depends: #223 kekkai oracle-required JVM, #221 same-artifact, oracle `require-ready!`/`preload-catalog!`

## Decision

1. **`murakumo.kekkai.gate` drops all `#?(:cljs … mirror-*)` pure reimplementations.**
   Pure helpers call the shipped `:kekkai-gate` KIR on **every** platform.
2. **`murakumo.kotoba.oracle` exposes the preload contract:**
   - `require-ready!` — throw if oracle missing (product shells after mirror delete)
   - `preload!` / `preload-catalog!` — entrypoint one-shot cache fill
3. **Preload guarantee for cljs/nbb:**
   - nbb from repo root: `resources/` via `node-resource-slurp` (existing default)
   - bundler/browser: `register-kir!` or `set-resource-loader!` before product shells
   - CLI entrypoints that need pure helpers may call `preload-catalog!` once at start

## Non-claims

- Other ~30 dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- T8.3 production AOT network/secret; W4 recursive values
- Browser packaging of KIR resources (still inject via register-kir!)

## Evidence

- `kekkai-gate-test` + `kekkai-gate-kotoba-parity-test` + focused oracle authority green
- No `#?(:cljs` mirror bodies remain in `src/murakumo/kekkai/gate.cljc`
