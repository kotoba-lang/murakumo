# Ten Mac mini resource snapshot

At approximately 2026-11-03 04:25 UTC, the ten Mac mini names in `fleet.edn`
were matched to Tailscale's peer inventory and queried over SSH with batch
mode and strict host-key checking. All ten returned successfully. Queries were
read-only: `sysctl hw.memsize`, `memory_pressure -Q`, `df -Pk /`, `sysctl
vm.swapusage` and the largest resident process from `ps`. The two SSH passes
were sequential per node but not an atomic fleet snapshot; concurrency was
limited to three. Every host reported 16 GiB physical memory.

| Node | macOS pressure free % | Swap used MiB | Largest RSS process at second sample |
|---|---:|---:|---|
| naphtali | 90 | 0 | node, 1,739,808 KiB |
| simeon | 91 | 0 | kotoba-server, 1,737,264 KiB |
| judah | 91 | 0 | java, 2,505,024 KiB |
| zebulun | 88 | 1080.44 | Python, 3,606,352 KiB |
| levi | 87 | 0 | kotoba-server, 1,201,200 KiB |
| joseph | 20 | 3612.94 | Mishima llama-server, 8,869,664 KiB |
| issachar | 90 | 0 | SecurityAgent, 86,416 KiB |
| dan | 87 | 133.25 | kotoba-server, 1,064,784 KiB |
| benjamin | 91 | 0 | kotoba-server, 1,505,248 KiB |
| asher | 92 | 0 | SecurityAgent, 313,792 KiB |

These measurements expose existing workloads and changing resource pressure;
they are not a reservation, a prediction of available unified/GPU memory,
or a safe admission decision. RSS is not an additive GPU-memory accounting
method. In particular, the Joseph sample warrants conservative handling, but
it does not establish the cause of swap or authorize stopping its workload.
No model, bot, service or configuration was changed. The governor's continuous
observation, reservations, limits, process lifetime and fleet-level fencing
remain unqualified. APFS available capacity was also observed, but no disk
allocation or storage qualification was attempted.
