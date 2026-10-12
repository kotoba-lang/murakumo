# Private cluster monitoring and user-owned registration

Each admitted Mac keeps its device-held Ed25519 key. The roster fixes every
DID/public key/Tailnet endpoint and is identified by a membership digest.
The Itonami cluster-monitor worker imports this exact package's protocol by
SHA-256; peer signatures admit only read-only telemetry, never human ownership
or inference/business actions. Every node runs its own recurring sensor and
records signed receipts. Other members verify and retain those receipts, so a
collector or laptop stopping does not stop the remaining monitoring routines.

The native status app reads the local authenticated monitor at loopback 19843.
Peer reads use signed, audience/purpose/time/sequence-bound requests on Tailnet
19842. Replays and older/equivocating peer receipts are refused. Checkpoints and
receipt files stay in a private per-user workspace. There is no public anonymous
telemetry endpoint and no coordinator that owns every monitoring tick.

`murakumo-native link` prepares the existing production Node approval flow.
`link-status` signs a fresh device proof to check the production registration
record. The owner must approve with the existing user-held Passkey/wallet;
operator access, SSH, monitoring signatures and a node DID do not grant ownership.
Registration proposals expire after five minutes; the monitor can refresh them
when explicitly installed with `--prepare-registration`. It never creates a new
human credential or chooses the owner account. Registration state is attributed
to the production registration verifier, not claimed as local receipt revalidation.

Common key and common authorization are different. The same supported wallet
controller can prove the same account at both products, but sessions, audiences,
nonces and resource grants remain separate. A Passkey is RP-scoped; this release
does not establish a cross-product Passkey login bridge or migrate older owners.

The monitor is the first operating routine, not a qualification of arbitrary
Itonami jobs, global consensus, always-available hardware or OS reboot behavior.
