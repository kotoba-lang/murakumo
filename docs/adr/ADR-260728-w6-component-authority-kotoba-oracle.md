# ADR-260728: W6 component-authority pure scalar kotoba oracle

- Status: Accepted
- Date: 2026-07-28

## Context

`murakumo.component-authority` owns Component placement epochs (place/revoke
fence). Full state maps, placement sets, and ed25519 signing stay host. The
identifier policy and epoch/sequence arithmetic are pure and oracle-eligible.

## Decision

Port to `kotoba/component_authority_core.kotoba`:

| function | notes |
|---|---|
| `identifier?` | non-blank string ≤ 4096 bytes |
| `place-epoch` / `revoke-epoch` | missing projected as 0 |
| `next-sequence` | `seq+1` |
| `command-op` / `event-kind` | place→placed, revoke→revoked |
| `event-version` / envelope format+algorithm strings | constants |

### Not ported

- `initial-state` / placements set folds
- event map assembly
- `sign-event` / ed25519 / ABI envelope validation
- `apply-command!` atom + publish boundary

## Evidence

- `test/murakumo/component_authority_kotoba_parity_test.clj`
