# ADR-260731: T5.2 call-record expand wave 11

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#274 (waves 1–10)
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` after wave 10 (#274 report/stream/credits/rebalance):

| Host | Export |
|------|--------|
| `cloud.plan` | `summary-title`, `dial-ok-title`, `from-to-cap-reason`, `authorized-line`, `address-family-line`, `policy-line`, `relay-score`, `quic-endpoint`, `webrtc-endpoint`, `webtransport-endpoint`, `transport-endpoint`, `relay-endpoint-url` |
| `provision.plan` | `local-bin-path`, `remote-bin-dest`, `label-kv`, `peer-entry`, `join-append`, `bootstrap-append`, `labels-append`, `roles-append`, `plist-replace`, `multiaddr`, `write-plist-shell` |
| `token` residual | `wire-token`, `constant-time-eq`, `scope-allows?` |
| `infer.plan` residual | `choose-strategy-name` |
| `infer.relay` residual | `make-id` |
| `tunnel` residual | `scp-dest` |
| `dash.state` residual | `recent-take-n`, `take-last-start` |
| `fleet.inventory` residual | `selector-wants-name?` |
| `component-authority` residual | `identifier-len-ok?` |

Guest export signatures stay positional scalars; only host projection changes.
After this wave, remaining multi-arg `o` calls are **T5.3 `oracle/record`
eligibility paths** only (`task-eligible?`, schedule `eligible?`).

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 nested kit EDN codec remains W4-gated.
- Zero-arg token/constants stay as `oracle/call`.

## Evidence

- `oracle-call-record-test` + cloud/provision/token/plan/relay/tunnel/dash/fleet/cauth suites green
