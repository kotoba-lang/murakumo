# ADR-261001: 802.11s mesh and auto-enrol of a second edge node

Status: accepted (owner, 2026-10-01). Implemented and verified except on a
real radio (see "Not verified").

## Decision

- Edge nodes form an 802.11s mesh (SAE, `mesh_fwding=1`, 5180 MHz). AP+STA
  concurrency is not needed and is not used.
- A joiner that reaches the mesh and proves possession of its device key is
  admitted `authorized` with no human step, **when the ledger opts in** with
  `:admit :auto-on-mesh`. Without the key a join stays `pending`.
- kekkai's own `:node/admit -> human` flow is unchanged; murakumo writes the
  ledger snapshot directly (`murakumo.mesh/admit`).
- `edge-install` still does not enrol. The seed runs a separate enrol unit.

## Parts

| ns | role |
|---|---|
| `murakumo.mesh` | pure: wpa_supplicant SAE template, mesh-point script, `admit`, proof message |
| `murakumo.mesh.seed` | pure enrol state machine (`/enroll/challenge`, `/enroll/claim`) |
| `murakumo.mesh.seed-server` | node:http shell, binds the mesh address only, persists the ledger |
| `murakumo.mesh.client` | joiner: persistent device seed, proof, takes the allocated address |
| `murakumo.mesh.install` | pure Linux/systemd plans for `:seed` and `:joiner`, join token |
| `murakumo.mesh.install-cli` | runs a plan over SSH (`--dry-run` prints it) |

## Flow

1. `install-cli seed --host H --node-name N` installs the mesh + enrol endpoint
   on the first node and prints a join token (`murakumo-mesh:v1:...`).
2. `install-cli joiner --host H2 --node-name N2 --token T` installs the mesh on
   the second node. On boot its client enrols; the seed writes it to the ledger
   as `authorized` with the next free `10.77.0.x`.

The token carries the SAE passphrase and is the one thing a human moves between
machines: a radio without it cannot reach the endpoint that would admit it.

## Proof

Ed25519 over a domain-separated string (nonce, seed endpoint, DID, node name),
verified against the key inside the claimed `did:key`. Not
`aiueos.provider.device`'s proof: that is JVM-only (JDK Ed25519 + a
canonicaliser) and edge nodes run the JVM-free kbb engine. `ed25519.core` is
portable. There is no second canonicaliser to drift, and no separate key field to
disagree with the name.

## Gates replacing the human

1. SAE passphrase (generated on the seed, never in the ledger or a plan).
2. Device-key possession proof; challenges are single-use, 60 s, capped at 64.
3. A revoked DID is refused first; a node name cannot be taken by another key;
   addresses are never reused; the ledger records `:admitted-by :auto-on-mesh`
   and `:admitted-ms`. A missing/unreadable ledger never defaults to auto-admit.

## Verified

- Pure logic: `kbb -M:test -n murakumo.mesh-test -n murakumo.mesh-seed-test
  -n murakumo.mesh-install-test`.
- Generated shell passes `bash -n`.
- Real server + real client on kbb over loopback with real Ed25519: admission,
  idempotent re-join, next-address allocation, same-name-other-key refused, key
  file mode 0600.

## Not verified

- A real mesh radio. The box (6600hs-2, 100.84.134.120) was unreachable by SSH.
  `iw phy | grep 'mesh point'` is the check; Intel AX200/AX210 (iwlwifi) cannot
  do mesh point, mt7921/mt7922 can. The generated mesh-iface script refuses a
  radio without it (exit 3).
- systemd units, `wpa_supplicant` mesh association, and `kbb` on the target
  Linux nodes (the preflight requires `kbb` on PATH and the repo at
  `/opt/murakumo`).
- 802.11s over a 5 GHz channel needs a channel legal for the node's location
  (36 is W52, indoor-only in Japan); pass `--frequency` otherwise.
- The enrol endpoint speaks plain HTTP over the SAE-protected mesh. SAE is the
  confidentiality layer; there is no TLS.

## Follow-ups

- Sync the seed's ledger to where `murakumo.kekkai` reads it
  (`kekkai-tailnet.edn`); today it lives at `/var/lib/murakumo/` on the seed.
- Revocation command (`murakumo.mesh/revoke` exists; no CLI yet).
- kekkai's governor still routes `:node/admit` to a human for its own flow.
