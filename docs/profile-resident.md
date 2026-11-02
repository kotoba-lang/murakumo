# Native Itonami profile residency

The draft lease-group path now requires an independently provisioned
`epoch-watermark` in each group directory. While holding a native scoped flock it
advances the watermark before creating the next epoch's voter file; missing
files at or below the watermark are refused. Epochs cannot skip ahead. Process
death releases the kernel lock; a watermark without its epoch file still
requires verified recovery. A leftover `.epoch-lock` directory from the earlier
draft is refused and must be reconciled. The `.epoch-lockfile` inode must be
retained and never renamed or unlinked while a group may run. Existing
directories need an explicit migration; creating
a new zero watermark after state loss is unsafe. A whole-directory rollback
can replay both files and is not prevented. The scoped ARM64 native tests,
including a three-server same-VM integration, are recorded in
`docs/evidence/awai150-group-flock-20261103.json`; this is not Mac-host,
network-failure, sudden-power-loss or production qualification.

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
"daily" declaration becomes a fixed 86,400,000 ms interval. Each original
accountability proposal has at most 256 completion tokens; it explicitly has no
live external observations. The existing two-turn recovery command keeps its
original limit and prompt for that profile.
Responses are usable only when the first choice has nonempty content and
`finish_reason` is `stop`. Truncated, tool-call, missing and unknown finish
reasons do not advance the checkpoint; a prior response that fails the same
check cannot be used as continuation input. A stopped response is still a
model proposal, not proof that its claims or the requested work are complete.

### Optional local MTP transport

The opt-in runtime kind `itonami.readonly-profile-runtime-local-mtp/1` uses
the same profile, policy, lease and retention checks as the original resident.
Its `endpoint` must be exactly `http://localhost/v1/chat/completions`, and
`socket` and `authorizationFile` must be distinct safe absolute paths. The
host-pinned runtime file, not a profile or checkpoint, selects these paths.
The caller starts curl through a transient user unit, with proxy use disabled,
the URL fixed and transport restricted to the supplied Unix socket. The
authorization file supplies a curl header; the credential itself is not in
the unit arguments or checkpoint. The local and original HTTPS routes use
distinct request/unit identities so a previous result on one route cannot be
mistaken for a result on the other.

For this local route, each new and previous response must report the configured
model, MTP generation, an installed/enabled draft head and at least one MTP
forward call. Missing or contradictory statistics refuse advancement, even
when the response contains text. Existing HTTPS checkpoints without those
statistics therefore need a separately reviewed migration; they are not
silently admitted as MTP history. Server-reported statistics are evidence of
the request path, not cryptographic proof of the peer or model artifact.

The local response may include extensive server diagnostics. Before it enters
the checkpoint, the resident keeps the complete answer, finish reason, model,
the MTP fields needed for admission, and a SHA-256 digest of the raw response
in a compact JSON record. The raw response remains in the local private cache;
its digest does not make it available after recovery. Empty or over-16-KiB
checkpoint records are refused before advancing state, matching the peer's
per-block limit. This transformation does not approve the answer or authorize
publication of its contents.

The path checks do **not** authenticate the socket peer, verify file ownership
or permissions, prove the model digest, or manage tunnel and service lifetime.
Before enabling this route, an operator must use private owner-controlled
directories, protect the header file, authenticate and pin any SSH tunnel,
qualify the actual model and MTP behavior, and establish bounded memory,
readiness, restart and failure behavior. A socket file or declared model name
is not qualification. The local URL and these fields do not grant a profile
tools, external publication, GitHub writes, or an execution lease. This route
does not qualify the other Itonami profiles or supply GitHub checkpoints.

