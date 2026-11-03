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

## Later transport investigation

A later fresh diagnostic run captured and classified the SSH stderr locally.
Asher's pressure probe and Simeon's memory probe returned 255 with SSH reporting
port 22 connection timeouts. This identifies the immediate failure in those
two attempts; it does not establish why packets or the server were delayed.
No credentials were printed. Tailscale discovery probes reached both nodes,
first over DERP or a direct route; ICMP and TSMP probes also returned replies.
Those replies alone do not prove the SSH service or path is continuously
available. The Benjamin control also replied.

After a single successful TSMP preflight per node, a fresh observer still
failed: Asher at swap and Simeon at memory, each with exit 124 from the
diagnostic host. To test a longer deadline without changing a service or
production runtime, a separate task-only host increased its mode-0 child
timer from five to fifteen seconds, and the fixture increased SSH
`ConnectTimeout` from 3 to 10 seconds and `ServerAliveInterval` from 1 to 3.
In this one comparison Asher got through memory, swap and pressure, then its
disk connection timed out; Simeon's memory connection timed out. Both SSH
processes returned 255 and the stderr named port 22 timeout. This small sample
does not establish a general timeout recommendation or the root cause.

The bounded stderr diagnostic fixture source SHA-256 was
`221e7334f9bbe41f833026275422fecfbf76b909afcda7b22ffc52f22e6f75f5`
and signed image SHA-256 was
`2b6bf0bac6ba05b42575949d7b4cb9e7a63093d1c137f9dcede2aa3e424b40d8`.
The longer-timer diagnostic host source SHA-256 was
`bcd15a1efcdcafaac646c43286864ada2684e104a2e5f05332849c9be8435c0b`
and signed host SHA-256 was
`dc4b2503386c418f8a3b60837df231fef37a8ecea226ef5b3d6aad2d7934ce5a`.
The longer-timeout observer fixture source SHA-256 was
`de8742b6aad287240c075582dfccf872f6583d8c09eb044aea02b90e5a544c8a`
and signed image SHA-256 was
`25efd163266a363332b7ed2bee4dfcc06a44c9dcbe46b14dc881bf1d451ab7ca`.
Only diagnostic copies were changed; existing production timeouts and services
were untouched. No workload or token was used.

## Subsequent reachability sample

Around 05:56–05:57 UTC on 2026-11-03, Tailscale reported both nodes online,
but a single 3-second Tailscale discovery ping and a separate 3-second TSMP
ping to Asher timed out. A strict known-hosts SSH `/usr/bin/true` attempt to
Asher's Tailscale IP also timed out at port 22 after five seconds. This is an
overlay reachability failure in that sample, not proof of an SSH-only problem
or a diagnosis of the peer's physical state.

Simeon answered both Tailscale discovery and TSMP and one strict known-hosts
SSH `/usr/bin/true` succeeded. A subsequent fresh native observer, using the
same task-only 15-second host and 10-second SSH connect bounds described above,
refused its first memory probe with exit 255 and SSH port 22 timeout. The
initial manual test omitted the required `HostKeyAlias` and was correctly
refused before authentication; the successful retry used the same pinned
alias and key as the observer. These isolated observations do not establish
availability rates, root cause or available capacity. No service was changed.

The fresh observer fixture source SHA-256 was
`9c5b225f2c76c69b8b787bfce35e9244a1afa0967d73919688863195e566df7d`
and signed image SHA-256 was
`56e85f977a0eecf24ad0f2e94f0d5ef3f1f51ef34cf507aa9e9866799497a921`.
