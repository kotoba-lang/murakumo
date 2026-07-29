# ADR-260728: W6 provision multiaddr path tokens pure oracle expansion

Status: accepted after identity JWT payload fragments pure (#188)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **multiaddr path
tokens**, dual-sourced on `murakumo.provision.plan`:

| export | role |
|---|---|
| `multiaddr-ip4-prefix` | `/ip4/` |
| `multiaddr-udp-mid` | `/udp/` |
| `multiaddr-quic-suffix` | `/quic-v1` |

`multiaddr` recomposes from these fragments. Host mirror uses the dual-sourced
tokens. SSH/rsync/launchctl effects stay host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision parity + authority green

## Related

- ADR-260728-w6-provision-peerid-plist-pure-oracle
- murakumo#188 identity JWT payload fragments pure
