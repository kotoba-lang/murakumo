# ADR-260728: W6 product-shell oracle authority — identity + infer.credits

Status: accepted after fleet.inventory (#102)

## Decision

Host-wire two catalog ids:

| catalog | host | pure delegates |
|---|---|---|
| `:identity` | `murakumo.identity` | seed preimages, did-from-output trim, JWT templates, cid-b-prefix |
| `:infer-credits` | `murakumo.infer.credits` | default-per-token, head/protocol frac nums, memory-time-weight |

### Still host

- SHA-256 / base32 / base64url crypto
- float share folds, job-cost multi-unit throws, ledger event assembly
- cljs host-mirrors

## Evidence

- authority + identity/credits parity + unit tests

## Related

- murakumo#99 bulk catalog; incremental host wiring trail
