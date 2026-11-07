# Schedulers `normal` and `karras`, sampler `dpmpp_2m` (stage M slice 49, JUN-927)

Recorded 2026-11-07T09:18:21+0900, M1 Max, load average 26.21 27.92 24.46. Program: `bench/kernels/pipeline-metal/pipeline_metal.kotoba.in`; gate `run.sh` with `SCHED=simple|normal|karras`, `SAMPLER=euler|dpmpp_2m`, `STEPS`, `MUTANTS`; oracle: ComfyUI v0.44.0's own `normal_scheduler`, `get_sigmas_karras`, `append_zero` and `sample_dpmpp_2m` source text (taken by `ast` as for the earlier `simple_scheduler` and `sample_euler`), with the Hugging Face towers and ComfyUI's `UNetModel`.

The render program had one schedule (`simple`) and one sampler (Euler). ComfyUI users mostly pick `dpmpp_2m` with `karras`, so:
- `sched_normal`: timesteps `linspace(999, 0, steps)` (torch's linspace counts the second half back from the end), sigma(t) interpolated between the two neighbouring discrete log-sigmas, a final 0. `sched_karras`: sigma_min and sigma_max the first and last discrete sigmas, rho 7, the ramp `linspace(0, 1, steps)`, a final 0.
- `cfg_dpmpp2m`: DPM-Solver++(2M) on the guided denoised values with the previous step's denoised kept in a buffer; the first step and the step to sigma 0 use the first-order form, as `sample_dpmpp_2m` does.
- Chosen at build time by the `@SCHED@` and `@SAMPLER@` placeholders (the default build is unchanged: simple + Euler).

| run (hf layout unless noted) | result |
|---|---|
| simple + Euler (regression) | gate passed |
| karras + dpmpp_2m, 4 steps | gate passed; steps within 3.2e-2, image 1.8e-2; mutants for the sampler (old denoised dropped, step ratio) and the scheduler (rho) detected |
| karras + dpmpp_2m, single-file layout | gate passed, all 22 mutants detected |
| simple + dpmpp_2m, 4 steps | gate passed, mutants detected |
| normal + Euler, 5 steps | gate passed; steps within 3.6e-2; the endpoint mutant detected (0.56) |
| normal + dpmpp_2m, 5 steps, single-file layout | clean render equal within the limit (ids exact, steps <= 2.8e-2) and the 11 mutants that ran were detected; its gate stopped on a mutant that is too weak to see (see below), which I replaced and ran in the normal + Euler row instead |

Notes: the first mutant I wrote for the normal scheduler (drop the interpolation) is not detectable: at 4 steps the timesteps are integers, and at 5 steps half a timestep moves sigma by about 0.5 percent, below what the gate resolves (3.5e-2 against the clean 3.6e-2). The replacement moves an endpoint. Every sampler and scheduler gate uses the 256 px toy model with random weights, 4 or 5 steps; step errors of 3 to 4e-2 are the usual float16 level against a 5e-2 limit. Along the way: the build substitutes only the first `@SCHED@` on a line (fixed with a global flag), the guidance mutant's text now occurs in two kernels (it is applied to both), and the shared amu build checkout I had been using was removed by another session's worktree cleanup, so these runs used a fresh one (amu main 1754ff26f).

Follow-up (slice 50): two more schedulers, `exponential` (`exp(linspace(log sigma_max, log sigma_min, steps))`) and `sgm_uniform` (the normal scheduler on `linspace(999, 0, steps + 1)` without its last point), the same way. Gates, hf layout, 4 steps, against ComfyUI's `get_sigmas_exponential` and `normal_scheduler(sgm=True)` source: exponential + Euler passed (steps up to 4.3e-2 against the 5e-2 limit, the widest of any run; the order mutant is detected at 225), sgm_uniform + dpmpp_2m passed, with both prompts weighted, embeddings and 2 / 3 blocks in the path as in every gate. The normal + dpmpp_2m single-file gate of slice 49, which had stopped on the undetectable mutant, was re-run with the endpoint mutant and passed (all 22 mutants detected). The two state slots of the context length moved again, from 60 / 61 to 62 / 63, because the two new kernels take pipeline slots 60 and 61 (the table of pipeline slots reaches slot 61 now; a further kernel needs the dispatch tables moved first).

Not done: other samplers (`euler_ancestral`, `heun`, `dpmpp_sde`, `dpmpp_2m_sde`, `lms`, `uni_pc`...) and schedulers (`exponential`, `sgm_uniform`, `beta`...); samplers that need noise need ComfyUI's noise sequence supplied to both sides.
