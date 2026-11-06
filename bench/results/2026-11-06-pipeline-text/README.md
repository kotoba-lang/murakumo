# Both SDXL text towers inside the render program (stage M slice 21, JUN-927)

Recorded 2026-11-06T19:31:10+0900 on 21mbp.local, M1 Max, load average 10.41 13.79 18.33. amu `5beb858cf1fc2d43bc6b5b05833a698535dc1f40`, profile `amu-program-metal`. Command: `sh bench/kernels/pipeline-metal/run.sh <amu> <ComfyUI-0.44.0> <stubs> <python with torch, transformers, safetensors, einops, numpy, tqdm> <sd1_tokenizer dir>`.

## What changed
The slice-18/19 render program took its text-tower outputs from files. It now runs the **towers themselves**, inside the same program and the same safetensors file: from the 77 token ids of a prompt and of the empty prompt it runs OpenAI CLIP-L (12 layers, 768 wide, quick_gelu, 123 M parameters) and OpenCLIP bigG (32 layers, 1,280 wide, gelu, 695 M parameters, with its text projection), both at full size, keeps each tower's penultimate hidden state and bigG's projected end-token vector, concatenates them (L then G), builds the label vector, and renders (4 guided Euler steps, UNet and VAE as before). Each tower is recorded once and re-run for the second prompt after copying the ids, like the UNet. 2,886 dispatches are recorded in all. The oracle is Hugging Face's `CLIPTextModelWithProjection` for both towers (`hidden_states[-2]`, `text_embeds`) feeding ComfyUI's `torch.cat` and real `encode_adm`, then the same ComfyUI render. The token ids come from Hugging Face's `CLIPTokenizer` (the vocabulary of ComfyUI's sd1_tokenizer): L is padded with the end token and G with 0, as ComfyUI's `SDXLClipGTokenizer` (`pad_with_end=False`) does.

## Result (limits: conditioning 5e-2, the figure slice 15 fixed for the bigG tower before its run; steps and image 5e-2 as in slice 18; |gpu - ref| / max(1, |ref|))
```
4 Euler steps (2 UNet passes each) + VAE decode, 256 px, 2886 recorded dispatches: ctx_c=8.855e-03 y_c=2.563e-03 ctx_u=1.003e-02 y_u=2.642e-03 step0=3.545e-02 step1=3.636e-02 step2=3.613e-02 step3=3.640e-02 image=1.688e-02  finite=1  ref|x_final|max=62.44 ref|image|max=4.13 ok process wall 3 s
mutant [guidance]: worst error 4.924 (detected)
mutant [timestep-reversed]: worst error 2.549 (detected)
mutant [uncond-context]: worst error 4.705 (detected)
mutant [concat-order]: worst error 11.46 (detected)
mutant [size-order]: worst error 2.144 (detected)
mutant [vae-residual]: worst error 8.857 (detected)
gate passed
```
- The conditioning built from the GPU towers is within 1.0e-2 of the Hugging Face towers' (context) and 2.6e-3 (label vector), the same f16 scale as slice 15's tower gate. The step latents differ from ComfyUI's by 3.5e-2 to 3.6e-2 and the image by 1.7e-2: a little more than in slices 18 and 19 (2.8e-2 to 3.3e-2) because the conditioning now carries the towers' f16 error into the guided step. All stay inside the 5e-2 limit chosen in slice 18.
- Six mutants are detected (guidance dropped, timestep reversed, unconditional pass given the conditional context, L/G order, size order, VAE residual).
- The program had grown past what Kotoba's `main` accepts (281 let bindings failed with the generic "expected one exported main" error; the 246 of slice 18 worked), so the VAE recording and the time/label embedding moved into helper functions. This is a compiler limit I inferred from the failure and did not locate precisely.

## What it does not show
- The **tokenizer is not in the program**: token ids are read from files made by Hugging Face's tokenizer. The Kotoba CLIP tokenizer of an earlier slice (gated against Hugging Face's) is a separate program; joining it needs its vocabulary and merge-rank loading moved into this one, not done. Prompt weighting, embeddings and clip skip other than -2 are not handled either.
- Random tower weights, the small UNet (model_channels 64) and VAE (ch 32), 256 px, Euler only: the same limits as slices 18 and 19. No trained checkpoint (D1), no place decided to run a real render (D4).
- Process wall time is 2 to 4 s including the load of a 3.5 GB weight file; not a benchmark.
