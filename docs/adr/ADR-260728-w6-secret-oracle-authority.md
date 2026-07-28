# ADR-260728: W6 product-shell oracle authority — secret pure path

Status: accepted after infer.engine (#96) product-shell cutover

## Decision

Wire pure name/policy helpers of `murakumo.secret` to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/secret_core.kotoba` | SSoT |
| `resources/murakumo/oracle/secret_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:secret` | load + execute |
| JVM name/env constants, `valid-env-var-name?`, POSIX `valid-path-ref?` | delegate to oracle |

### Still host

- `env-fetch` / `map-fetch` / `fn-fetch` / `kagi-fetch` / `System.getenv`
- resolve* orchestration
- Windows absolute path detection (`File/isAbsolute`)
- cljs host-mirror

## Evidence

- authority + secret_kotoba_parity (+ secret unit tests)

## Related

- inventory Next: bulk catalog gen for remaining cores / Delivery shells
- murakumo#86–#96 product-shell pattern
