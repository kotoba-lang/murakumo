# ADR-260731: T5.2 native guest record wire — cloud-plan

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through config/connect
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs on `cloud_plan_core` into single guest records:

| Export | Schema | Fields |
|--------|--------|--------|
| `node-region` | `:cloud/region-in` | zone, region-label, region |
| `relay-score` | `:cloud/relay-score` | node-region, relay-region |
| `overlay-id-input` | `:cloud/overlay-id` | overlay-id, cloud-name |
| `node-id-input` | `:cloud/node-id` | overlay-cid, node-name |
| `quic-endpoint`, `webrtc-endpoint`, `webtransport-endpoint` | `:cloud/host-port` | host, port |
| `relay-endpoint-url` | `:cloud/relay-url` | url, node-id |
| `transport-endpoint` | `:cloud/transport` | scheme, host |
| `summary-title` | `:cloud/summary-title` | domain, overlay |
| `dial-ok-title` | `:cloud/dial-ok` | route-name, node |
| `from-to-cap-reason` | `:cloud/from-to-cap` | from, to, capability, reason |
| `authorized-line` | `:cloud/authorized` | from, to, capability |
| `address-family-line` | `:cloud/address-family` | af, nodes, relays |
| `policy-line` | `:cloud/policy` | default, allow-n |
| `starts-with?` | `:cloud/starts-with` | s, prefix |

Host builds `oracle/record` + `call-record` `:raw`.

## Non-claims

- Single-arg residual (titles, flag classifiers, constant tokens) stay scalar.
- Internal multi-arg helpers (`flag-value-after`, digit parsers) stay multi-arg.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `cloud_plan_core` only
- Focused cloud-plan parity + unit + authority green
