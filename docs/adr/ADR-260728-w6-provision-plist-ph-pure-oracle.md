# ADR-260728: W6 provision plist placeholder pure oracle expansion

Status: accepted after deploy argv flags pure (#163)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **LaunchDaemon
template placeholder tokens**, dual-sourced on `murakumo.provision.plan`:

| export | token |
|---|---|
| `plist-ph-user` | `{{USER}}` |
| `plist-ph-bin` | `{{BIN}}` |
| `plist-ph-port` | `{{PORT}}` |
| `plist-ph-roles` | `{{ROLES}}` |
| `plist-ph-labels` | `{{LABELS}}` |
| `plist-ph-home` | `{{HOME}}` |
| `plist-ph-ed25519` | `{{ED25519}}` |
| `plist-ph-x25519` | `{{X25519}}` |
| `plist-ph-did` | `{{DID}}` |
| `plist-ph-p2pport` | `{{P2PPORT}}` |
| `plist-ph-p2pseed` | `{{P2PSEED}}` |
| `plist-ph-extaddr` | `{{EXTADDR}}` |
| `plist-ph-bootstrap` | `{{BOOTSTRAP}}` |
| `plist-ph-webrtc` | `{{WEBRTC}}` |

`render-plist` and `render-watchdog-plist` dual-source those tokens;
`str/replace` fold stays host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + parity + authority green

## Related

- ADR-260728-w6-provision-plist-path-pure-oracle
- ADR-260728-w6-provision-peer-id-pure-oracle
- murakumo#163 deploy argv flags pure
