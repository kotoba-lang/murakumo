# ADR-261001: 802.11s mesh and auto-enrol of a second edge node

Status: accepted (owner, 2026-10-01). Implemented so far: pure core only.

## Decision

- Edge nodes form an 802.11s mesh (SAE, `mesh_fwding=1`, 5180 MHz). AP+STA
  concurrency is not needed and is not used.
- A joiner that reaches the mesh and proves possession of its device key is
  admitted `authorized` with no human step, **when the ledger opts in** with
  `:admit :auto-on-mesh`. Without the key a join stays `pending`.
- kekkai's own `:node/admit -> human` flow is unchanged; murakumo writes the
  ledger snapshot directly (`murakumo.mesh/admit`).
- `edge-install` still does not enrol. The seed runs a separate enrol unit.

## Gates replacing the human

1. SAE passphrase (generated on the seed, never in the ledger).
2. Device-key possession proof.
3. Revoked DIDs are refused first; a node name cannot be taken by another key;
   addresses (10.77.0.0/24) are never reused; the join is audited
   (`:admitted-by :auto-on-mesh`, `:admitted-ms`).

## Not done yet (needs hardware)

- Linux/systemd seed installer (the existing `edge_install` is macOS-only).
- Enrol endpoint on the seed, joiner client, ledger persistence.
- Radio check: `iw phy info | grep 'mesh point'` on 6600hs-2. Intel
  AX200/AX210 (iwlwifi) cannot do mesh point; mt7921/mt7922 can. The box was
  unreachable (SSH timeout to 100.84.134.120) when this was written.
