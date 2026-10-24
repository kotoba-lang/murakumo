# Native identity, replica retention and recovery leases

Tracks [JUN-156](https://linear.app/kotobalabs/issue/JUN-156), with
[JUN-289](https://linear.app/kotobalabs/issue/JUN-289) and
[JUN-287](https://linear.app/kotobalabs/issue/JUN-287). Builds on merged
PR #541 (`8fc156533203c9efc5c84d2386f84a1a7daf732c`).

## Verified scope

The [evidence](evidence/fenced-profile-recovery-20261024.json) distinguishes:

- New host-local A/B identities, generated with native entropy and retained
  in private application data. These are not public RFC8032 fixture keys.
- A completed actual `accountability-steward` inference and signed its new
  checkpoint with its real recovery identity. B verified and independently
  restored that checkpoint, preserving step 2 as the restart point.
- A/B each verified and fsynced every checkpoint object, then signed a
  retention receipt. The independently pinned two-domain pair verified.
- Native unit gate 31; removing signature checks gives 29, removing lease
  ownership checks gives 15. A live-clock integration accepted a value that
  the old startup-clock voter rejected.
- A's task producer and both voters were stopped. B waited for the prior
  lease plus 60 s skew, learned epoch 2 from B+C, automatically restored the
  real A checkpoint and completed actual `mishima` inference step 2. Its
  state advanced to `next: 3`; a fresh majority still returned its lease.
- An A claimant was refused with `recovery/lease-not-held` before dispatch.
  C's voter was restarted, and B+C still returned the same persisted value
  while A stayed stopped. B signed the successor checkpoint with its real
  key, and two-domain A/B retention receipts verified for that successor.

Earlier PR #541's read-only inference drill used public signing fixtures and
a process liveness hint, and remains separate evidence. The new drill's
checkpoint, leases, receipts and actual inference result are recorded together.

The third physical host ran a dedicated witness VM on external application
storage. C's SSH key was checked against its independent boot console and
then pinned. Its host-local VSOCK SSH forwarder ran independently of A's
orchestration connection. B and C have `Linger=yes`. This was a task service
outage; reboot and physical power-loss service availability remain separate
operational gates. No production membership, Cell
admission, gateway route or existing resident service was changed.
C's main host volume remained nearly full; the task VM used external storage.
Its bootstrap and forwarding were sufficient for this drill, while managed
boot and storage-capacity remediation remain production-readiness work.

## Identity and immutable replica receipts

`recovery_identity.kotoba` accepts `init KEY_FILE` or `show KEY_FILE`. Its
host launcher supplies an exclusively owned 0700 application-data directory.
Init obtains 32 bytes from native entropy, writes/fsyncs a unique candidate,
sets 0600, and publishes it with a non-overwriting hard link. Concurrent
initializers return the same persisted identity; a damaged existing key is
refused rather than rotated. Candidate names contain a digest, not seed bytes.
Only the DID is printed. Node seeds never enter a Git repository, checkpoint,
peer payload, release archive or model request.

Identity init additionally grants `/usr/bin/stat`, `/usr/bin/chmod`,
`/usr/bin/ln`, `/usr/bin/unlink` via wire 20 and native entropy wire 23.
These tools are host providers. The identity decisions and key derivation run
in the Amu-produced native artifact. Existing identities need only the read
and permission-check subset. Keep filesystem grants confined to the selected
application-data roots; do not grant `/` to bridge a workspace and key store.
The pinned native filesystem ABI takes one root. Place the deployment's
public inputs, lease state, cache and restored checkout under its application
data root; a colon-joined list is not a filesystem-root grant.

`recovery_retention.kotoba` provides:

```text
retain STORE CHECKPOINT_CID NODE_KEY_FILE FAILURE_DOMAIN TASK UNTIL_MS
verify-pair RECEIPT_A_FILE RECEIPT_B_FILE INDEPENDENT_PINS_FILE
```

Retain verifies the signed checkpoint and complete object closure. It fsyncs
the root, profile, policy, environment, summary, bundle index, every chunk and
each allowlisted untracked object, then the store directory. It signs and
fsyncs its receipt and directory before reporting `retention/recorded CID`.
Closure validation also needs the narrowly granted `/usr/bin/stat` host
provider on wire 20, in addition to clock, filesystem and argv/stdout grants.
Receipts are canonical `{payload,signature}` envelopes. Payload fields are
`checkpoint`, `did`, `domain`, `kind=itonami.checkpoint-retention/1`, `task`,
and `until`. Reissuing the same promise is content-addressed and idempotent.

The caller's canonical pins specify `authorA`, `authorB`, `domainA`, `domainB`,
`checkpoint`, `task` and minimum `until`. The verifier requires two different
signers and two different, independently assigned failure domains. It does
not trust a peer's self-assigned domain or take pins from the receipt itself.
Declared domains are operator facts, not hardware attestation by this code.

These are local-filesystem durability barriers and signed retention promises.
They do not prove future disk survival, rollback-resistant custody, power-loss
durability of the device, an erasure-coded quorum, encryption or a GC/repair
service. This bounded selected profile cache contains no credentials; broader
private workspace replication still needs the approved encrypted storage
adapter. Replica objects live in application data, separately from keys and
ephemeral development checkouts. Do not delete promised replicas as cleanup.

## Lease-gated continuation

`fenced_profile_recovery.kotoba` composes the existing canonical
`fleet_lease_core` / `fleet_lease_node`, checkpoint, retention and profile
modules. It does not introduce another consensus algorithm. Each register
persists its promise/accepted value before replying. The voter now samples
its wall clock per request in `clock` mode, instead of freezing membership's
maximum lease deadline at startup; fixed-time diagnostic mode is unchanged.

```text
fenced-profile watch DEPLOYMENT_PLAN_FILE
fenced-profile step DEPLOYMENT_PLAN_FILE
```

The canonical plan has kind `itonami.fenced-recovery-plan/1`, `task`, `node`,
`checkpoint`, `store`, `dest`, `cache`, `contract`, `pins`, `receiptA`,
`receiptB`, `peer`, `previousLease`, `previousPorts`, `previousEpoch`, `ports`,
`epoch`, `revision`, `tokenFile` and `observedLease`. Paths and expected
identities come from the authorized host, not checkpoint instructions. The
prior and successor registers each require three distinct IPv4 host addresses;
multiple ports on one host do not count as independent voters. Exact dial and
listen addresses are separately narrowed by the native network grants.

Watch observes the producer alive before considering two missed heartbeats.
It learns the prior lease from an authenticated majority and requires exact
equality with the independently pinned prior value. It waits for canonical
`lease/take-over`, including the clock-skew barrier. It verifies the signed
replica pair and checkpoint binding before proposing the successor lease.
Only the chosen holder at exactly the next epoch may materialize and infer.
Direct `step` also freshly learns the predecessor and requires its expiry
plus skew before proposing the successor. The review counterexample and
native gate 63 are in [review evidence](evidence/direct-step-review-20261024.json).

The inference curl timeout is bounded by the remaining lease window, minus
the canonical skew and a publication margin, with an upper bound of 840 s.
The replica retention deadline can shorten this budget further, and expiry
after inference retains the proposal instead of reporting a valid continuation.
After inference the controller learns the lease again; a different/unavailable
lease or expired holder retains the proposal and refuses successful publication.
No model-output tools or legacy profile scripts are dispatched.

The existing wire protocol uses a task membership token for request/reply
HMAC, including binding replies to requests. The token is distinct from node
signing keys. Provisioning it to additional hosts is an explicit secret-sharing
operation, not an approval carried by a checkpoint. The owner explicitly
approved the task-only token's transfer from A to B and C. A pending approval
must not be replaced with public/fixed tokens or unauthenticated listeners.

Per-epoch registers, task-local operator topology pins and these checks do
not establish a global accepted head or a Byzantine quorum. Fresh membership,
delegation, one-winner authoritative output admission and chain anchoring stay
with the existing JUN-287/288/289 and Kagami/Nekko work. New recovery identities
have not been promoted into the production fleet's trust set.

## Native build and validation

Use the pinned root and dependency owners in [git-checkpoint.md](git-checkpoint.md).
After its SHA/Ed25519/JCS/OCI/tsukasa/enrollment closure, append in order:
`fleet_intent_core`, `fleet_topology_verify`, `fleet_lease_core`,
`fleet_lease_node`, `git_checkpoint`, `recovery_retention`,
`recovery_admission`, `profile_recovery`, `fenced_profile_recovery`.
For identity use checkpoint + `recovery_identity`; topology provisioning also
uses `recovery_topology` after the identity and topology verification sources.
Select the desired main function and compile `aarch64-linux` / `x86_64-linux`
static artifacts. Native gates additionally append `recovery_retention_test`.
No CI/CD workflow or legacy language fallback is added.

Deployment requires actual ready voters on three physical hosts, current
topology/trust pins, private membership-token provisioning, durable register
state and a separately managed watcher. Require stale-owner refusal before
dispatch and fresh lease verification after inference. Preserve evidence of
what was actually run; do not substitute the pure gate for this live drill.
After the drill, stop only its task-owned units and forwarding helpers, keeping
host identities, promised checkpoint replicas and persisted lease state.
