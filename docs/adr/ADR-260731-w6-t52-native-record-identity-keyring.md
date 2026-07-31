# ADR-260731: T5.2 native guest record wire — identity + overlay.keyring

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#277–#279 (eligibility / token / credits / gc native-record waves)
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure preimage/math inputs on identity and overlay-keyring
into **single named guest records**:

| Module | Export | Schema | Fields |
|--------|--------|--------|--------|
| `identity_core` | `seed-node`, `seed-p2p` | `:identity/node-seed` | operator-seed, node-name |
| `identity_core` | `seed-overlay` | `:identity/overlay-seed` | operator-seed, overlay-id |
| `identity_core` | `did-derive-cmd` | `:identity/did-cmd` | kotoba, seed |
| `overlay_keyring_core` | `epoch` | `:keyring/epoch-in` | seconds, rotation-seconds |
| `overlay_keyring_core` | `key-id-input` | `:keyring/key-id-in` | overlay, epoch |
| `overlay_keyring_core` | `derive-key-input` | `:keyring/derive-in` | operator-seed, overlay, epoch |

Host builds `oracle/record` and projects via `call-record` with a single
`:raw` field. Single-arg residual (`seed-x25519`, seps/tokens) stays scalar.

## Non-claims

- SHA-256 / b64url / key map assembly remain host.
- Does not convert relay / persist / moe multi-arg pure in this change.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `identity_core` + `overlay_keyring_core` only
- identity/keyring parity + oracle-call-record + authority suites green
  (97 tests / 1716 assertions)