The local MTP runtime also has an opt-in, model-only
`awai-growth-steward` lane. The host must pin the profile metadata and SOUL,
`profile/tasks/first-turn.md`, and an operator-prepared
`profile/context.md` using the runtime's `profileDigest`, `roleDigest`,
`workDigest`, and `contextDigest`; the last two must be nonempty and at most
8 KiB. It must set `profile` to `awai-growth-steward` and `tools` to `none`.
Both files must be included in the verified checkpoint closure and contain no
secrets. The snapshot is supplied as source material, not live observations. The
model can propose a draft only: metadata capability declarations do not
enable tools, publication, messaging, GitHub writes or scheduling. A pinned
runtime and a completed response do not prove the model fits the host or that
the source snapshot is current. The growth lane requests a concise six-role
handoff and defaults to a 768-token completion limit. An independently pinned
local runtime may set `maxTokens` from 64 to 768. The host must admit sufficient
context and memory for the full prompt plus that bound. The existing replica,
lease and retention
the source snapshot is current. The growth lane has a 768-token completion
limit and requests a concise six-role handoff. The host must admit sufficient
context and memory for the full prompt plus that bound. The existing replica, lease and retention
gates remain required. A 16 GiB Mac mini operator experiment generated a
six-role draft with the full SOUL, work order and source snapshot using the
sustained profile and a 128-block KV setting; the resident, API response
checks, restart and capacity admission have not been qualified for that
workload, and this lane has not been deployed.

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
   request file may enqueue explicitly authorized pending work. A missing file
   preserves the last consumed CID, including across a periodic turn; restoring
   that same request therefore does not enqueue it again. A present request
   must be a canonical JSON object no larger than 8 KiB, otherwise the cycle
   refuses. Only the latest consumed CID is retained: replaying an older CID
   after a different request still needs a durable request history or monotonic
   sequence. Local wall clocks must remain healthy. There is no whole-fleet
   exactly-once inference claim.

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

For the AWAI growth steward, use a separately constructed Git execution cache
containing the pinned profile files, pinned context, source record and tracked
state; do not assume that a full source repository fits this transport. An
isolated fixture with the actual pinned profile and context produced a 3,798-byte
bootstrap Git bundle and a 4,267-byte bundle with one modified state; the
compact real model output was 2,589 bytes as a separate untracked file. A full
bundle of the source revision was 1,308,736 bytes and would produce chunks over
the peer limit. These are fixture measurements, not a native checkpoint,
replication or recovery qualification; future context and state changes must
be checked again.

An additional isolated AWAI-147 test captured that growth-profile fixture with
the native checkpoint executable, including the real compact MTP response as
an allowlisted output. A native peer uploaded the full signed closure over
authenticated loopback into a separate store; the checkpoint verifier and an
independent checkout restored from that store, and the profile, context,
state and output matched byte for byte. A wrong profile pin and a corrupted
output block were refused before their destination directories were created.
Both peer processes and stores were on the same Linux host with test-only
credentials: this does not establish independent failure domains, retention
receipts, GitHub persistence, or a resident inference/lease cycle.

A subsequent isolated drill transferred the same signed fixture from the K16
Linux host to the separately identified `aiueos-6600hs` Linux host through
Tailscale. Each host fsynced the closure and signed a two-minute test retention
receipt using a different public test key and fixture domain; the native pair
verifier returned `retention/pair-verified`. On the second host, native
verification and checkout restoration succeeded, and SHA-256 comparisons of
the profile, context, state and output matched the first host. A wrong token
was rejected. The temporary peer listener was stopped and its random token
cleared after the drill; fixture objects were retained. This demonstrates the
selected host-to-host path, not sustained retention, Mac mini deployment,
GitHub persistence, a production identity, or a resident inference/lease cycle.

## Optional GitHub archive barrier

When the trusted host config includes an absolute `archiveAck` path, a
replica-admitted head must also have a matching, unexpired local GitHub archive
acknowledgement before the resident starts the next turn. The current
GitHub-enabled lane is the read-only local-MTP `awai-growth-steward` profile;
other profiles refuse this mode until they have an independently reviewed
projection and runtime contract. Missing, malformed,
acknowledgement before the resident starts the next turn. Missing, malformed,
mismatched or expired acknowledgement yields `resident/archive-pending`.
A present but empty, non-string, unsafe or colliding configuration path is
refused. Omitting `archiveAck` preserves the earlier behavior. The ack path and
its parent must be private and writable only by the trusted host components;
this local file is not a signed receipt or a distributed lease.

`profile-resident archive CONFIG` describes a head only after checking the
pinned profile, policy and environment, checkpoint signature and closure,
and both current signed retention receipts. The companion
`scripts/itonami-resident-github-archive.sh RESIDENT_BIN CONFIG CHECKPOINT_BIN WORKROOT OWNER/REPO`
passes that descriptor to the GitHub archive adapter. After independently
fetching and verifying the archived checkpoint, it writes an atomic fsynced
ack for the same root and tree, expiring in 15 minutes. For this mode the
host additionally pins `runtimeFile` and its raw CID in `runtime`, together
with `modelArtifactDigest` and `mtpArtifactDigest`. `runtimeFile` holds the
local socket and authorization-file paths; it is never the checkpoint
`environmentFile`. Instead, `environmentFile` must be canonical JSON with
exactly these 14 fields: `kind` (`itonami.profile-environment-public/2`),
`maxTokens` (an integer from 64 to 768), `profile`, `profileDigest`, `roleDigest`, `workDigest`, `contextDigest`,
`source`, `task`, `model`, `rail`, `tools`, `modelArtifactDigest`, and
`mtpArtifactDigest`. Shared fields must match the independently pinned local
runtime, including the same `maxTokens`, and the two artifact digests must match
the host config. The earlier 13-field `/1` experimental projection requires a
new signed checkpoint; it is not silently accepted as `/2`. The public
descriptor is checkpointed and archived; runtime paths and credentials are
not part of it. These declared artifact digests do not prove what a socket
peer actually loaded. The bundle, profile context and output also need
explicit data classification before archival; the projection is not a general
secret scanner.

