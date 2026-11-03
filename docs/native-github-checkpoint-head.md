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
