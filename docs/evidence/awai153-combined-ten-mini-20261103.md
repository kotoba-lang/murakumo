# Ten-mini combined observer sample

Around 06:06 UTC on 2026-11-03, the combined-sysctl observer from commit
`b80825fa8072385f0441186c29e75979e4260706` collected all four sections from
each of the ten Tailscale peers. The diagnostic host used pinned SSH host
keys and an existing identity, with at most three observer processes in
parallel. The test produced exactly 30 task-local capture files, three per
peer. It did not run a bot or model or change a service.

| Node | Physical RAM | Swap used | Memory pressure free | Disk available |
| --- | ---: | ---: | ---: | ---: |
| Asher | 16 GiB | 0 MiB | 92% | 81,797,716 KiB |
| Benjamin | 16 GiB | 0 MiB | 91% | 60,988,716 KiB |
| Dan | 16 GiB | 133.25 MiB | 88% | 123,390,072 KiB |
| Issachar | 16 GiB | 0 MiB | 90% | 131,776,988 KiB |
| Joseph | 16 GiB | 2,794.44 MiB | 20% | 70,526,344 KiB |
| Judah | 16 GiB | 0 MiB | 91% | 78,483,280 KiB |
| Levi | 16 GiB | 0 MiB | 87% | 94,441,668 KiB |
| Naphtali | 16 GiB | 0 MiB | 90% | 117,198,296 KiB |
| Simeon | 16 GiB | 0 MiB | 91% | 127,449,228 KiB |
| Zebulun | 16 GiB | 1,080.44 MiB | 88% | 64,554,040 KiB |

These are non-atomic, untrusted point-in-time reports. Joseph's pressure and
swap differ notably from the others; free percentage is not allocatable RAM,
and this observation does not determine the cause. Asher and Simeon had failed
earlier attempts, so this single successful pass does not establish sustained
connectivity or prove the reduction from four connections improved reliability.
No measurements here authorize admission, deployment or inference. The
diagnostic process host, remote response integrity, resource governor,
workload lifecycle and multi-host fencing remain unqualified.

Task-only native fixture source SHA-256:
`82148a49d77b6ab4cbfe6415b143e1f356a2d85535f238ea2da8f143ed729366`;
signed image SHA-256:
`75146bc67d4fe65b9225bb77bef4d00763e9bc217dffb98cfd4b226c688eb2e4`.
It used the same bounded diagnostic host as the
[two-host observation](awai153-combined-sysctl-20261103.md).