Run the provided
ack for the same root and tree, expiring in 15 minutes. Run the provided
`itonami-profile-archive.service.tmpl` and `.timer.tmpl` every five minutes
on a separately provisioned trusted Linux host. The archive script, native
binaries, config, task directory, explicit repository and GitHub credentials
must be pinned and provisioned by that host. Templates do not enable services.
A failure does not refresh the acknowledgement; once it expires the next
turn remains pending. A valid acknowledgement records a recent readback, not a
guarantee of future GitHub availability or that an ongoing turn can be
cancelled. Changing the head makes the prior acknowledgement inapplicable.

An isolated integration test captured a new signed checkpoint with the public
projection while keeping the local MTP runtime separate, verified two test
retention receipts, and archived it to the private GitHub repository. The
GitHub readback contained the 13-field projection without endpoint, socket or
authorization-file fields. Changed runtime bytes and an added socket field in
the projection both refused. This test did not start model inference.

A later integrated resident attempt restored that checkpoint and started the
next local-MTP proposal. With a 3,072-token context and a 768-token completion
limit, the Mac mini memory-pressure monitor stopped the model at 26% free
(the configured stop threshold was 27%); the resident retained its input head.
A separate 2,048-context diagnostic using the previous proposal as input
reached MTP but exhausted a 160-token output limit and was refused as
incomplete. It bottomed at 27% free. See
[capacity evidence](evidence/awai147-resident-mtp-memory-20261103.json).
A compacted operator-prepared work order and snapshot retained the original
SOUL and preceding proposal; a direct 2,048-context diagnostic used 1,673
prompt tokens and completed in 278 of a 360-token cap, reporting six role IDs,
121 MTP forwards and 99 accepted drafts. Its minimum observed free pressure
was 28%. The host can now pin `maxTokens` for this profile, but this diagnostic
was not a resident turn. These measurements do not establish a safe production
admission limit or justify accepting truncated proposals. The earlier resident
binary also lacked the later interrupted-output retry fix; the combined native
gate was subsequently rebuilt using the exact current source.

A subsequent one-turn integration used that current source, the compact pinned
work/context, a 360-token bound, and the actual Mac mini MTP service. The
resident completed step 2, rechecked the lease, replicated and published its
successor, waited for GitHub archival, and then remained idle on two subsequent
invocations. The model reported 1,673 prompt / 278 completion tokens, 121 MTP
forwards and 99 accepted drafts; the minimum observed free pressure was 28%.
The resulting private GitHub archive was fetched by immutable commit and
restored on another Linux host; its state, output and pinned profile files
matched the producer. [Evidence](evidence/awai147-resident-mtp-github-turn-20261103.json).
The test used public test keys and simulated all voters and replica signers on
one host; it does not qualify independent failure domains or continuous
operation. The second host verified only cold restoration. No inference service,
timer or production identity was left enabled.

A separate [multi-host replication and quorum drill](evidence/awai147-dist-quorum-replication-20261103.json)
used three Linux voter hosts and two host-local keys generated independently.
The remote replica verified and restored the checkpoint and returned its block
after a peer restart. The lease quorum continued with one voter stopped,
refused with two stopped, and recovered after one restarted with persisted
state. The same checkpoint was archived to private GitHub. The topology used a
public test operator key, the outages were process stops rather than host
power cycles, and no inference was dispatched during this drill. Promised
blocks and private test keys were retained; test listeners and transport
tokens were removed. This is not production membership or a 10-Mac failover
qualification.

