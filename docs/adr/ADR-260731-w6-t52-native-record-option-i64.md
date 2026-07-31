# ADR-260731: T5.2 native guest record wire — option-i64 residual

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through dash/fleet/kekkai + report
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs that carry **`[:option :i64]`** into single guest records:

| Module | Export | Schema | Fields |
|--------|--------|--------|--------|
| `token_core` | `claim-exp` | `:token/claim-exp` | now, ttl option |
| `token_core` | `expired?` | `:token/expired` | exp option, now |
| `fleet_inventory_core` | `resolve-port` | `:fleet/ports` | node-port/fleet-port option |
| `provision_plan_core` | `resolve-p2p-port` | `:provision/p2p-ports` | node/fleet option |
| `report_core` | `status-row` | `:report/status-row` | name, health option, wasm, links, p2p |

Host builds `oracle/record` + `call-record` `:raw`.

## Non-claims

- **`[:option :string]` in-record is still a compiler gap** (measured: type mismatch
  expected option-i64 / got option-string). Left positional:
  `parts-present?`, `failed?`, `sealed-fields-present?`, `choose-via`, `action-name`,
  `classify-run-flags`, claim-sub/scope.
- Single-arg option residual (`desired`, `health-label`) stays scalar.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for token/fleet/provision/report only
- Focused parity+call-record 46/543; authority+unit 90/1546 green
