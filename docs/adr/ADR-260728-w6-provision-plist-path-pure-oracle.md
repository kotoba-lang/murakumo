# ADR-260728: W6 provision plist path pure oracle expansion

Status: accepted after provision peer-entry pure (#158)

## Decision

Expand `kotoba/provision_plan_core.kotoba` with residual **render-plist path
and CSV join fragments**, dual-sourced on `murakumo.provision.plan`:

| export | role |
|---|---|
| `home-bin-suffix` | `/.murakumo/bin` under node home |
| `home-bin-path` | `home + home-bin-suffix` for `{{BIN}}` |
| `label-join-sep` | `,` between `label-kv` pairs in `labels-env` |
| `roles-join-sep` | `,` for `{{ROLES}}` CSV |

`render-plist` uses `home-bin-path` + `roles-join-sep`; `labels-env` uses
`label-join-sep`. Template `str/replace` fold and map folds stay host.

## Evidence

- regenerated `provision_plan_core.kir.edn`
- provision unit + parity + authority green

## Related

- ADR-260728-w6-provision-peer-entry-pure-oracle
- murakumo#158 bootstrap peer-entry pure
