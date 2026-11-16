# Qwen3.5-4B decode, fused Gated DeltaNet kernel (2026-11-16)

Linear AWAI-2938. Follow-up to [the third pass](../2026-11-16-qwen35-decode-3/README.md). ms/token comes from 64
decode steps after the 5-token prompt, with builds run interleaved in the same minutes. Very busy host (load averages
25–69, other sessions on the GPU).

## Kept

**One kernel per Gated DeltaNet layer instead of three.** `dnf` runs one 1024-thread threadgroup per value head. In
order, it:

1. computes the head's conv1d channels (q and k of its key head, v of its value head) into threadgroup memory;
2. RMS-normalises q and k;
3. updates the delta-rule state, 4 dv rows per simdgroup;
4. applies the gated RMSNorm to the head's 128 outputs.

The conv, dnet and gnorm dispatches and two of the barriers between them are gone, on each of the 24 DeltaNet layers.

Value heads hv and hv + 16 read the same key head's q and k channels. Only the head below 16 writes their conv state,
so the state is kept in two copies (`oCONV`, and `oCONV2` added at the end of the arena) that swap roles by token
parity. A head reads one copy and writes the other, so it never reads a state the other head has already shifted.

| Round | #695 (main) | fused DeltaNet |
| --- | ---: | ---: |
| 1 | 16.96 | 16.86 |
| 2 | 17.05 | 16.88 |
| 3 | 17.04 | 16.93 |
| 4 | 17.07 | 16.81 |

About 0.15 ms per token (1 %), ahead in every round. The greedy tokens are unchanged.

## Tried and rejected

| Variant | Result |
| --- | --- |
| gate and up matvecs fused with SwiGLU (`mvk<12, 1>`: each simdgroup reads 2 gate and 2 up rows and writes silu(gate) × up; one dispatch and one barrier fewer per layer) | 17.10–17.23 against 16.72–17.09 for the fused-DeltaNet build, 5 interleaved rounds |

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row. That includes the tile and conv mutants, which now mutate the
fused kernel.
