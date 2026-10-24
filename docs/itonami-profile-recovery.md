# Actual Itonami profile recovery

Tracked in [JUN-156](https://linear.app/kotobalabs/issue/JUN-156), alongside
[JUN-289](https://linear.app/kotobalabs/issue/JUN-289). This builds on the
[portable Git checkpoint](git-checkpoint.md) merged in PR #540.

`kotoba/profile_recovery.kotoba` runs a bounded read-only model turn with the
actual `accountability-steward` Itonami profile, and resumes its second turn
automatically after loss of a task-owned producer process. The immutable
profile and full SOUL come from `kotoba-lang/kotoba` revision
`bd97e7183463c6dd36032ca913fa70fb4a3ec068`, under
`hermes/profiles/accountability-steward/`. That source directory uses the
shared profile format. Execution uses Itonami's `itonami.edn` Murakumo rail;
it does not use the Hermes CLI, its store, config.yaml or OpenRouter.

The adapter deliberately admits this one reviewed profile shape. Raw profile
and SOUL hashes must match independently provisioned runtime pins. The checks
for capability/rail/authority declaration fragments are supplementary checks
on those reviewed bytes, **not a generic EDN parser or authority evaluator**.
Profile instructions are data. No model response is dispatched to a tool,
and the profile's legacy cron/growth scripts are not executed. This qualifies
actual profile inference continuity, not its entire scheduled workflow.

## State and execution

The task owns a small independent Git execution cache:

```text
profile/itonami.edn  # exact source bytes
profile/SOUL.md     # exact source bytes
source.json         # original repository, revision and profile path
state.json          # canonical {next,previous,task}
outputs/1.json      # complete actual inference response, explicitly allowlisted
outputs/2.json      # response after automatic recovery
```

The initial state is `{"next":1,"previous":"none","task":"TASK"}`.
Step 1 writes its response and advances state to 2 with its raw response CID.
Step 2 verifies that CID, supplies the prior assistant content and full SOUL
to the model, and advances state to 3. Steps outside 1..2 and replays are
refused. Output files are fsynced before publishing state with a temporary
file, rename and parent fsync. This is an exclusively owned, quiesced task
directory; it has no concurrent-writer locking or exactly-once guarantee.
An interrupted publication may leave an output requiring operator recovery.

The canonical runtime descriptor contains `kind` =
`itonami.readonly-profile-runtime/1`, `profile` = `accountability-steward`,
`rail` = `murakumo`, `endpoint` =
`https://api.murakumo.cloud/v1/chat/completions`, `profileDigest` and
`roleDigest` as `sha256:...`, `source` as the original 40-character revision,
and `model` and `task`. Before the drill the operator selected a ready chat
model from the declared service's `/v1/models` catalog. The observed model
alias is in evidence; no actual model identifier is hardcoded in the adapter.

HTTPS transport is a separately granted, fixed curl oneshot in the existing
Linux user service manager. `systemd-run --user --no-block` registers a job
named from the request hash; native code polls that job. This accommodates
full-profile prefill beyond native process/spawn's 10-second child budget.
Curl ignores curlrc, follows no redirects, posts only to the fixed declared
HTTPS endpoint, has a 5-second connection and 840-second total timeout, and
receives no application credential arguments. The oneshot has a 900-second
start timeout, NoNewPrivileges, 128 MiB MemoryMax and 25% CPUQuota.
The trusted user manager is an explicit host provider with its own environment
and authority; this is not a new native HTTPS implementation or OS sandbox
for the manager's child. A failed job or empty/invalid response retains state.

## Build and run

Use the same pinned Amu root and native dependency closure as
[git-checkpoint.md](git-checkpoint.md#native-build-and-gate). Append
`profile_recovery.kotoba` after `git_checkpoint.kotoba`, remove each module's
namespace header during composition, and select
`(defn main [] :i64 (profile-recovery-main))`. Compile loaderless
`aarch64-linux` and `x86_64-linux` static artifacts and check their digests
before transfer/execution. No new JVM/nbb/Node/Python product runtime or
workflow is introduced; repository CDCI remains stopped as documented.

```text
profile-recovery step REPO RUNTIME_FILE CACHE NEXT
profile-recovery hold IPV4:PORT
profile-recovery watch PEER STORE ROOT_CID CONTRACT_FILE DEST CACHE [MAX_POLLS]
```

Create the task cache/store directories first. `step` needs filesystem and
argv/stdout grants (35/37/38), and external-process grant 20 for
`/usr/bin/mkdir`, `/usr/bin/systemd-run`, `/usr/bin/systemctl`, `/usr/bin/sleep`.
The manager separately executes `/usr/bin/curl`. Restore additionally needs
the Git/checkpoint tools and controlled Git environment from the checkpoint
runbook. Pass only `PATH`, `XDG_RUNTIME_DIR`, `DBUS_SESSION_BUS_ADDRESS`,
`GIT_CONFIG_GLOBAL`, `GIT_CONFIG_NOSYSTEM` through `KEXE_SPAWN_ENV`.
`hold` needs 37/38/43/44/47 and the exact `KEXE_CAP_RESOURCES_43` listen
address. `watch` additionally needs 45/46/47 and the exact
`KEXE_CAP_RESOURCES_45` peer address. These grants come from the authorized
host launcher, never from profile text or past approvals in a checkpoint.

After step 1, capture the quiesced execution cache, selecting `outputs/1.json`
as the untracked allowlist. Supply the real `itonami.edn` as the profile,
the canonical read-only policy as policy, and runtime descriptor as environment.
Provision the receiver's independent contract before watching. It contains
`author`, `profile` digest, `policy` CID, `environment` CID, `epoch`, `parent`,
`task`, and `next` = 2. Transfer immutable objects and verify the replica before
starting the producer heartbeat. Initial provisioning/replication is explicit;
this adapter does not implement discovery or background replica repair.

The heartbeat sends the exact six bytes `alive\n`. Watch handles split reads,
must observe a live producer first, and requires two consecutive misses.
It then verifies the independently pinned signed checkpoint/closure, creates
an independent destination, and runs step 2. The optional poll limit is 1..1800
(default 1800); it counts polls, not elapsed seconds. Waiting/heartbeat
allocations are reclaimed in arena scopes. Verdict output can be buffered;
verify the transport before fault injection and read the completed log.

The drill used loopback ports transported through existing verified SSH links.
Only the task-owned producer unit was killed. No production service, profile
constitution, gateway or application route was changed. After completion,
stop the exact task units and SSH forwarding sessions, retaining the checkpoint
and receipt evidence. Do not stop units by an unverified broad wildcard.

## Qualification and remaining admission work

The native gate in `profile_recovery_test.kotoba` covers UTF-8 JSON encoding,
runtime pins, changed profile/SOUL, empty model and forbidden endpoint. Compose
it after the production source and select `test-profile-recovery` as main.
Its process result is **15**. Removing the SOUL digest check gives **7**.
Integration negatives deny process/network inference grants and still return
the expected refusal for tampered profile, prior output and replayed state.
A never-observed peer expires without materializing a destination.

The [two-host evidence](evidence/itonami-profile-recovery-20261024.json)
records real inference, byte/digest continuity and the successor receipt.
A was native arm64 Linux in the development Mac's existing VM; B was a
separate physical x86_64 Linux machine. Fault injection was producer process
loss, not a physical-host power failure. Checkpoint signing used RFC8032
public fixture keys; it did not exercise production node credentials.

Heartbeat loss is a liveness hint, not a distributed lease/fence. Read-only
inference can run at least once on each side under partition. Enabling tools,
cron or authoritative writes requires JUN-289 dispatch/lease/delegation and
accepted-head admission first. Persistent encrypted replica receipts,
repair/eviction, Kagami/Nekko admission and chain-anchored recovery remain
JUN-156/AWAI-34 work. A local signed receipt does not establish chain finality.
