# ADR-260731: product gate predicates are profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: compiler#451, murakumo#212/#214

## Decision

Convert remaining product-facing pure gates from `:i64` 0/1 to `:bool`:

| Predicate | Module |
|-----------|--------|
| `charge-allow?` | infer-credits |
| `reply-is-value?` | secret |
| `sealed-alg-ok?` / `sealed-fields-present?` | overlay-crypto |
| `known-adapter?` | overlay-runtime |
| `plan-fits-total?` / `span-fits?` | infer-plan |
| `marker-prefix?` | tunnel |
| `selector-is-all?` / `selector-wants-name?` / `line-has-offline?` | fleet-inventory |
| `serves-read?` / `serves-live?` / `serves-plane?` | connect |
| `ack-accepted?` (arg+result `:bool`) | overlay-stream |

Hosts use `oracle/bool->host`. Ranking/pick-code helpers stay `:i64`.

## Evidence

Focused parity + authority suites green after KIR regen.
