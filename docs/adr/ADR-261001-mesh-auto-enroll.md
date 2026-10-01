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
| `murakumo.mesh.ledger` | pure: parse/validate, revoke, two-way `sync-plan` |
| `murakumo.mesh.ledger-cli` | `sync`, `revoke`, `list` against the seed over SSH |

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

## Ledger sync and revocation

The seed writes admissions; the operator owns hand-authored entries and
revocations. `ledger-cli sync` reconciles them:

1. Revoked is sticky in both directions (a stale copy cannot un-revoke).
2. The operator's `:admit` flag is never imported from the seed.
3. An authorized name never changes key through a sync; a disagreement is
   reported (exit 3) and the local entry kept.

`ledger-cli revoke NAME|DID` writes the operator's ledger first (the one fleet
operations consult), then the seed's. If the seed is unreachable the revocation
stands locally and the next `sync` pushes it. The seed refuses a revoked DID
under any name. The server re-reads the ledger file on every request, so a
revocation takes effect on the next join; a corrupt or missing-at-runtime file
answers 503 and is never overwritten (startup over a corrupt file refuses to
start; absent at startup is an empty ledger without the opt-in).

The operator's first ledger is kept as `<path>.orig`, the previous as `.bak`
(a rewrite drops hand-written comments).

Verified: unit tests (`murakumo.mesh-ledger-test`, `murakumo.mesh-seed-server-test`)
and a loopback run of real server + client + CLI with a stand-in `ssh`: sync is
idempotent, revoke reaches both ledgers, the revoked key is refused under its
old and a new name, other nodes are unaffected, an unreachable seed degrades to
a local revocation.

**A revocation does not take the radio off the mesh.** The node still holds the
SAE passphrase and can associate and talk layer 2/3 to other nodes. It is cut off
from fleet operations and from re-enrolling, not from the air. Rotating the
passphrase (new `mesh.env` on every remaining node) is the cutoff for that and is
not implemented.

## Follow-ups

- Passphrase rotation across the remaining nodes (see above).
- `sync`/`revoke` read-modify-write the seed's ledger over SSH; a join landing in
  that window can be lost (the atomic rename keeps the file whole, not the race).
  Narrow in practice, closed by moving the edit into the server.
- kekkai's governor still routes `:node/admit` to a human for its own flow.
