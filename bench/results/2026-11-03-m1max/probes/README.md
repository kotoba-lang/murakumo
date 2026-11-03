# Vector-access probes, 2026-11-03

Each probe runs 50,000,000 iterations and returns its accumulator, so both
builds must print the same number.

| Probe | Result | Mac CLI build (`bin/amu compile`) | Static image (`root.elf … --format static`, podman) |
| --- | --- | ---: | ---: |
| `scalar.kotoba` | 250337436076103 | 0.478 s (one run) | 0.269 / 0.270 / 0.275 s |
| `vec.kotoba` | 166767901446151 | 0.540 s (one run) | 3.247 / 3.181 / 3.133 s |

Wall time per process; process start-up is included and not subtracted. Amu
`1abb17f30`: `bin/amu` sha256 `916f3237…6a`, static root sha256 `e140b8d2…97`.
