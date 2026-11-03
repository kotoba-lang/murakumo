# Native SSH observer across ten Mac minis

On 2026-11-03 approximately 05:30–05:31 UTC, the native observer entry point
ran sequentially against the ten named Tailscale peers with an explicitly
selected existing SSH identity and per-node known-host key. It requested the
same four fixed, read-only commands on each host. The source-unity fixture used
a task-local privileged diagnostic process adapter; its child timer was five
seconds per SSH call. It did not read or copy the private key contents.

Eight hosts returned all four observations; each reported 16 GiB physical RAM.
The following values are untrusted, point-in-time host reports and were not
used as allocation decisions.

| Node | Pressure free % | Swap used | Disk available KiB |
|---|---:|---:|---:|
| Benjamin | 91 | 0 MiB | 60,980,660 |
| Dan | 88 | 133.25 MiB | 123,409,100 |
| Issachar | 90 | 0 MiB | 131,762,944 |
| Joseph | 19 | 3,690.25 MiB | 70,531,968 |
| Judah | 91 | 0 MiB | 78,483,156 |
| Levi | 87 | 0 MiB | 94,446,420 |
| Naphtali | 90 | 0 MiB | 117,198,120 |
| Zebulun | 88 | 1,080.44 MiB | 64,550,152 |

Asher was refused on its first probe, and Simeon on its fourth; the relevant
capture files were empty. The observer returned no partial output for either.
The diagnostic adapter does not distinguish a timeout from other SSH failures,
so the cause is unknown. No retry was made. The ten readings were sequential,
not an atomic snapshot. No service, model or bot was started or stopped.

The native unit fixture also passed its invalid input and response cases,
including rejection of terminal control bytes. Fixture source SHA-256
`e26e5d2a5e691b0a79a505c6656273813d74c1f934022cb4496e104db4d3c04a`;
signed diagnostic fixture
`31886bbf63559852b1dff3fd49bf89aca53d6dfb155a646a11a52162ef27381e`;
host packager
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.

This is not production capability qualification, secure peer identity beyond
the host-key configuration, a resource reservation, continuous monitoring or
proof that a model fits. The remote status marker is forgeable and the test
host is privileged. SSH process bounds, authenticated telemetry, existing
workloads, process-tree lifetime and governor admission remain separate work.
