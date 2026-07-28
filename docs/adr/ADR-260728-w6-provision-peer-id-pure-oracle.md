# ADR-260728: W6 provision peer-id pure oracle expansion

Status: accepted after deploy pin-path pure (#160)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **peer-id DID/body
pattern fragments**, dual-sourced on `murakumo.provision.plan`:

| export | role |
|---|---|
| `did-key-prefix` | already pure; still SSoT for DID prefix |
| `peer-id-body-prefix` | `12D3` multihash body start |
| `peer-id-body-pattern` | `12D3[A-Za-z0-9]*` for grep -o |
| `peer-id-did-pattern` | `did:key:` + body pattern for grep -ho |
| `did-peer-id` | `did:key:` + peer-id body |

`peer-id-log-command` and `live-link-count-command` recompose those patterns
instead of hard-coded literals. Host `peer-id-from-log` builds its re-find
regex from dual-sourced `did-key-prefix` + `peer-id-body-prefix`; re-find
itself stays host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + parity + authority green

## Related

- ADR-260728-w6-provision-peer-entry-pure-oracle
- ADR-260728-w6-provision-plist-path-pure-oracle
- murakumo#160 deploy pin path pure
