# ADR-260728: W6 tunnel ssh/scp argv pure oracle expansion

Status: accepted after cloud parse-flags pure (#153)

## Decision

Expand `kotoba/tunnel_core.kotoba` with residual **ssh/scp argv bin and flag
fragments**, dual-sourced on `murakumo.tunnel`:

| export | role |
|---|---|
| `ssh-bin` | `ssh` |
| `scp-bin` | `scp` |
| `o-flag` | `-o` (option) |
| `O-flag` | `-O` (control) |
| `exit-ctl` | `exit` control verb |

Host dual-source wires `ssh-argv` / `scp-argv` / `close-master-argv` /
`conn-opts` / `ssh-opts`. **SSH subprocess execution** and parse-rc line-split
stay host-forever. Embedded `ssh` inside ensure/replace-forward shell strings
is unchanged (follow-up if needed).

## Evidence

- regenerated `tunnel_core.kir.edn`
- tunnel unit + parity + authority green

## Related

- ADR-260728-w6-tunnel-result-pure-oracle
- ADR-260728-w6-tunnel-config-oracle-authority
- murakumo#153 cloud parse-flags pure