An isolated test with the real experimental MTP checkpoint, two distinct
public test signers and freshly signed retention receipts observed pending
before archival, verified the actual private GitHub archive, and observed idle
after the acknowledgement. Expired acknowledgement and simulated GitHub
failure returned to pending; an Amu-built mutant that removed the barrier
incorrectly returned idle. The test used both test signers on one host and
an ephemeral SSH HTTPS relay, so it does not qualify independent failure
domains, persistent networking, real identities, inference scheduling or
production deployment. See `test-native/murakumo/resident-github-barrier-gate.sh`
for the isolated negative checks.
For the AWAI growth steward, use a separately constructed Git execution cache
containing the pinned profile files, pinned context, source record and tracked
state; do not assume that a full source repository fits this transport. An
isolated fixture with the actual pinned profile and context produced a 3,798-byte
bootstrap Git bundle and a 4,267-byte bundle with one modified state; the
compact real model output was 2,589 bytes as a separate untracked file. A full
bundle of the source revision was 1,308,736 bytes and would produce chunks over
the peer limit. These are fixture measurements, not a native checkpoint,
replication or recovery qualification; future context and state changes must
be checked again.

An additional isolated AWAI-147 test captured that growth-profile fixture with
the native checkpoint executable, including the real compact MTP response as
an allowlisted output. A native peer uploaded the full signed closure over
authenticated loopback into a separate store; the checkpoint verifier and an
independent checkout restored from that store, and the profile, context,
state and output matched byte for byte. A wrong profile pin and a corrupted
output block were refused before their destination directories were created.
Both peer processes and stores were on the same Linux host with test-only
credentials: this does not establish independent failure domains, retention
receipts, GitHub persistence, or a resident inference/lease cycle.

A subsequent isolated drill transferred the same signed fixture from the K16
Linux host to the separately identified `aiueos-6600hs` Linux host through
Tailscale. Each host fsynced the closure and signed a two-minute test retention
receipt using a different public test key and fixture domain; the native pair
verifier returned `retention/pair-verified`. On the second host, native
verification and checkout restoration succeeded, and SHA-256 comparisons of
the profile, context, state and output matched the first host. A wrong token
was rejected. The temporary peer listener was stopped and its random token
cleared after the drill; fixture objects were retained. This demonstrates the
selected host-to-host path, not sustained retention, Mac mini deployment,
GitHub persistence, a production identity, or a resident inference/lease cycle.

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

For the AWAI-147 MTP change, an ARM64 Linux native build composed this entire
resident closure, the checkpoint and resident regression gates, and the MTP
tests without parser or cryptographic stubs. The combined gate passed; removing
the production post-inference retention deadline guard made it fail. A separate
native gate parsed the actual 17,275-byte MTP response and generated a
2,589-byte compact record; independent JSON parsing checked the full answer,
model, finish, MTP fields and raw-response hash. A wrong-model control failed.
These gates do not run a resident cycle or prove a network replica or restart.

The pinned dependency owners were unavailable directly during this test. The
source bytes were taken from `kotoba-lang/kotoba-parent` commit
`d50f87be086e7dd82e828df03aacb580c4115b0c` and its prior Ed25519 import
commit `5ce9dac6c`; the parent project's import manifests identify the pinned
revisions in the build recipe and the retrieved files match the manifests'
SHA-256 values. This is evidence from the parent import records, not an
independent fetch from the original repositories.

An operator-run AWAI-147 experiment subsequently executed the actual native
`profile-recovery step` with the growth profile, work order and context pinned
by the runtime, a verified SSH Unix-socket tunnel, and a memory-guarded MTP
service on an M4 Mac mini. The step accepted a `stop` response with 342 MTP
forward calls and 248 accepted drafts, wrote a 2,990-byte compact output, and
advanced fixture state from 1 to 2 with a matching output CID. The monitor's
minimum free-memory reading was 27%; the API reported zero paged KV blocks
despite a 128-block setting. The temporary model service, tunnel and unit
were stopped after the turn. This exercises the real step and parser, but not
the resident lease, automatic checkpoint replication, restart, GitHub state
adapter or other profiles. The manually recorded private GitHub draft is not
an autonomous checkpoint, and the generated claims require verification.

The resulting real profile output and state were then captured and restored by
the native checkpoint executable. Repeating step 1 was refused because state
had advanced; changing the previous output made step 2 refuse before dispatch.
For a reviewable GitHub export, a second checkpoint used a sanitized,
non-runnable environment descriptor without socket or credential paths. Its
eight CID blocks and verification manifest were manually committed to a private
experimental branch, fetched from an immutable GitHub commit, and restored by
the native verifier into a fresh test directory. The restored profile, context,
state and output matched the original bytes. The signer was a public test key;
this export is not an accepted production head, an automated GitHub adapter, or
a substitute for fresh runtime provisioning and lease admission.

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
