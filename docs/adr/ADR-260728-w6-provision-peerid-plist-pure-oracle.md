# ADR-260728: W6 provision peer-id extract + write-plist shell pure oracle

Status: accepted after provision bootstrap fold pure (#169)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **peer-id log extract**
and **write-plist shell assembly**, dual-sourced on `murakumo.provision.plan`:

| export | role |
|---|---|
| `alnum-char?` | ASCII `[A-Za-z0-9]` single-char test |
| `find-prefix-at` | first index of prefix (or -1) |
| `take-alnum` | consume `[A-Za-z0-9]*` from offset |
| `peer-id-from-log` | scan `did:key:12D3…` → PeerId body (or `""`) |
| `write-plist-shell` | `tee-plist-prefix` + body + `plist-heredoc-footer` |

Host maps blank peer-id to `nil`. Plist **body content** remains host-rendered
XML; only shell assembly is pure. Collection walks stay host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + parity + authority green

## Related

- ADR-260728-w6-provision-bootstrap-fold-pure-oracle
- ADR-260728-w6-provision-peer-id-pure-oracle
- murakumo#169 bootstrap fold pure
