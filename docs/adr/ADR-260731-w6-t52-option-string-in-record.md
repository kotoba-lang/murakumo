# ADR-260731: T5.2 option-string-in-record product unlock

- Status: accepted
- Date: 2026-07-31
- Depends: kotoba-lang/compiler `98b56bdb` (if-some over record-get of `[:option :string]`)
- WBS: T5.2 native guest record wire / option residual

## Decision

Close the **option-string-in-record** product gap after the compiler fix that
resolves `if-some` option type from `(record-get rec :field)` field descriptors.

Fold multi-arg pure that carry `[:option :string]` into single guest records:

| Module | Export | Schema |
|--------|--------|--------|
| `token_core` | `parts-present?` | `:token/parts` |
| `overlay_peer_core` | `choose-via` | `:peer/via` |
| `overlay_crypto_core` | `sealed-fields-present?` | `:crypto/sealed` |
| `reconcile_plan_core` | `action-name` | `:reconcile/action-in` |
| `task_plan_core` | `failed?` | `:task/failed` (also option-i64 exit) |

Murakumo test-only compiler pin → `98b56bdb`. Host uses `oracle/record` +
`call-record` `:raw`.

## Non-claims

- Single-arg option residual (`claim-sub`, `claim-scope`, `desired`, …) stay scalar.
- Digit-scanner multi-arg internals stay multi-arg.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for token/peer/crypto/reconcile/task
- Focused parity + authority 96/1676 green
