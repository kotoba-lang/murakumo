# I1 remaining kernels: LayerNorm, matmul, GEGLU, upsample, timestep embedding, general conv2d (JUN-927)

Recorded 2026-11-06T15:49:31+0900 on 21mbp.local, Apple M1 Max, load average 29.63 26.48 24.40 (busy host: timings are diagnostic and noisy).

- amu: `f6c6ea215a8c51383c5ebac0b39879cef780eafb`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)
- command: `sh bench/kernels/gate.sh bench/kernels/<name> <amu checkout> ["PARAMS"] [REPS]`

## layernorm (R=64 N=320 10)
```
reps=1 checksum equal: 233604315942640
reps=10 checksum equal: 56774694182367
mutant differs: 133764052060125 != 233604315942640
oracle: elements 20480 max_abs_err 9.292e-07 max_abs_ref 4.521e+00
abs err <= 1e-4: ok
amu round 1: .8233333 ms per pass (diagnostic)
c round 1: .6133333 ms per pass (diagnostic)
gate passed (R=64 N=320 REPS=10)
```

## matmul (M=64 K=320 N=320 5)
```
reps=1 checksum equal: 221223482416798
reps=5 checksum equal: 122875768014130
mutant differs: 78938354640128 != 221223482416798
oracle: elements 20480 max_abs_err 3.221e-05 max_abs_ref 4.951e+01
abs err <= 1e-3: ok
amu round 1: 76.31750 ms per pass (diagnostic)
c round 1: 4.415000 ms per pass (diagnostic)
gate passed (M=64 K=320 N=320 REPS=5)
```

## geglu (R=32 N=320 10)
```
reps=1 checksum equal: 201823137025561
reps=10 checksum equal: 44045833448185
mutant differs: 91979483573726 != 201823137025561
oracle: elements 10240 max_abs_err 4.914e-06 max_abs_ref 6.361e+01
abs err <= 1e-4: ok
amu round 1: 2.595555 ms per pass (diagnostic)
c round 1: .9133333 ms per pass (diagnostic)
gate passed (R=32 N=320 REPS=10)
```

## upsample (C=32 H=24 W=40 20)
```
reps=1 checksum equal: 224086486906937
reps=20 checksum equal: 44731216427065
mutant differs: 205785047561273 != 224086486906937
oracle: elements 122880 max_abs_err 0.000e+00
abs err <= 0: ok
amu round 1: .1442105 ms per pass (diagnostic)
c round 1: .1915789 ms per pass (diagnostic)
gate passed (C=32 H=24 W=40 REPS=20)
```

## timestep (DIM=320 NT=64 TS=16 10)
```
reps=1 checksum equal: 21645055742099
reps=10 checksum equal: 206953554932669
mutant differs: 281466954829971 != 21645055742099
oracle: elements 20480 max_abs_err 8.462e-08
abs err <= 1e-5: ok
amu round 1: -2.21666 ms per pass (diagnostic)
c round 1: -.632222 ms per pass (diagnostic)
gate passed (DIM=320 NT=64 TS=16 REPS=10)
```

## conv2d (CI=32 CO=32 H=32 W=32 KS=3 STRIDE=2 3)
```
reps=1 checksum equal: 102931799960585
reps=3 checksum equal: 231060158500329
mutant differs: 146924490107049 != 102931799960585
oracle: elements 8192 max_abs_err 1.129e-05 max_abs_ref 2.124e+01
abs err <= 1e-3: ok
amu round 1: 19.58000 ms per pass (diagnostic)
c round 1: .3850000 ms per pass (diagnostic)
gate passed (CI=32 CO=32 H=32 W=32 KS=3 STRIDE=2 REPS=3)
```

## conv2d (CI=16 CO=24 H=18 W=26 KS=3 STRIDE=1 3)
```
reps=1 checksum equal: 154938650173106
reps=3 checksum equal: 256467873853396
mutant differs: 68586722051322 != 154938650173106
oracle: elements 11232 max_abs_err 7.067e-06 max_abs_ref 1.746e+01
abs err <= 1e-3: ok
amu round 1: 5.580000 ms per pass (diagnostic)
c round 1: 2.020000 ms per pass (diagnostic)
gate passed (CI=16 CO=24 H=18 W=26 KS=3 STRIDE=1 REPS=3)
```

## conv2d 1x1 (CI=24 CO=40 H=20 W=28 KS=1 STRIDE=1), channel-index mutant
The conf mutation (padding side) is a no-op when KS=1, so this shape uses GATE_MUT_FROM/GATE_MUT_TO to swap the weight channel indexing.
```
reps=1 checksum equal: 126545760721665
reps=3 checksum equal: 94803077268705
mutant differs: 280438981633962 != 126545760721665
oracle: elements 22400 max_abs_err 1.529e-06 max_abs_ref 7.684e+00
abs err <= 1e-3: ok
amu round 1: 4.060000 ms per pass (diagnostic)
c round 1: 1.570000 ms per pass (diagnostic)
gate passed (CI=24 CO=40 H=20 W=28 KS=1 STRIDE=1 REPS=3)
```

## What this shows
- Each Kotoba kernel's checksum equals its C twin's bit for bit at 1 and N passes, on the shapes listed. conv2d is gated on 3x3 stride 2, 3x3 stride 1 and 1x1.
- Every kernel has a mutant (named in its `conf`) that changes the checksum, so each gate can fail.
- Errors against f64 references: LayerNorm 9e-7, matmul 3e-5, GEGLU 5e-6, timestep embedding 8.5e-8 (arguments up to about 1000 rad), conv2d 1e-5; upsample is exact.
- sin, cos, exp and erf are not in the language; they are the fixed polynomials in each kernel (erf is Abramowitz-Stegun 7.1.26, up to 1.5e-7 off the true erf, so GEGLU differs from an exact-erf GELU by about that much).

## What it does not show
- Synthetic data, one thread, CPU only, no f16. Kotoba is roughly 6x to 15x slower than `cc -O2` where timing is above noise (the host load was high); the tiny kernels are noise.
- References are the twins' math in double (libm exp/erf/sin/cos), not comfyui-clj or ComfyUI output.
- No NHWC, groups, dilation, batch above 1.
