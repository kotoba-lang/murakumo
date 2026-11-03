# Native fleet observer failure classification

The diagnostic observer now reports the failed fixed probe and a bounded host
exit classification, without including remote stderr or partial observation
bytes. Malformed, noncanonical or oversized host replies remain unknown; an
SSH exit code is not interpreted as an authenticated remote completion. Unit
fixtures checked canonical 124 and 255 statuses, malformed status, missing
marker, terminal control bytes and the success case on the local Mac.

Following the prior ten-host observation, a fresh diagnostic native fixture
returned success on Benjamin, `memory transport-exit-255` on Asher, and
`pressure transport-exit-255` on Simeon. A separate minimal, read-only
`/usr/bin/true` SSH connection with the same identity and pinned host keys then
succeeded on both. A further fresh task directory produced failures on Asher
pressure and Simeon memory, both exit 255. In a separately instrumented fresh
task directory, Asher failed swap with exit 124 and no captured stderr, while
Simeon failed memory with exit 255 and nonempty stderr; the adapter did not
retain a diagnostic stderr category. These outcomes show intermittent failures
at different stages, not their underlying cause. Exit 124 may be the diagnostic
host's five-second child timer, but is not by itself proof of timeout. No
partial metric was reported by the native entry point.

One attempted rerun reused capture paths from an earlier run; the diagnostic
host's exclusive creation refused locally before SSH. A new task directory was
then used. No production configuration, service or model was modified.

The final instrumented fixture source SHA-256 was
`1c3bfbd0f23c8c299ea3c2866d4d102c3d75c7da426c5af02d60733e0cb68a17`,
its signed image SHA-256 was
`e75af0bf8513d3293b0a45bef446b18745369fedcc21095b5919e25e2ddd0472`,
and the privileged diagnostic host packager was
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
The host is not a production capability boundary; telemetry and the fixed
in-band completion marker are not authenticated. A separate transport and
resource-governor qualification is still required.
