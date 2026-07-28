# ADR-260728: Product Value ABI v1 — overlay sealed fields + reconcile optionals

Status: accepted after fleet/provision PVA (#114)

## Decision

Expand Product Value ABI v1 beyond token/ports to presence gates that used
`has-*` sentinels:

| core | export | before | after |
|---|---|---|---|
| `overlay_crypto_core` | `sealed-fields-present?` | `has-alg`/`has-nonce`/`has-ct` i64 | `[:option :string]` ×3 + `if-some` |
| `reconcile_plan_core` | `desired` | `has-replicas` + filler | `[:option :i64]` + `if-some` (default 1) |
| `reconcile_plan_core` | `action-name` | `has-cid` + unused `has-misplaced` | `[:option :string]` cid; drop unused flag |

Host bridges via `murakumo.kotoba.oracle/option-string` and `option-i64`.

### Still host / unchanged

- AES-GCM seal/open, SecureRandom nonce, SHA-256 key material
- reconcile eligible/observed set algebra, variable pick-targets sort
- schedule/join bit-packed eligibility flags (later PVA / arity slice)
- report `has-health` / connect plane flags (later PVA slices)

## Evidence

- overlay_crypto + reconcile parity + authority suite
- regenerated `overlay_crypto_core.kir.edn` + `reconcile_plan_core.kir.edn`

## Related

- murakumo#112 Product Value ABI v1 token
- murakumo#114 PVA fleet + provision ports
- inventory Next: PVA expand (schedule flags, overlay sealed fields, …)
