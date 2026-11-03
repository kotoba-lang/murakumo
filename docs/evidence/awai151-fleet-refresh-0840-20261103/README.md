# Ten Mac mini read-only resource refresh

At 08:40 UTC on 2026-11-03, Tailscale reported the ten named minis online. A read-only SSH pass, using batch authentication and strict host-key verification, sampled `memory_pressure -Q`, `sysctl vm.swapusage`, and `sysctl hw.memsize`. At most three SSH queries ran at once; this was not an atomic fleet observation. All ten responded and reported 16 GiB physical memory.

| Node | macOS pressure free % | Swap used MiB |
|---|---:|---:|
| naphtali | 90 | 0 |
| simeon | 91 | 0 |
| judah | 91 | 0 |
| zebulun | 88 | 1279.62 |
| levi | 87 | 0 |
| joseph | 20 | 3119.38 |
| issachar | 90 | 0 |
| dan | 88 | 133.25 |
| benjamin | 91 | 0 |
| asher | 92 | 0 |

These point-in-time values do not establish memory available for allocation, GPU usage, headroom, reservations, process ownership or a safe inference placement. Joseph's sample warrants conservative treatment but does not establish a cause or authorize stopping work. Values can change immediately; existing services were neither modified nor stopped. Continuous capacity measurement, process containment, host admission and cross-host fencing remain unqualified.
