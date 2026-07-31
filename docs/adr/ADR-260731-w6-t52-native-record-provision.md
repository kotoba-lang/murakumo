# ADR-260731: T5.2 native guest record wire — provision-plan

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through cloud-plan
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs on `provision_plan_core` into single guest records:

| Export | Schema | Fields |
|--------|--------|--------|
| `multiaddr` | `:provision/multiaddr` | ip, port |
| `local-bin-path` | `:provision/bin-path` | local-bin, bin |
| `remote-bin-dest` | `:provision/remote-dest` | host, bin |
| `label-kv` | `:provision/label-kv` | k, v |
| `peer-entry` | `:provision/peer-entry` | peer-id, multiaddr |
| `join-append` | `:provision/join` | acc, sep, next |
| `bootstrap-append` | `:provision/bootstrap` | acc, entry |
| `labels-append` | `:provision/labels` | acc, pair |
| `roles-append` | `:provision/roles` | acc, role |
| `plist-replace` | `:provision/plist-replace` | tmpl, ph, val |
| `write-plist-shell` | `:provision/write-plist` | label, body |

Host builds `oracle/record` + `call-record` `:raw`.

## Non-claims

- Single-arg residual (path builders, shell tokens, peer-id-from-log) stay scalar.
- Internal multi-arg scanners (`find-prefix-at`, `take-alnum`) stay multi-arg.
- `resolve-p2p-port` stays positional (option residual).
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `provision_plan_core` only
- Focused provision parity + authority + call-record green
