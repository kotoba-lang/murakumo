# conv2d 3x3, stride 1, pad 1, bias, f32 (I1 slice 2, JUN-927)

Recorded 2026-11-06T15:26:57+0900 on 21mbp.local, Apple M1 Max, load average 13.17 19.13 19.75 (busy host: timings are diagnostic and noisy).

- amu: `f6c6ea215a8c51383c5ebac0b39879cef780eafb`, `bin/amu` sha256 `ee9a94e406f90dd1c899121708bed01b44818f2c3bc0b9780f266b0408c61ff0`
- cc: Apple clang version 21.0.0 (clang-2100.1.1.101)

## Square shape (CI=32 CO=32 H=32 W=32 REPS=10)
```
reps=1 checksum equal: 185740223315065
reps=10 checksum equal: 207643388578009
mutant differs: 89601007808320 != 185740223315065
oracle: elements 32768 max_abs_err 1.427e-05 max_abs_ref 2.516e+01
abs err <= 1e-3: ok
amu round 1: 76.34111 ms per pass (diagnostic)
c round 1: 6.566666 ms per pass (diagnostic)
amu round 2: 79.95777 ms per pass (diagnostic)
c round 2: 9.824444 ms per pass (diagnostic)
amu round 3: 85.73666 ms per pass (diagnostic)
c round 3: 6.523333 ms per pass (diagnostic)
```

## Non-square shape (CI=16 CO=24 H=20 W=28 REPS=2), gate only
```
reps=1 checksum equal: 141799805491839
reps=2 checksum equal: 192633476908189
mutant differs: 161409523048271 != 141799805491839
oracle: elements 13440 max_abs_err 7.317e-06 max_abs_ref 1.604e+01
abs err <= 1e-3: ok
```

## What this shows
- The Kotoba kernel's checksum equals the C twin's, bit for bit, at 1 and N passes and on two shapes. The non-square shape is there because a square one hides a swapped H/W index.
- Mirroring the window in x changes the checksum, so the gate can fail.
- The f32 twin is within 1.4e-5 of an f64-accumulated reference (outputs up to 25 in magnitude).
- Kotoba is roughly 10x slower than `cc -O2` on this shape (about 77 ms against about 7 ms). The host load was 15 to 20, one C round came out negative (noise), so treat the ratio as indicative only.

## What it does not show
- One kernel, synthetic data, one thread, CPU only. Stride 1 and a 3x3 window only: no stride 2 downsample, no 1x1, no groups, no NHWC.
- The reference is the twin's math in double, not comfyui-clj or ComfyUI output.
