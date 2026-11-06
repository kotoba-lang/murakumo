# The SDXL UNet call against ComfyUI's own on this Mac's GPU (stage M slice 46, JUN-927)

Recorded 2026-11-07T03:42:24+0900 on the M1 Max, load average 8.46 7.99 7.37 (other processes were using the machine, as in every timing here). Oracle script: `bench/oracle/comfyui/unet_mps_timing.py` (run once by hand, local oracle only; PyTorch 2.6.0 MPS, ComfyUI v0.44.0 with the two stubs of the other oracle scripts). Ours: `bench/kernels/unet-sdxl-metal/run.sh` (synthetic weights, ~2,100 kernels in one command buffer).

The roadmap compared our UNet with a ComfyUI figure from a different machine (an M4 mini). This is the first same-machine comparison: ComfyUI's own `UNetModel` with SDXL's configuration (2,567,463,684 parameters, model_channels 320, channel_mult 1 2 4, transformer depths 0 / 2 / 10 and 10 in the middle, 77 x 2048 context, 2,816-wide label), built directly in float16 on the MPS device with random weights, one forward pass of batch 1, best of 5 after 2 warm-ups, `torch.mps.synchronize()` around each.

| latent (pixels) | ComfyUI UNetModel, fp16, PyTorch MPS | Kotoba on Metal (this repo) | ours / ComfyUI |
|---|---|---|---|
| 64 (512) | 0.325 s (twice) | 0.44 s | 1.35 |
| 96 (768) | 0.943 s and 1.046 s | 1.09 s | 1.04 to 1.16 |
| 128 (1024) | 2.408 s | 2.59 s | 1.08 |

Reading: on the same GPU our UNet call is 4 to 35 percent slower than PyTorch's, closest at the large sizes where the matrix work dominates and farthest at 512 px, where PyTorch's fused scaled-dot-product attention and its tuned kernels cost less in the many small layers. A 25-step 768 px render (50 calls) is therefore about 55 s here against about 50 s in ComfyUI, not "tens of seconds faster" as the older 84 s figure suggested and not slower by the 2x the early slices had. The remaining gap is the per-call overhead and the small-layer kernels, not the large GEMM and convolution work.

Caveats: random weights in both (values meaningless, timing is shape-bound); the two runs were sequential, not simultaneous, and the load average was 9 to 11 for both, so differences under about 10 percent are inside the noise (the two ComfyUI 768 px runs differ by 11 percent); one machine; ComfyUI's float16 path is PyTorch's, with its own attention implementation, so this is "ComfyUI as it runs on this Mac", not the best any implementation could do; no sampler, text tower or VAE in either number.
