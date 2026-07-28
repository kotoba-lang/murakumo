# ADR-260728: W6 dash.state probe/parse pure oracle expansion

Status: accepted after cljs dual-source complete (#122–#135)

## Decision

Expand `kotoba/dash_state_core.kotoba` beyond display math with pure probe/
response helpers used by the dashboard host shell (`dash.clj` / `ops.cljs`):

| export | role |
|---|---|
| `parse-links` | trim + digit parse → i64 (0 on empty/non-digit) |
| `probe-line-key` / `probe-line-value` | `H:`/`L:`/`P:` line shape for host fold |
| `content-type-json` / `content-type-html` / `http-ok-status` | response constants |
| `health-from-present` / `health-ok-label` / `health-down-label` | probe node health label |

Host dual-source via existing `:dash-state` catalog + `try-oracle` / mirrors.

### Still host

| concern | why |
|---|---|
| `probe-command` shell string | SSH host-forever; quoting/newline hazard in guest strings |
| `probe-lines` fold over lines | collection reduce |
| `parse-hosted` comma split | list fold |
| `render-html` / `diff-alerts` | map assembly |

## Evidence

- `kotoba/dash_state_core.kotoba` + regenerated `dash_state_core.kir.edn`
- `murakumo.dash.state` dual-source wiring
- parity + unit + authority suites green

## Related

- ADR-260728-w6-dash-oracle-authority
- murakumo#135 residual clj dual-source complete
