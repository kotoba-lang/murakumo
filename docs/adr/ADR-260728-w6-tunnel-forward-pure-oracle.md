# ADR-260728: W6 tunnel forward/curl fragment pure oracle expansion

Status: accepted after tunnel argv pure (#154)

## Decision

Expand `kotoba/tunnel_core.kotoba` so **ensure-forward**, **replace-forward**,
and **remote-curl** shell strings compose from pure fragments (no hardcoded
`ssh` / `pgrep` / `curl` literals in the assembled commands):

| export | role |
|---|---|
| `pgrep-bin` / `pkill-bin` / `f-flag` | process probe/kill |
| `fN-flag` / `L-flag` | ssh background local-forward |
| `null-redirect` / `or-sep` / `settle-sleep` / `pkill-suffix` | shell glue |
| `localhost-colon` / `forward-spec` | `{lp}:localhost:{rp}` |
| `ssh-forward-prefix` | `ssh -o BatchMode=yes -fN -L ` (uses `ssh-bin`/`o-flag`/`batch-mode-opt`) |
| `curl-prefix` / `curl-stderr-redirect` | remote-curl pieces |

`ensure-forward-command` / `replace-forward-command` / `remote-curl-command`
are rewritten to compose these fragments. Host dual-sources fragment constants
+ full command exports. SSH **subprocess** stays host-forever.

## Evidence

- regenerated `tunnel_core.kir.edn`
- tunnel unit + parity + authority green
- ensure/replace byte-stable vs previous mirror strings

## Related

- ADR-260728-w6-tunnel-argv-pure-oracle
- murakumo#154 tunnel argv pure
