# Plan: the fastest inference engine, in Kotoba, built by Amu selfhost

Owner direction, 2026-11-07: murakumo had grown several unrelated directions. Put them aside and aim at one thing:
**the fastest inference engine, written in Kotoba and built by Amu selfhost.** The reference to beat is
[Kapitsa](https://github.com/nace-ai/kapitsa) (nace-ai, C++ and Metal, Apple silicon only).

Status: plan, decided by the owner on 2026-11-08 (D1–D4 below, as proposed). Nothing here is implemented by this
document. Numbers under "Where it stands" were measured and are linked; every later number is a target.

This document is the one plan. [inference-roadmap.md](inference-roadmap.md) (S0–S8) and
[media-generation-roadmap.md](media-generation-roadmap.md) (I0–I5, stage M) stay as detailed records; where they
disagree with this plan on order or scope, this plan wins.

## The goal, in one sentence

On a given Mac and a given model, murakumo's decode is faster than every other engine measured on the same Mac the same
day (llama.cpp, MLX, Kapitsa, …), with the same greedy output as plain decoding, and the engine is Kotoba compiled by
Amu selfhost with no C, C++, Python, JVM or nbb on its path.

"World's fastest" is therefore claimed per machine and per model, never across machines, and only through the AWAI-18
claim gate.

## Where it stands (measured)

MacBook Pro M1 Max, 32 GB. Qwen2.5-0.5B-Instruct Q8_0 unless noted.
[2026-11-03 record](../bench/results/2026-11-03-m1max/README.md).

| Engine | Decode tok/s | Note |
| --- | ---: | --- |
| MLX 8-bit | 249.4 | same Mac, 2026-11-03 |
| llama.cpp Metal | 218.4 | same Mac, 2026-11-03 |
| murakumo decode guest, GLSL kernels via MoltenVK and a C loader | 71.2 | 2026-10-19; the loader is gone, so this cannot be rerun |
| murakumo, Amu selfhost, one Q8_0 kernel on the CPU | 3.6 (kernel bound) | not a token rate |
| murakumo E1, Amu selfhost, Metal (hand-written MSL) | about 174 | 2026-11-15, diagnostic, load average about 9 ([record](../bench/results/2026-11-15-e1-first-token/README.md)) |
| murakumo E2, Amu selfhost, Metal (hand-written MSL) | 292.3–294.3 | 2026-11-15, 128 tokens, loaded host; same hour MLX 250.7–253.0 (`-p 8`), llama.cpp 155.8–186.6 ([record](../bench/results/2026-11-15-e2-decode/README.md)) |
| murakumo E3 prefill, pp512 | 8,540–9,087 (prefill) | 2026-11-15, very busy host, four undisturbed rounds; same hour llama.cpp 8,537–9,125 (level within about ±1 %), MLX 5,938–7,421 ([record](../bench/results/2026-11-15-e3-f16/README.md); third unit 8,170–8,322, [record](../bench/results/2026-11-15-e3-split/README.md); second unit 6,558–6,858, [record](../bench/results/2026-11-15-e3-gemm/README.md); first unit 6,227–6,314, [record](../bench/results/2026-11-15-e3-prefill/README.md)) |
| Kapitsa 0.2.36, Qwen3.5-4B 4-bit (not Qwen2.5: Kapitsa does not read it) | 106–110 (DFlash2 drafter), 78–80 (MTP head); its harness 137.5 | 2026-11-15, same M1 Max, busy host; same day on the same model MLX 48 (plain), llama.cpp 32–36 (plain) ([E0 record](../bench/results/2026-11-15-e0-kapitsa/README.md)). Its own M1 Pro 16 GB runs: Qwen3.5-4B 4-bit 97, Qwen3.6-35B-A3B 2-bit 52 ([benchmarks.md](benchmarks.md#other-engines-as-published)) |
| murakumo, Qwen3.5-4B Q4_K_M, Amu selfhost, Metal (engine library, `qwen35` profile) | 44.0 run between the others; 41.6–45.8 alone (17.6–18.0 ms/token in build comparisons) | 2026-11-16, plain greedy, loaded host; same hour MLX 4-bit 55.5, llama.cpp 38.6 on the same GGUF ([record](../bench/results/2026-11-16-qwen35-decode-3/README.md); previous pass 38.6 against MLX 67.9, [record](../bench/results/2026-11-16-qwen35-decode/README.md)) |

**Update 2026-11-15:** E1 generates tokens on the GPU (gate passed, greedy `314 746 600 220`). The other bars of ours above are dated records or kernel bounds.

What the last week built that this plan can use (stage M, JUN-927, 50 slices, all on the M1 Max through the
`amu-program-metal` profile of amu ADR 0405):

- reading multi-gigabyte files with `pread` and checking their SHA-256 in Kotoba before reading them
  ([record](../bench/results/2026-11-06-sha256-multi-gb/README.md));
- a safetensors reader ([record](../bench/results/2026-11-06-safetensors-metal/README.md));
- f16 simdgroup-matrix GEMM, implicit-GEMM convolution, fused non-causal attention, norms and activations on Metal;
- many dispatches in one command buffer (one dispatch per command buffer costs about 264–338 µs; batching removes it);
- a whole SDXL UNet call within 4–35 % of PyTorch MPS on the same Mac
  ([record](../bench/results/2026-11-07-unet-vs-comfyui-mps/README.md));
- a BPE tokenizer (CLIP) written in Kotoba.

So the Mac already has the pieces a GPU decode needs: file access, digest checks, a Metal dispatch route and command
buffer batching. What it does not have is an LLM forward pass on that route.

One honest caveat: the stage M GPU kernels are MSL source text inside Kotoba programs, written by hand. The host program
is Kotoba built by Amu; the kernels are not yet emitted by Amu from KIR v2. Closing that is step E7 below.

## What makes Kapitsa fast (read 2026-11-07, its README and docs/kflash.md)

| Technique | Kapitsa | In this plan |
| --- | --- | --- |
| Hand-tuned Metal kernels for 2/4/5/6/8-bit affine and NVFP4 weights | the base of every number | E2 |
| Speculative decoding: a block drafter (DFlash2, DFlash, MTP …) proposes 7 tokens, one 8-row pass of the target checks a best-first tree of candidates (KFlash); output equals plain decoding | most of its lead (351 tok/s on 35B-A3B, M5 Max) | E5 |
| MoE experts streamed from the SSD when the model does not fit | 180B on 48 GB | later |
| Prefill split between the GPU and the Neural Engine | prefill | later |
| KV cache on the SSD across restarts | follow-up latency | later |
| OpenAI / Anthropic APIs, streaming, tool calls | serving | E4 |

The lesson for the order: a plain decode at memory bandwidth comes first, because speculative decoding multiplies
whatever one target pass costs. The lead over MLX and llama.cpp then comes from the drafter and tree verify.

## Scope: what is in and what stops

**In:** one Mac, one process: read a model file, run the forward pass on the GPU, keep the KV cache, sample, draft and
verify, and answer `/v1/chat/completions`. Benchmarks against the other engines on the same Mac.

**Paused (owner decision D1, 2026-11-08).** Not deleted; no new work until the engine leads on the Mac.

| Direction | Issues | Why it stops |
| --- | --- | --- |
| Image, video, audio generation | JUN-927 (stage M) | its GPU tier is reused by E1–E3; SDXL itself is not the engine |
| Route C on the Xavier (nvgpu, hand-written SASS) | JUN-701, JUN-714, JUN-739, JUN-715 | a second GPU vendor before the first one generates tokens |
| mishima on MoltenVK | JUN-187 | its C loader is gone; the run cannot be repeated (historical record) |
| CPU-only stages S2 (bytes), S3 (NEON), S6 (threads) | JUN-804 | the fastest Mac decode is on the GPU; CPU work returns only if E-steps need it (tokenizer, sampler) |
| Fleet, economy, web plane, mesh, onboarding, PoI | JUN-210 and others | not the engine; the production fleet stays in maintenance only |

Fleet serving (nodes, quorum placement, `/v1` gateways) keeps running as it does today. It meets the engine again only
at E4, where a Mac node serves the engine's binary instead of an external model server.

## Steps

Each step keeps the existing rules: an exact or tolerance-bounded gate against an oracle, a mutant that must fail, one
recorded number under `bench/results/`, and the same-day comparison engines on the same Mac.

| Step | What | Gate | Target (M1 Max) |
| --- | --- | --- | --- |
| E0 · baseline | Same-day table on the M1 Max: llama.cpp Metal, MLX, **Kapitsa**, for Qwen2.5-0.5B Q8_0 (correctness model) and Qwen3.5-4B 4-bit (the smallest model Kapitsa publishes for M1). Record versions, settings and output tokens | each engine's greedy output recorded | the numbers to beat |
| E1 · first token on the GPU | Qwen2.5-0.5B: a GGUF reader on the stage M file route (digest checked first), embedding, RMSNorm, RoPE, GQA attention with KV cache, SwiGLU, tied head and argmax, all on Metal, one command buffer per token | prompt `760 6511 314 9338 369` → greedy `314 746 600 220` (2026-10-19 oracle); a flipped weight changes a token | any token rate |
| E2 · decode at bandwidth | quantized matvec kernels (Q8_0, then 4-bit affine) at memory bandwidth, fused norm/RoPE/attention, argmax on the GPU, no CPU round trip inside a token | E1's tokens unchanged | ≥ llama.cpp Metal 218, then ≥ MLX 249 on 0.5B; same order on 4B |
| E3 · prefill | the stage M f16 GEMM tier for prompt processing; flash attention over the prompt | logits within tolerance of E2's step-by-step result | ≥ llama.cpp Metal prefill (8,842 tok/s on 0.5B) |
| E4 · serve | Qwen BPE tokenizer and chat template in Kotoba; `/v1/chat/completions` with streaming from the engine binary; a Mac `murakumo node join` without an external model server (JUN-740 on the Mac) | API answer equals E2's tokens | first-token latency recorded |
| E5 · speculative decoding | a drafter the model ships (MTP head or a published DFlash-style block drafter), then tree verify in one multi-row pass | greedy output identical to plain decoding on a fixed prompt set; a broken verifier is caught | ≥ Kapitsa on the same model, same Mac, same day |
| E6 · headline model | the Qwen3.5/3.6 family Kapitsa leads with that fits 32 GB (dense 4B/9B; 35B-A3B 4-bit at about 22 GB if memory allows): gated delta-net and MoE kernels | per model, E1's gate with a recorded oracle continuation | fastest decode on the M1 Max for that model |
| E7 · kernels from Amu | replace each hand-written MSL kernel with one Amu emits from KIR v2 (ADR 0375 route B, rest of S7) | same output as the hand kernel; speed not worse | no hand-written MSL left on the path |

E5 status (2026-11-17, Qwen3.5-4B): drafts from the MTP head and from the DFlash block drafter both run, and both
give output equal to plain greedy decoding (gated: exact tokens, accept/replay/context mutants). With DFlash and
blocks of 8, text inside a think block decodes at 11.3–11.4 ms/token against 17–20 for plain decoding in the same
rounds; chat text with an empty think block is about level with plain. A cycle costs about 39 ms, most of it the
8-token verify pass (about 25 ms, close to llama.cpp's pp8). Tree verify, adaptive blocks and a register-only
small-batch matvec were tried and not kept ([records](../bench/README.md)). Not yet run against Kapitsa in the
same hour, so E5's target is open.

E7 can run beside E2–E6, kernel by kernel; the claim "built by Amu selfhost" in full waits for it. Other machines
(Linux nodes, route C, other GPUs) come after the Mac leads, using the same Kotoba sources.

## Owner decisions (2026-11-08, all as proposed)

- **D1.** The directions in the table above are paused.
- **D2.** Hand-written MSL text inside Kotoba is accepted as the first GPU tier. The full "built by Amu selfhost" claim
  waits for E7 (KIR v2 → MSL); doing E7 first would have delayed the first token by the whole of E7.
- **D3.** The headline model for E6 is Qwen3.5-4B 4-bit first: Kapitsa publishes it on an M1 and it fits any Mac.
- **D4.** Kapitsa, MLX and llama.cpp run on the developer Mac as comparison engines in E0/E5 benchmarks only, recorded
  with versions, never on the engine's path or in a gate.

## Tracking

Linear: JUN-1010 (this plan). The steps reuse JUN-804 (Mac engine) and JUN-740 (serving) where they match.
