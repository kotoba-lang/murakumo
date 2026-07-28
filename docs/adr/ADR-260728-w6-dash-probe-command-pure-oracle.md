# ADR-260728: W6 dash probe-command pure oracle expansion

Status: accepted after tunnel forward pure (#156)

## Decision

Expand `kotoba/dash_state_core.kotoba` with residual **dashboard probe shell
string** dual-source (previously host-forever citing quoting hazard). Escapes
for `tr '\\n'` are expressible in kotoba (verified byte-stable vs host):

| export | role |
|---|---|
| `health-url` | `http://localhost:{port}/health` |
| `mesh-log-path` | `~/.murakumo/mesh.log` |
| `probe-h-prefix` / `probe-l-clause` / `probe-p-clause` | H:/L:/P: shell segments |
| `probe-command` | full remote probe shell for one port |

Host dual-sources via `:dash-state`. **SSH execution** of the shell string
stays host-forever. Map folds (probe-lines / parse-hosted / render-html) stay
host.

## Evidence

- regenerated `dash_state_core.kir.edn`
- dash unit + parity + authority green
- probe-command(8077) oracle == host mirror byte-stable

## Related

- ADR-260728-w6-dash-probe-pure-oracle (parse-links / line key-value)
- ADR-260728-w6-tunnel-forward-pure-oracle (shell fragment compose pattern)
- murakumo#156 tunnel forward pure
