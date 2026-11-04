# Kapitsa startup and watcher loss under FIFO hold

On 2026-11-05 the task-only Python coordinator exercised the native RGQ4 FIFO
candidate, WP04 watcher and the fixed-profile Kapitsa startup launcher from
Amu. On naphtali, benjamin and judah (macOS 26.7.2 arm64, build 25G332),
strict host-key SSH transferred signed, pinned test artifacts. A newly created
mode-0700 model directory contained only a one-byte invalid configuration and
no weights. The sandbox allowed default operations except process-fork. The
operator verified the stopped executable path, but did not atomically bind it
to the checked binary.

For each run the coordinator queued two synthetic tickets and reserved the
first, waited for WP04 readiness and a heartbeat while Kapitsa was stopped,
and verified the second ticket and front/dequeue were refused. Two separate
scenarios were performed on all three hosts:

* Resume: Kapitsa rejected invalid config and exited 2; WP04 returned the
  matching raw status 512. The FIFO remained held until explicit test-only
  reconciliation after the coordinator reaped the direct child.
* Watcher loss: kill and reap the watcher while Kapitsa is still stopped;
  confirm the child remains alive and FIFO front remains held; kill and reap
  the direct child, then perform the same explicit test-only reconciliation.

The task model directory and SSH staging directory were observed absent after
each completed run. The launcher was fixed to loopback/private/local, but
these cases do not establish a working server or actual inference. Scripts in
this directory are the exact test-only observers and wrappers, retained as
experiment provenance. They reference temporary, locally built signed
artifacts and nonce files and are not general fleet or deployment commands.

Artifact SHA-256: FIFO
`e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7`, WP04
`ad32993a07f5e8b3db5d46f695c9d1a2b319974f145f13ebe3406c23810eba6e`,
installed Kapitsa
`20db8dadcb79de820d237367ca93d7ac66f8a70d91d5513f6e1ab77253925daa`.
The profile-specific launcher hashes are pinned in each wrapper. Amu local
commit `0d8dc988` describes its generator and constraints.

This does not test the production native supervisor, operator death,
descendant processes, daemonization, crash recovery after a lost release,
resource admission or enforced memory/GPU limits. The FIFO reservation in
these tests is synthetic and reconciliation is manual test behavior; it does
not establish a qualified governor or a real Itonami bot/GitHub state turn.
