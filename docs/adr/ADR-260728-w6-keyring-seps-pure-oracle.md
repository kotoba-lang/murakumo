# ADR-260728: W6 overlay keyring seps/type tokens pure oracle expansion

Status: accepted after component-authority op/event tokens pure (#184)

## Decision

Expand `kotoba/overlay_keyring_core.kotoba` with residual **preimage separators
and type tokens**, dual-sourced on `murakumo.overlay.keyring`:

| export | role |
|---|---|
| `seed-sep` | `:` between seed/overlay segments |
| `key-id-mid` | `:key:` in kid preimage |
| `derive-key-mid` | `:murakumo-overlay-key:` in derive preimage |
| `type-key` / `type-rotation` | map `:type` bare names |
| `key-id-hex-len` | kid hex prefix length (16) |

`key-id-input` / `derive-key-input` recompose from seps. SHA-256 stays host.

## Evidence

- regenerated `overlay_keyring_core.kir.edn`
- keyring parity + authority green

## Related

- ADR-260728-w6-overlay-pure-kotoba-oracle
- murakumo#184 component-authority op/event tokens pure
