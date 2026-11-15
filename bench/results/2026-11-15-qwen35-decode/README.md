# Qwen3.5-4B decode speed, first pass (2026-11-15)

Linear AWAI-2938. Busy host (load average about 40): differences under about 1 ms per token are noise. The ms/token
figures below come from 64 decode steps after the 5-token prompt, two runs each.

| Change | ms/token | Kept |
| --- | ---: | --- |
| #691 (one matvec row per simdgroup, serial encoder) | 22.6–24.3 | |
| x in registers for 4 rows per simdgroup; Q6_K via ushort loads | 23.8–24.1 | yes |
| lanes cover 8 consecutive values of a 256-value step (coalesced reads), 1/2/4/8 rows | 22–32 | 4 rows |
| 8 simdgroups split k for the same 4 rows (more threadgroups for 2560-row matrices) | 23.6 | yes |
| Q4_K/Q5_K scales read as words, branch-free | 23.0–23.3 | yes |
| **concurrent encoder, barriers only where a dispatch reads an earlier one's output** (q\|k\|v, z, alpha, beta and gate, up overlap) | **21.6** | yes |

## Measured on the way

| Measurement | Result |
| --- | --- |
| encode on the CPU | 0.2 ms per token |
| dispatches when every kernel returns at once | 1.6 ms per token |
| head (521 MB of Q6_K) | about 3.1 ms, about 170 GB/s |
| layer matvecs, per dtype | Q4_K about 119 GB/s, Q5_K about 106 GB/s, Q6_K about 168 GB/s |

The GPU time is almost all matvec time.

## Comparison and next steps

- **Comparison:** MLX reads about 2.4 GB per token and runs at 61–62 tok/s (about 16 ms). This Q4_K_M file reads 2.74 GB.
- **Not done yet:**
  - committing the next token before waiting on the previous one (tokens chained on the GPU);
  - faster Q4_K/Q5_K kernels;
  - fusing RMSNorm into the matvecs.

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row. The Q6_K mutant now targets the new kernel's scale index.
