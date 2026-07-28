# ADR-260728: Product Value ABI v1 — token pure oracle without sentinels

## Decision
Rewrite `kotoba/token_core.kotoba` to use Product Value ABI v1:

- optional fields: `[:option :string]` / `[:option :i64]` + `if-some`
- decimal: `string-from-i64` (no hand `nat-str`)
- length: `string-length` alias of UTF-8 byte length

Host bridge: `murakumo.kotoba.oracle/option-string`, `option-i64`.
JVM `murakumo.token` pure helpers pass options; HMAC/b64 stay host.

## Depends
- kotoba-lang/compiler Product Value ABI v1 (typed if-some)
- kotoba-kir string-length / string-from-i64

## Evidence
token parity + authority + token unit tests green with regenerated
`resources/murakumo/oracle/token_core.kir.edn`.
