# ADR-260728: W6 product-shell oracle authority — identity + credits pure paths

Status: accepted after fleet.inventory (#102) product-shell cutover

## Decision

Wire pure helpers of two catalog-only cores to kotoba SSoT:

| catalog id | host | SSoT / KIR |
|---|---|---|
| `:identity` | `murakumo.identity` | `identity_core.kotoba` → `identity_core.kir.edn` |
| `:infer-credits` | `murakumo.infer.credits` | `infer_credits_core.kotoba` → `infer_credits_core.kir.edn` |

### identity (JVM)

- seed preimages: `seed-node` / `seed-p2p` / `seed-x25519` / `seed-overlay` (then host SHA-256)
- `did-derive-cmd` → argv, `did-from-output` trim
- op-token templates: `jwt-header-json` / `jwt-payload-json` / `op-token-sig-seg`
- `cid-b-prefix`, `graph-name-fleet`

### credits (JVM)

- `default-per-token`, `head-num`/`head-den`, `protocol-num`/`protocol-den`
- `memory-time-weight` for settle contribution weights
- `charge-allow?` when balance and cost are whole numbers

### Still host

- identity: SHA-256, base32 CID multihash, b64url encode, cljs
- credits: float settle share folds, transfer, balances, ledger-violations, non-integer charge compare

## Evidence

- authority + identity/credits kotoba parity (+ unit tests)

## Related

- murakumo#86–#102 product-shell pattern
