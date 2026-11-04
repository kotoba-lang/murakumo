# Native capture profile read faults

The local Git diagnostic now supports `--fault-profile-read empty|short`,
which instruments the fixture's `cp-read-op` to return an empty or one-byte
vector for an `E` range read of the nonempty synthetic profile file. The real
FS2 provider and application source are unchanged. Each private fixture runs
actual current native checkpoint capture and requires a non-success result
without `checkpoint/created`; the tracked, staged and listed untracked source
snapshot is also compared before and after.

On one Apple Silicon Mac, both injected faults were refused. A separately
built `--fault-profile-read short --mutate-profile-validation` fixture disabled
only the synthetic profile read validation and did report a created checkpoint,
showing that the test detects this guard bypass. That mutant's signed checkpoint
was confined to a disposable local fixture and is not a valid production
checkpoint. The uninstrumented local bare-Git capture, archive, verify, restore
and recovery diagnostic passed again after adding the test options.

The runner SHA-256 was
`a4561bf894e3a402c62a5b14b441f4464e3feb1d80d348ed0d87d8a725f93555`;
checkpoint source SHA-256 was
`55810bc2db201e8c82bd80f267db5de1f2f53b5531c18618b7b75e09bafc57a8`.
The diagnostic compiler was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` and
the privileged test-only Git packager was
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.

This instruments the application read boundary; it is not an injected FS2
provider error or a real kernel I/O failure. It does not cover empty-file
framing, source mutation races, bundle chunks, materialization failures,
power loss, real GitHub, production isolation, selfhost or the governor.
