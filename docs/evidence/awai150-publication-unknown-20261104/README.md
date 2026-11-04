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
