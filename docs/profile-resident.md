# Native Itonami profile residency

Tracked in [JUN-156](https://linear.app/kotobalabs/issue/JUN-156), with the
production membership/admission boundaries in JUN-287/288/289.
This extends [fenced recovery](fenced-profile-recovery.md) from a two-turn
drill to a resident **read-only accountability-steward** profile. The native
controller schedules the actual pinned profile/SOUL, restores an independent
Git checkout for each proposal, and admits its next checkpoint only after a
fresh lease and two signed, fsynced host replicas. This is a selected native
adapter, not qualification of every Itonami CLI/cron/tool workflow.

The source profile is `kotoba-lang/kotoba` revision
`bd97e7183463c6dd36032ca913fa70fb4a3ec068`,
`hermes/profiles/accountability-steward`. Its shared format does not make this
a Hermes execution: host state belongs in `~/.itonami`, rail is Murakumo,
model is `mishima`, and no model output is dispatched as a tool. The source's
"daily" declaration becomes a fixed 86,400,000 ms interval. Each proposal has
at most 256 completion tokens; it explicitly has no live external observations.
The existing two-turn recovery command keeps its original limit and prompt.

## Components and state

| Component | Native entry | Responsibility |
| --- | --- | --- |
| Resident | `profile-resident-main`, `run CONFIG` or `once CONFIG` | Schedule, restore, previous-output continuity, inference, lease checks, capture, replication and atomic head |
| Dedicated voter | `lease-group-main`, `ADDRESS STATE_DIR TASK TOPOLOGY TRUST TOKEN_FILE` | Existing durable Paxos register, multiplexed by epoch on one endpoint |
| Replica peer | `checkpoint-peer-main`, `CONFIG` | Task-authenticated immutable put/get, independently pinned checkpoint verification and real host-key retention signatures |
| Checkpoint CLI | `checkpoint-main` | Existing independent Git capture/restore and signed checkpoint contract |

Recommended private layout (application ancestors mode 0700; seeds/token 0600):

```text
~/.itonami/
  profiles/accountability-steward/{itonami.edn,SOUL.md,source.json}
  native/accountability-steward/
    identity/node.seed
    membership.token
    bin/<sha256>/{resident.elf,checkpoint.elf,group.elf}
    group/epoch-<n>.state
    topology.json
    runtime/
      resident-config.json policy.json runtime.json
      store/<CID>
      checkouts/<attempt-time>/
      cache/<request-sha256>.{request,response}.json
      head.json candidate-head.json lease.json status.json request.json
```

Host-local hardlinks can retain an already issued node identity inside the
new native FS scope on the **same host/filesystem**. Check inode, owner and
mode; do not rotate an existing identity or copy/export its seed. Membership
provisioning requires explicit owner authorization. A/B seeds are never sent
to a peer, checkpoint or witness. Keep old promised stores and unrelated WIP.

Host configuration supplies the author DIDs, failure domains, task, exact
profile/policy/environment pins, topology operator trust, endpoint set and
native artifact paths. Checkpoint bytes cannot choose these. The resident
config kind is `itonami.profile-resident/1`; the peer config kind is
`itonami.checkpoint-peer/1`. See the sanitized live evidence for pins and
artifact hashes, and the example schema in `deploy/itonami-resident/`. The examples use
placeholder host paths/endpoints and are not deployable without independent
operator provisioning and review.

## Scheduling and recovery contract

1. Sign a bootstrap checkpoint on A with parent `root`, epoch >=1 and state
   `{next:1,previous:"none",task:<fixed task>}`. Its policy must describe the
   daily resident; the earlier two-turn drill policy is not reused.
2. Provision the complete immutable closure on B. A and B independently flush
   every object and their directory, then sign the same root/deadline using
   different real DIDs and failure domains. No inference occurs during this
   initial replica-admission cycle.
3. A due cycle learns the authenticated current register, renews an owned
   lease when needed, and waits for a different owner's expiry plus 60 s skew
   before proposing a successor epoch. Three endpoints must identify three
   distinct hosts. Losing a majority fails before model dispatch.
4. Materialize an independent checkout, verify source/SOUL/runtime pins and the
   CID of the immediately preceding output. Submit the fixed HTTPS operation
   to `https://api.murakumo.cloud/v1/chat/completions`. No redirects, ambient
   curl config, credentials, model-selected URL or tool dispatch are allowed.
5. The request body hashes to a stable systemd inference unit and cache path.
   A resident crash reuses an in-flight/completed unit and the same cache;
   runtime restart does not create another inference for that request.
6. After the actual model result, recheck both the input retention deadline
   and the lease, capture a successor signed
   checkpoint, automatically upload its entire closure, obtain both signed
   retention receipts, recheck the input deadline and lease again immediately
   before atomically publishing head. An expired input keeps the proposal and
   leaves the admitted head unchanged, even when its successor has replicas.
   Only the latest output is allowlisted in the successor checkpoint; older
   outputs are not needed to continue the immediately preceding proposal.
7. The head records `nextAt` and the consumed operator request CID together.
   A normal restart after publication stays idle. A unique canonical operator
   request file may enqueue explicitly authorized pending work; replacing it
   with the same bytes cannot enqueue a second turn. Local wall clocks must
   remain healthy. There is no whole-fleet exactly-once inference claim.

`status.json` is atomically updated and provides progress even when stdout is
buffered. Head publication uses file fsync, rename and parent-directory fsync.
A crash after result/replication but before head admission leaves a proposal;
resume uses the last admitted root. Native request cache can recover the
result, but external side effects are never replayed by this adapter.

Retention is refreshed for 48 hours; a peer refuses promises beyond 7 days.
The peer admits blocks <=16 KiB for this small profile workload, and refuses
larger blocks instead of truncating. Existing checkpoint bundles support
larger chunks, so general repositories need a separately reviewed streaming
transport before using this peer. A signature is a retention promise, not
proof of future media survival. There is no automatic eviction/repair GC;
keep promised objects, archive failed attempts, and review local usage before
removing any task-owned data. Do not delete an object until all live heads,
receipts and retention commitments are accounted for.

## Deployment and restart

Run voter/peer/resident as separate **enabled** user services with linger on
Linux. Use a pinned absolute native executable path, `Restart=on-failure`,
`RestartSec=10`, `NoNewPrivileges=yes`, and an appropriate memory bound.
Application directories are private; use UMask 0022 because the selected
checkpoint file format admits regular files with mode 0644/0755. Secrets stay
0600. Do not restart a shared physical host to qualify this profile.

Grant only the necessary native capabilities:

- voter: wires 7,35,37,38,43,44,46,47; FS35 application root and exact listener43;
- peer: the same plus spawn20 `/usr/bin/stat` for durable closure checks;
- resident: 7,20,23,35,37,38,45,46,47; FS35 `~/.itonami`, exact voter/peer45
  addresses; spawn20 Git, mkdir, stat, chmod, systemd-run, systemctl, sleep and
  the pinned checkpoint executable only. Spawn20 resources use **colons**;
  outbound45 resources use **commas**. FS35 is one absolute root, not a list.

The checkpoint child must inherit its exact `KEXE_ALLOW`, FS35 and spawn20
grants through `KEXE_SPAWN_ENV`, together with PATH, XDG_RUNTIME_DIR,
DBUS_SESSION_BUS_ADDRESS and fixed Git config overrides. Set global Git config
to `/dev/null`, system config disabled. A profile cannot broaden these grants.

TCP framing is task HMAC authenticated; confidentiality is supplied by the
operator's **encrypted Tailnet/verified SSH transport**. Bind guest listeners
to loopback and expose only exact Tailnet addresses using native SSH
forwarders. Never expose these endpoints as unauthenticated public storage.
A replica signature/fsync may take longer than a simple register response;
the peer client allows a bounded 30 s initial response wait, then bounded 3 s
reads. Large or stalled messages fail closed.

Use only dedicated services/VMs for fault injection. Before a VM power cycle,
record its boot ID and native voter state. After boot verify a changed boot ID,
enabled/active voter, persisted register, quorum availability and actual result
continuation. A managed process existing is not proof that its VM booted.
Mac host login/boot startup and host storage capacity remain separate gates
from Linux guest user-service persistence. Preserve working VM storage; do not
free host space by deleting unrelated caches or repositories.

## Native build and gates

Use the pinned root/dependency closure in [git-checkpoint.md](git-checkpoint.md).
Append `fleet_intent_core`, `fleet_topology_verify`, `fleet_lease_core`,
`fleet_lease_node`, `git_checkpoint`, `recovery_retention`, `recovery_admission`,
`profile_recovery`, `fenced_profile_recovery`, `recovery_identity`,
`fleet_lease_group`, `checkpoint_peer`, `profile_resident`. Flatten namespace
headers into the seed's single namespace input, then select the desired main.
Build static `aarch64-linux` and `x86_64-linux` artifacts. A pure gate additionally
appends `git_checkpoint_test` and `profile_resident_test`: result/exit status
127. It covers the exact retention deadline and independently lost leases.
Removing the **production** retention guard produces 95. Before this review's
expanded gate, removing the production `pp-root?` checkpoint signature/contract
check changed 31 to 23. These are not assertion-inversion tests. CI/CD stays stopped;
there is no Node, nbb, JVM or Python product runtime fallback.

The per-epoch quorum and operator-pinned local head do not establish a global
accepted head, Byzantine consensus, production Cell membership, delegated
write authority, OCI job dispatch or a chain-anchored recovery receipt. Those
remain explicit JUN-156/287/288/289 acceptance work. Model proposals cannot
publish source, contact people, run tools or grant browser-operation consent.

## Measured deployment (2026-10-24)

[Sanitized evidence](evidence/profile-resident-restart-20261024.json) records
the real source pins, compiled artifact hashes, two actual model results,
automatic runtime crash recovery, deduplicated completed-request restart,
A/B replica readback after peer restart, no-majority refusal, and C guest boot
ID change with enabled/active native voter. B+C chose the same persisted lease
across a C voter process restart while the A voter was stopped.

The B resident, A replica, and all Linux voters remain enabled/active. The
current C native VM and SSH transport are running, but C Mac managed host
startup remains unqualified: a distinct launchd candidate never established
its listener and was removed without disrupting the working transport. The
root volume also has insufficient free space for new user LaunchAgents.
No physical Mac/shared B host restart or host-storage cleanup was performed.
The qualified runtime crash/guest reboot does not claim host-login auto-start.

[PR #544 review evidence](evidence/profile-resident-review544-20261024.json)
records the retention regression and its correction. A loopback-only fixture
delayed the real A retention response by 22 seconds, using an isolated head
with a conservatively shortened deadline. The old resident published after
that deadline; the corrected native resident refused publication. Both reused
the already completed inference unit with provider submission capability
removed. The production head, schedule and inference cache stayed unchanged.
The fixture was stopped after the check; it is not a product runtime helper.

C's host boot installation requires an administrator-owned LaunchDaemon,
running the exact dedicated VM/transport as the unprivileged owner. FileVault
unlock remains a physical boot prerequisite. The owner requested startup
configuration and dedicated VM qualification first, with physical C reboot
deferred until unlock preparation. The real daily tick remains scheduled for
2026-10-25 18:21:15.493 JST; a thread follow-up checks it after 18:45 JST.
Neither the clock nor `nextAt` was advanced to claim elapsed-time evidence.
