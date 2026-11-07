# ResBlock composition, f32 (I2 step 1, JUN-927)

Recorded 2026-11-06T15:54:04+0900 on 21mbp.local, Apple M1 Max, load average 25.82 28.04 25.90 (busy host: timings diagnostic).

- amu: `f6c6ea215a8c51383c5ebac0b39879cef780eafb`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)

## CI=16 CO=32 16x16, G=8, TE=64
```
reps=1 checksum equal: 33640690923766
reps=5 checksum equal: 45750746285162
mutant differs: 115030755876566 != 33640690923766
oracle: elements 8192 max_abs_err 7.201e-06 max_abs_ref 9.279e+00
abs err <= 1e-4: ok
amu round 1: 33.57750 ms per pass (diagnostic)
c round 1: 2.397500 ms per pass (diagnostic)
gate passed (CI=16 CO=32 H=16 W=16 G=8 TE=64 REPS=5)
```

## CI=24 CO=24 12x20, G=6, TE=32 (gate only)
```
reps=1 checksum equal: 270854419974924
reps=2 checksum equal: 28203748987903
mutant differs: 269143010656245 != 270854419974924
oracle: elements 5760 max_abs_err 4.939e-06 max_abs_ref 9.502e+00
abs err <= 1e-4: ok
gate passed (CI=24 CO=24 H=12 W=20 G=6 TE=32 REPS=2)
```

## What this shows
- A whole SDXL-style ResBlock (GroupNorm+SiLU, 3x3 conv, linear on silu(temb) broadcast over positions, second GroupNorm+SiLU and 3x3 conv, 1x1 skip conv, residual add) runs as one Kotoba program built by `bin/amu`, with a checksum equal to its C twin's bit for bit, on two shapes. The twin and the f64 reference are one source (`impl.h`) compiled with `float` and with `double`.
- Dropping the skip connection from the residual add changes the checksum, so the gate can fail.
- The f32 output is within 7.2e-6 of the f64 reference (outputs up to about 9.3).
- Kotoba is roughly 13x slower than `cc -O2` (about 30 ms against about 2.3 ms per pass; busy host, diagnostic).

## What it does not show
- Synthetic weights, one thread, CPU only; no f16, no checkpoint, no GPU.
- The reference is the twin's own pipeline in double, not comfyui-clj or ComfyUI output: comfyui-clj needs a Deno/JVM run and a checkpoint-layout mapping that this step did not do.
- The kernels are copied into the program, not imported; the toolchain's import/project route is still open (amu `docs/native-public-entry.md`).
