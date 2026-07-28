# ADR-260728: W6 identity seed/JWT seps pure oracle expansion

Status: accepted after cloud node-type pure (#167)

## Decision

Expand `kotoba/identity_core.kotoba` with residual **seed/JWT separators and
did-derive subcommand**, dual-sourced on `murakumo.identity`:

| export | role |
|---|---|
| `seed-sep` | `:` in seed preimages |
| `seed-p2p-suffix` | `:p2p` |
| `seed-x25519-suffix` | `:x25519` |
| `seed-overlay-suffix` | `:murakumo-overlay-auth` |
| `did-derive-subcmd` | `did-derive` |
| `jwt-seg-sep` | `.` between JWT segments |
| `argv-join-sep` | space in did-derive-cmd |

Seed preimages and did-derive-cmd recompose those fragments. Host mirrors /
`did-derive-argv` / `op-token` dual-source seps; SHA-256 / b64url stay host.

## Evidence

- regenerated `identity_core.kir.edn`
- identity unit/parity + authority green

## Related

- ADR-260728-w6-identity-credits-oracle-authority
- murakumo#167 cloud node-type pure
