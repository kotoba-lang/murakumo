# ADR-260728: W6 deploy cmd recompose pure oracle expansion

Status: accepted after provision multiaddr path tokens pure (#189)

## Decision

Expand `kotoba/deploy_plan_core.kotoba` with residual **argv join / localhost
URL prefix** tokens and recompose space-joined cmds from fragment SSoTs:

| export | role |
|---|---|
| `argv-join-sep` | space between space-joined cmd tokens |
| `localhost-url-prefix` | `http://localhost:` |

- `localhost-url` recomposes from prefix + port
- `component-build-cmd` / `app-deploy-cmd` recompose from subcmd/flag + sep tokens
- host dual-sources the new tokens; argv vector assembly stays host

## Evidence

- regenerated `deploy_plan_core.kir.edn`
- deploy parity + authority green

## Related

- ADR-260728-w6-deploy-argv-flags-pure-oracle
- murakumo#189 provision multiaddr path tokens pure
