# Reconcile a completed but unpublished profile turn

Under the task's existing single-writer lock, a pending `state.json.tmp` may
be published without another inference dispatch only when the exact request,
dispatch marker, exited unit, completed response, saved output, original
state and candidate state all agree. The candidate synchronizes the output,
output directory, temporary state and repository directory before rename,
and synchronizes the repository after rename. A missing, unreadable,
inconsistent or not-yet-exited input, or a failed operation, remains unknown.
No unknown outcome may be retried as a new inference submission. The provider
must distinguish a lookup error from a confirmed absence.

On 2026-11-03 an unprivileged user ran the attached Kotoba fixtures as native
ARM64 Linux programs in a 1 CPU / 2 GiB VM. The helper fixture used real scoped
FS2 existence, reads, fsync and rename, but substituted string identity for
CID, a tiny response parser and a synthetic unit state. The integrated step
fixture used the current `pr-step-range`, real FS2 existence/writes and
synthetic identity, model, request and unit functions; `ENTERED-DISPATCH`
marked its stub dispatch decision. Neither fixture invoked a real model or
unit manager.

The helper published the matching case. Six controls with a different original
state, candidate state, request, marker, response or output each refused and
left both files in place; an active unit also refused. The step fixture refused
before dispatch for a pending or unreadable temporary file with insufficient
evidence and proceeded through the stub decision when the file was absent.
One initial observer assertion incorrectly expected the unmodified original
state in the deliberately modified-state case; the corrected observer reran
all seven cases and checked the actual pre-test bytes.

This is a candidate, not a qualified component: the fixtures do not exercise
cryptographic CIDs, a real response, concurrent writers, a restarted or
missing unit, power loss, rename/fsync fault injection, Mac runtime, GitHub or
distributed lease authority. In particular, a disappeared unit cannot be
proven exited and is left for host reconciliation. A rename followed by an
unknown directory-sync result also needs host reconciliation. The compiler is
the byte-patched diagnostic Amu #1796 artifact, not a release build.

## Follow-up: real CID and unit status

Two additional native Linux ARM64 fixtures composed the current reconciliation
function with real Kotoba SHA-256, CID, JSON parsing, MTP response validation,
compact-response creation and scoped FS2. The response was synthetic, including
its claimed MTP stats. With a stub unit status, the matching files published;
six independently changed state, candidate, request, marker, response and
output cases, plus a running-unit case, refused and retained the pre-test state
and temp file.

The second fixture also used the production `pr-unit-state` via the real Amu
spawn capability and `systemctl --user` (systemd 255). An isolated test-owned
oneshot unit with `RemainAfterExit=yes` allowed reconciliation after reaching
`exited`. A running test-owned sleep unit and a nonexistent unit both refused.
Each test unit was stopped and `LoadState=not-found, ActiveState=inactive` was
verified. An initial observer using `systemd-run --wait` timed out because the
unit remained active after exit; its unit was stopped and the successful rerun
waited for the expected `SubState` directly.

These results qualify only the composed test artifacts on that Linux VM. The
model response, request and task were synthetic; this is not an integrated
resident turn, Mac runtime test, real model or GitHub recovery. Power loss,
concurrency, state persistence, process-tree and fault injection remain open.

The source SHA-256 is
`e27335e7f421657c2872533816a3080ab4f0b269d4b897aa81f5dba640e9ed21`;
the diagnostic compiler SHA-256 is
`4a42e07083beb87337ad56dbf9759890e48d4502154e361d54cfee0b71e89da9`.

| Fixture | Source SHA-256 | Executed ELF SHA-256 |
| --- | --- | --- |
| reconcile | `ee97397b7f305764e011f9f5c8b7786e288e488abc2fc54ec01ece2e3fac30d7` | `cf0cc10453f01245996e0160253c1cda2196ad6e9729a154da8cc9f38990844a` |
| step | `5a7f57f324321c4f8ac80c04489ebffbee75ff2feebfbd88f2e3026da38c511a` | `ddbda3de78f14c3fb3ac3588ee9115d9f042679fdbf89ae52fd5b9ac34ea7aa3` |
| CID and JSON | `a4963c3078eef61fd6a8093525bdff0693c7ae03b5cfefae8741f86a923f3808` | `bedb2d7ac213e0ebbe65d119d484cb61e68590a077c0aec27bce9e8c4b3b08c0` |
| systemd status | `ba53be3f39aa079957f723415f4bbbabb5beea3a7a70d0fc1490b4aaa9b29fa0` | `094917bb9b591da9e959972021fef049fb1b35eb7fea37046e5dce0c01bd06f8` |
