# Engine profiles: one kernel library, one plan per model

Owner direction 2026-11-15 (Linear AWAI-2938): murakumo should not grow one hand-tuned program per model. It should
combine shared parts into the best plan for each model, chosen through a model profile. This document is the design;
the Qwen3.5 Metal route is its first user. The Qwen2.5 program (`bench/kernels/qwen2-metal/`, E1–E3) stays as it is
until it is ported (see "Migration").

## Layers

```
engine/src/murakumo/engine/
  base.kotoba        boot-function wrappers (memory, C calls, dlsym, Objective-C messages, stderr); SHA-256 of the
                     model file against the pinned digest before any other read; GGUF index (KVs, tensor infos,
                     typed lookups, tensor sizes for every supported dtype)
  metal.kotoba       pipelines, one dispatch, command buffers, waits
  profile.kotoba     model profiles: architecture -> dims, layer kinds, tensor table (offset and dtype per role)
  kernels.kotoba     the kernel library: one MSL source and the pipeline order (kernel ids)
  kernels/*.kotoba   MSL text by concern (quant matvec per dtype, norms, attention, delta net, head)
  model/<arch>.kotoba  the per-architecture step encoder: walks the profile's layers, picks kernels from the plan
bench/kernels/<arch>-metal/  entry programs (templates with the path, digest and prompt) and their gates
```

Everything is Kotoba compiled by `bin/amu compile ENTRY --source-path engine/src --runtime-profile
amu-program-metal` (closed module linking, amu ADR 0005). The kernels are still hand-written MSL text (engine-plan
D2) until E7 makes Amu emit them.

## The profile

A profile is built at load time from the GGUF, not from a hard-coded table of offsets:

- **architecture** (`general.architecture`): only listed architectures are accepted (`qwen35` today; `qwen2` when it
  is ported). Anything else is refused before the data region is read.
- **dims**: layers, widths, heads, head dims, RoPE dims and base, norm epsilon, vocabulary, and the delta-net state
  sizes, read from the architecture's KVs. Each is checked against the shapes the kernels support: a model is either
  in a supported envelope or refused, never run on a guess.
- **layer kinds**: one entry per layer, for example `qwen35`'s full-attention interval making layers 3, 7, … gated
  attention and the rest Gated DeltaNet. A model mixes layer kinds freely, and the encoder dispatches per kind.
- **tensor table**: for every (layer, role) the data offset, **dtype** and dims, each checked for span and shape. The
  dtype is taken from the file: a Q4_K_M file mixes Q4_K, Q5_K, Q6_K and Q8_0 per tensor, and the same role can have
  different dtypes in different layers.

## The plan: picking kernels per tensor and per phase

The plan maps each (tensor, phase) to a kernel variant. Today it is code in the step encoder, not data. Its rules:

| Decision | Chosen from | Variants |
| --- | --- | --- |
| matvec kernel (decode) | the tensor's dtype | `mv_q8_0`, `mv_q6_k` (one template, one instantiation per dtype); `mvk_q4_k`, `mvk_q5_k` (16-byte nibble loads covering a low- and a high-nibble sub-block). Each simdgroup owns 4 rows over the whole row; Q8_0 and Q6_K rows over 3,072 values are split between 2 simdgroups. Two tokens at once (the prompt, and the verify pass of drafted decoding) read the weights once: `mv2_q8_0`, `mv2_q6_k` keep both tokens' sums per lane; `mvk2_q4_k`, `mvk2_q5_k` use 8-byte loads so both tokens' activations fit in registers. `mv_bf16`/`mv2_bf16` read BF16 rows; `bfq8` converts BF16 rows to Q8_0 at load. Three to 16 tokens (the prompt; a drafted block's verify) use simdgroup matrices: `mm_<dtype>` dequantizes 64 x 64 weight tiles to half in threadgroup memory and multiplies them by the tokens' float activations, split along the row when the matrix has too few row tiles to fill the GPU (`mmr` adds the partials in order) |
| embedding row | the embedding's dtype | `emb_<dtype>` |
| layer kernel set | the layer kind | gated attention (q\|gate, q/k norm, partial RoPE, sigmoid gate); GQA attention; Gated DeltaNet (conv1d, delta rule and gated norm fused in one threadgroup per value head; `dnfn` takes every token of a pass in one dispatch, the state in registers) |
| prefill | dtype, shape and memory | Qwen3.5: passes of up to 16 tokens through `mm` (in-place dequantization), only the last prompt token through the head. Qwen2.5 (E3): f16 copy + tiled GEMM, 64 x 128 tiles, split k for short n |
| decoding method (E5) | what the model ships | plain; MTP head (Qwen3.5-4B: one draft token per pass, verified by a two-token pass; the recurrent states keep three slots so a rejected draft's state is never written over the one before it); DFlash block drafter (Qwen3.5-4B: a 6-layer Qwen3 stack over the main model's rows after layers 1, 5, .., 29 drafts a block of up to 16 tokens; one multi-token pass verifies it; the delta-net states are replayed over the accepted tokens from the inputs the verify pass saved, `dnr`); chosen per model when a head or drafter is present |

A new model then needs only three things: a profile (KVs, layer kinds, tensor roles), any missing layer-kind kernels,
and a gate. Every quant format and kernel already in the library is reused.

## Gates

Every entry has a gate that proves its profile end to end:

- greedy tokens equal to a pinned oracle;
- must-fail mutants for each layer kind it uses;
- a digest that is off by one character is refused;
- a file of another architecture is refused.

The oracle for `qwen35` is `bench/kernels/qwen35-metal/ref_forward35.py`. It agrees with llama.cpp.

## Migration

- The Qwen2.5 program keeps its gate and numbers while the library grows.
- Porting it means a `qwen2` profile, its kernels moved into `kernels/`, and its gate rerun unchanged. This is a
  follow-up, not part of the first Qwen3.5 slice.
- The prefill work (f16 weight copy, tiled GEMM, split k) moves into `kernels/gemm` when Qwen3.5 prefill starts.
