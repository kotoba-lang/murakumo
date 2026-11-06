# The float16 range of the VAE, an int64 buffer, and a real-size float16 file (stage M slice 29, JUN-927)

Recorded 2026-11-06T20:52:22+0900 on 21mbp.local, M1 Max 32 GiB, load average 25.61 22.03 18.38. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`. Three loose ends of slices 27 and 28, closed or measured.

## 1. SDXL's VAE and the float16 range: my earlier "safe by construction" was wrong
Slice 27 said the program keeps activations in float32 between layers, so the known float16 overflow of SDXL's VAE was "not expected". It was expected, and it was there. A stress fixture (`VAESTRESS=K`: every residual block's output convolution of the decoder multiplied by K) makes the float32 oracle's residual stream reach 1.5e5, past the half range of 65,504 (taps: `mid` 6.1e4, `b0` 1.5e5). The program then produced **NaN from the first upsample on**: two places convert an un-normalised float tensor to half for a convolution, the nearest-neighbour upsample feeding each Upsample convolution and the half copy of the input for a 1x1 shortcut convolution where the width changes. Trained SDXL VAE weights do exactly this in their up blocks.
The fix: before such a conversion the tensor's largest magnitude is found on the GPU (`amax_part` / `amax_fin`), a power-of-two scale s >= 1 brings it under 8192, the convolution runs on the scaled half tensor without bias, and `axpb` multiplies the output by s and adds the bias. Scale 1 (nothing changes numerically) for ordinary activations.
While making it work I also hit a slot collision in the stand-alone VAE program: its state vector had 22 pipeline slots and 23 pipelines (the pipelines table overlapped the first dispatch-buffer entry), which silently disabled the new bias kernel and gave 27 percent errors at normal scale; the table was moved as the render program's already was.

Normal scale (unchanged numerics, all mutants detected; the mutants for the changed kernels were re-aimed at their new text):
```
mini (3104323 parameters, 64 px): cin=5.881e-04 mid=1.856e-03 l3=2.748e-03 l2=3.797e-03 l1=6.018e-03 b0=1.091e-02 final=4.847e-03 ok best=0.05 s
sdxl64 (49490179 parameters, 512 px): final=6.208e-03 ok best=1.39 s
sdxl128 (49490179 parameters, 1024 px): final=6.255e-03 ok best=8.13 s
mutant [attn-scale]: final error 1.892 (detected)
mutant [upsample]: final error 4.403 (detected)
mutant [residual]: final error 3.05 (detected)
mutant [silu]: final error 3.029 (detected)
gate passed
```
Stress (K = 20000; stages judged relative to their own largest value, 5e-3, the image 1e-2 as always; the 5e-3 was chosen after the first run, which gave 3e-4 to 2.4e-3; the oracle must exceed 65,504 where it is tapped, which it does: 1.5e5):
```
mini (3104323 parameters, 64 px): cin=5.881e-04 mid=3.800e+00 l3=8.759e+00 l2=1.923e+01 l1=4.390e+01 b0=4.055e+01 final=6.511e-03 ok best=0.05 s
sdxl64 (49490179 parameters, 512 px): final=9.708e-03 ok best=1.46 s
mutant [range guard removed]: non-finite output (detected)
gate passed
```
- With the guard the 64-px decoder with a residual stream up to 1.5e5 matches the float32 oracle at the image (6.5e-3), and the real-width decoder at 512 px with the same weights scaling gives 9.7e-3 (its activations were not tapped, so I cannot say they crossed 65,504). Removing the guard gives non-finite output: the gate has that mutant.
- Not shown: trained weights. The stress weights are random with a large gain, a model of the failure, not the failure itself.

## 2. An int64 tensor in the float16 file
The single-file fixture now carries `conditioner.embedders.0.transformer.text_model.embeddings.position_ids` as int64 [1, 77], as a real CLIP-L does. It is indexed (8 bytes per element) and never read; the render is unchanged (ids exact, conditioning 5.9e-3, image 1.7e-2, ten mutants detected).

## 3. A real-size float16 checkpoint through the whole program
`bench/kernels/pipeline-metal/big_f16.sh` streams a random-weight SDXL single file of the real size, with no oracle, and runs the render from prompt text to image:
```
tensors 2406 bytes 6869699456
file 6.87 GB
step 0: finite=True max|x|=59.43
step 1: finite=True max|x|=59.95
image 256x256: finite=True max|x|=2.16 mean|x|=0.206
recorded dispatches 4290
maximum resident set size: 4.74 GB
peak memory footprint: 15.33 GB
real: 0.00 GB
finite
build dir /var/folders/h_/bx1m0lv90qxb1sdrx5tqn8b80000gn/T//big-f16.jFSsZ6
sh bench/kernels/pipeline-metal/big_f16.sh $AMU $O/src/ComfyUI-0.44.0 $O/stub  68.85s user 24.29s system 86% cpu 1:47.74 total
```
- 6.87 GB, 2,406 tensors (UNet at model_channels 320, VAE decoder ch 128, CLIP-L, OpenCLIP bigG with its stacked projections) loaded into one Metal buffer; the 4,290 recorded dispatches ran twice-guided Euler steps at a 256 px latent and decoded an image: all values finite. **Peak memory footprint 15.3 GB** (the 6.9 GB file buffer, 5.2 GB of half weights for the UNet plus the towers and VAE, and the 0.8 GB parameter buffer); the machine's swap was already 8.9 GB and did not grow.
- The values are random, so the image is noise; this is a size, memory and finiteness check, not a correctness check (correctness at real UNet width is slice 25 and 26; the layout is slice 27 and 28).

## What it does not show
Still no trained checkpoint (D1), no judgement of the image, no place decided to run it (D4).
