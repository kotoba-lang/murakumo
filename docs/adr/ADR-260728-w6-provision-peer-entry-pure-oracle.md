# ADR-260728: W6 provision peer-entry pure oracle expansion

Status: accepted after dash probe-command pure (#157)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **bootstrap peer
list fragments**, dual-sourced on `murakumo.provision.plan`:

| export | role |
|---|---|
| `peer-at-sep` | `@` between peer-id and multiaddr |
| `peer-join-sep` | `,` between bootstrap peers |
| `peer-entry` | `peer-id@multiaddr` |
| `did-key-prefix` | `did:key:` (PeerId DID prefix) |

`bootstrap-str` host fold uses `peer-entry` + `peer-join-sep` + existing
`multiaddr` / `node-p2p-port`. Peer map fold and peer-id regex stay host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + parity + authority green

## Related

- ADR-260728-w6-provision-argv-pure-oracle
- murakumo#157 dash probe-command pure
