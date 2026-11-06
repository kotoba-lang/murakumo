# Staging the linear (f16 GEMM) kernel through threadgroup memory: no gain (stage M slice 13, JUN-927)

Recorded 2026-11-06T18:15:46+0900, M1 Max, load average 8 to 13 from other agents' processes.

## Tried
The `linear_t` kernel (the largest block of the SDXL-shaped call, about 1,236 GMAC at ~1.6 TMAC/s) reads its A and W fragments straight from device memory per simdgroup. A variant staged 64x32 tiles of A and W through threadgroup memory with cooperative `half4` loads (K tile 32, scalar-path fallback when K is not a multiple of 32), in both the mini-UNet and SDXL programs.

## Result
- Correctness: the mini-UNet gate against ComfyUI v0.44.0 passed with the same errors (final 1.368e-03, worst stage 4.26e-03), all mutants detected.
- Speed: SDXL-shaped call at 512 px, 8 interleaved runs each of the previous kernel and the staged one: previous 1.00 to 1.10 s (median about 1.04), staged 0.98 to 1.04 s (median about 1.01), with one outlier each (0.73 and 0.78 s). That is inside the noise of a loaded host: no gain I can claim.
- Decision: the change was **not merged** (more code, no measurable benefit). The device-memory loads are evidently served well enough by the cache; the limit on this kernel is elsewhere (the 4x4 accumulator tile per simdgroup and the 8-wide K step, the epilogue, the grid size at small token counts, or the host's contention), none of which was measured.

## Not done
No isolated linear micro-benchmark on a quiet host; no larger per-simdgroup tile; no double buffering; no profiler (Instruments / Metal counters) run.
