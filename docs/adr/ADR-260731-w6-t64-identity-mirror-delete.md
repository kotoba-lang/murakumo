# ADR-260731: T6.4 remainder — identity deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion wave 3 after kekkai/token/secret)
- Depends: #236 secret mirror delete, #235 token, #233 kekkai + preload contract

## Decision

1. **`murakumo.identity` drops all `mirror-*` / `try-oracle` / `oracle-str-const`
   dual-source pure reimplementations.** Pure seed preimages, JWT templates,
   seps, and string tokens call the shipped `:identity` KIR on **every** platform
   via `oracle/require-ready!`.
2. **Host-only remains:** SHA-256, base32 CIDv1 assembly, b64url encode.
3. **Preload guarantee (same contract as kekkai/token/secret).**

## Non-claims

- Other dual-source hosts still keep cljs `mirror-*` fail-closed fallback
- Live `kotoba did-derive` subprocess shell stays host
- T8.3 production AOT; W4 recursive values

## Evidence

- `identity-test` + `identity-kotoba-parity-test` + focused authority green
- No dual-source mirror bodies remain in `src/murakumo/identity.cljc`
