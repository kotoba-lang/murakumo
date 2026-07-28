# ADR-260728: W6 provision bootstrap/labels fold pure oracle expansion

Status: accepted after identity seed/JWT seps pure (#168)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **CSV join fold steps
and template replace step**, dual-sourced on `murakumo.provision.plan`:

| export | role |
|---|---|
| `join-append` | generic empty-first CSV append (`acc + sep + next`) |
| `bootstrap-append` | bootstrap-str reduce step (`peer-join-sep`) |
| `labels-append` | labels-env reduce step (`label-join-sep`) |
| `roles-append` | roles CSV reduce step (`roles-join-sep`) |
| `plist-replace` | one placeholder substitution (`string-replace-all`) |

Host still walks node / label / role collections and chains placeholder
replacements. Peer-id re-find and write-plist heredoc body stay host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + parity + authority green

## Related

- ADR-260728-w6-provision-plist-ph-pure-oracle
- ADR-260728-w6-provision-peer-entry-pure-oracle
- murakumo#168 identity seed/JWT seps pure
