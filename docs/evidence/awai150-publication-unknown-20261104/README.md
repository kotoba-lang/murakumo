# Profile state publication acknowledgement

`pr-step-range` previously returned `profile/state-publication-failed` when
its final rename or repository-directory sync did not report success. A failed
or lost acknowledgement can leave the new state visible, so the return is now
`profile/state-publication-unknown`. The host must reconcile durable state and
must not infer permission to dispatch another request from this result.

A local signed native source-unity diagnostic used the current `pr-step-range`
with stubbed filesystem, transport, runtime validation and submission. It
injected a failed final sync *after* a simulated successful rename; success
returned a completed result and the injected failure returned unknown. Replacing
this final result with the previous `failed` value made the same fixture fail.
The source/image SHA-256 values were:

- corrected: `bfbd5bfb76c13bf526cdbb9019938cc972843d69663bf73bef225fdc622a06c6` / `2a26b608c180643ab348b615010d8150fd870d2ae2400770b552d7d35989aac0`
- previous-result control: `ee2728e69f2234e918fe5bfb9807fe01ef643f383c7f6fddddc2b9652d64db99` / `504fd177281cb34f8f132a85b34dde60a125b85465834a049d43ecaa8ce7f916`

Each native process had a three-second CPU cap. This is an injected branch
exercise, not a real rename, fsync failure, crash, power loss, full source-lock
build, retry policy, supervisor, or proof of recovery after an ambiguous write.
In particular, other runtime kinds still require a trusted host to serialize
and reconcile state before another inference.

## Candidate for an already-visible state

`profile-recovery-main reconcile-published REPO RUNTIME CACHE OLD-STATE REQUEST-ID COUNT-ID`
adds a separate reconciliation path for a Flint turn when rename made the new
state visible. The exclusive trusted host must supply the exact prior state and
retain the same authenticated runtime/instance, cache, and systemd unit; it
must prevent aliases, concurrent writers and independent server users. The
candidate does not infer these facts from caller arguments. It verifies the
request's identity, matching intent and dispatch marker, completed unit,
validated response, compact output and expected current state. A leftover
`state.json.tmp`, missing/mismatched evidence, or failed sync refuses. It syncs
the evidence, output and state before attempting the existing matching busy
marker release. Failed or ambiguous release still returns unknown. It never
starts an inference or rewrites the profile state. If the release happened but
its acknowledgement was lost, separate reconciliation remains necessary.

A native source-unity fixture called the actual new function with stubbed
filesystem, systemd and release operations. A matching synthetic case returned
reconciled; mismatched current state, request ID, count ID and failed sync
returned unknown. Removing the current-state comparison was detected by the
negative control. The positive and control source/image SHA-256 values were
`c156ca5d8cad52e96849f831c53069e9ae60c9e092de4ddc4bc07457e66bc6b2` /
`3e8a9c76989d6db60695c4eddf36ff5bfc6e41d16ed7a78bd361b3c1ad65392f` and
`c45c29fd627fa65e5cf87feca4d65c0426f64d04b47b41bda55d65c390aeb3d2` /
`67938449879a8eb3da506efbd3f4565dd4d530d0b1b67fe82cd408c2455fe2db`.
The actual command entry and candidate passed a selected source-unity Amu
check. This is not a real filesystem or systemd recovery, authenticated process,
crash test, qualified native provider, source-locked build or live bot run.

## Local native FS2 exercise

A follow-up on one local Apple Silicon Mac used the actual current reconciliation,
response validation, turn intent and busy-marker functions with the pinned
Darwin FS2 diagnostic provider. It created separate task-owned directories and
synthetic requests, responses, outputs and state. The `pr-unit-state` function
alone was replaced with a test stub reporting `exited`; there was no systemd
unit, network request or inference. Each native process had a three-second CPU
cap.

In the matching case the operation returned `profile/publication-reconciled`;
an independent filesystem check found no busy marker and one `.last` marker.
Four separate cases with a mismatched state, an existing `state.json.tmp`, a
missing response, or a mismatched count ID each returned
`profile/state-publication-unknown` and retained the busy marker without a
`.last` marker. Source/image SHA-256:
`935cf3f1ef93843788b073da24857b7658f4233af943da2fa10c4c0635ac42b8` /
`f119560e61989302c7c69537f0a1c2aa3e3262876c894848c526cf17795128ba`.
Compiler/provider pins are the diagnostic pins in the preceding evidence.
The test does not exercise real systemd, production credentials, lock
ownership, response loss after marker rename, provider I/O errors, a crash,
power loss or the full source-locked application. Its synthetic directories
are retained for inspection; no user workload or service was changed.
