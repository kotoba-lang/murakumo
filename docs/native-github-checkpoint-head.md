# Conditional GitHub checkpoint head candidate

`github_checkpoint_head.kotoba` adds a native candidate for recording which
verified checkpoint follows a pinned previous checkpoint. It first archives and
independently verifies the new signed closure using the existing archive
component. It then fetches a pre-provisioned task-specific head ref, requires
the host-pinned previous Git commit and exact previous checkpoint, commits the
new checkpoint pointer as its child, and pushes without force. A separate Git
repository fetches the exact resulting commit and checks its parent, tree and
content. A lost push acknowledgement is accepted only if that exact commit is
independently read back. Conflicts and unknown outcomes require reconciliation,
not automatic retry. A failed head update may already have archived its
immutable checkpoint; archival is not acceptance.

The ref is `refs/heads/itonami/head/<sha256(task)>`; its only tree entry is a
regular `head.json` containing canonical `checkpoint`, `kind` and `task` fields.
The initial commit and prior checkpoint must be independently authorized and
pinned by the host. This component does not bootstrap an authoritative head,
obtain a lease, select the current expected head, authenticate a bot or publish
external effects. The host must classify all data, isolate paths, pin Git/GH,
restrict credentials, configuration and egress, and establish process and
resource control as for the archive component.

**A normal Git push is not exact expected-OID CAS if a privileged actor can
rewind or delete the remote ref between fetch and push.** The competing-child
property requires a monotonic, non-deletable ref and no bypass by any writer.
The Git server does not validate this bot's lease. Even with a monotonic ref, a
stale lease holder can win before a newer holder advances it. This candidate is
therefore not sink-side lease fencing or authority to run autonomous bots.
The host must enforce those separate boundaries before integrating it with a
resident or production ref.

The [local Git fixture](evidence/awai150-github-head-candidate-20261103/)
checks competing children, readback after a deliberately lost acknowledgement,
and refusal of unexpected files and symlinks. It uses a local bare repository,
stubbed privacy and a privileged test-only Mac spawn adapter. No native GitHub
credential, production branch, real bot or full autonomous flow was tested.

The companion `github-checkpoint-head-restore-main` accepts a fresh private
work path and destination, independently pinned head commit, task, author,
profile, policy, environment, repository and GitHub executable. It verifies
the head tree and pointer at the pinned commit, reads the archive manifest,
then invokes the archive restore path to independently fetch and verify the
signed closure before materializing a new checkout. The head commit pin must
come from an external authority: accepting the current remote ref alone would
not protect against rollback. Restore grants no lease or permission to execute.
The [local restore fixture](evidence/awai150-head-restore-20261103/) verifies
state reconstruction and rejects wrong pins, an existing destination and a
corrupted archive block. Real GitHub credentials and production integration
remain unqualified.

GitHub's GraphQL `createCommitOnBranch` exposes `expectedHeadOid`; an
[operator-side synthetic test](evidence/awai150-github-graphql-cas-20261103/)
observed one winner and one expected-head mismatch for concurrent updates.
The live input schema names the unqualified branch field `branchName`.
This is a candidate replacement for the normal-push step, not yet an
integrated native writer or a lease fence. Expected-OID comparison does not
prevent an ABA rewind to the same OID or authorize a lease holder.
