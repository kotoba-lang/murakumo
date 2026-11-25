# Inkling 2 Mini and Ternary Bonsai 2.5 27B

Public benchmark snapshot for 2026-11-25 JST. These measurements come from
the linked publishers and model users. Murakumo did not run either model for
this comparison. See the [benchmark index](benchmarks.md) for Murakumo runs and
the [agent benchmark snapshot](agent-benchmarks-2026-11-25.md) for ALE and other
OpenRouter endpoints.

## Coding quality and footprint

| Metric | Inkling 2 Mini | Ternary Bonsai 2.5 27B |
| --- | --- | --- |
| Architecture | Looped MoE, 26.4B total, 3.4B active per pass and 5.4B weight activations per token including loops | Qwen3.8 27B hybrid-attention architecture, ternary weights at 1.76 bits/weight |
| Weights | About 20 GB, mixed NVFP4 and MXFP8 | 5.93 GB for the smallest text weights reported in the model card; other packs are larger |
| Context limit | About 1M tokens | 262,144 tokens |
| SWE-bench Verified | 79.2% | 75.6% |
| DeepSWE v1.1 | 48.2% | 27.0% |
| Terminal-Bench 2.1 | 71.6% | 60.1% |
| Artificial Analysis Intelligence Index | 20 | No entry located at the snapshot date |

Sources: [Thinking Machines release](https://thinkingmachines.ai/news/inkling-2-mini/),
[Inkling checkpoint and hardware guidance](https://huggingface.co/thinkingmachines/Inkling-2-Mini),
[PrismML release](https://prismml.com/news/bonsai-2-5-27b),
[Bonsai model card](https://huggingface.co/prism-ml/Ternary-Bonsai-2.5-27B-GGUF),
and [Artificial Analysis](https://artificialanalysis.ai/models/inkling-2-mini/).

These are separate publisher runs. Inkling used Pi v0.83.0 on Terminal-Bench
2.1 and a bash-only harness on SWE-bench Verified. Both publishers report
mini-swe-agent for DeepSWE v1.1, with settings determined by each publisher.
Inkling has higher reported coding scores in these suites. Treat the gaps as
indicative until both are evaluated with the same harness, budget, and model pack.

In PrismML's suite, Bonsai 2.5 reached 90.5 on AppWorld, up from 51.2 for
Bonsai 2. On five tool-use benchmarks it averaged 77.2, compared with 77.7
for Qwen3.8 27B FP16. [PrismML tool-use evaluation](https://huggingface.co/prism-ml/Ternary-Bonsai-2.5-27B-GGUF)

## Local inference speed

Decode and prefill are in tokens/s. Time to first token (TTFT) is elapsed
time to the first generated token. The paired Mac report used one machine
and input. The other rows are separate reports; the workload, quantization,
runtime, and draft settings vary.

| Hardware and source | Model and configuration | Decode | Prefill or TTFT |
| --- | --- | --- | --- |
| M5 Pro Mac mini, 24 GB, [paired Python prompt report](https://news.ycombinator.com/item?id=50633922) | Inkling UD-Q3_K_XL, 16K context in the reporter's configuration | 27.6 | 285.3 prefill, 1.21 s TTFT |
| M5 Pro Mac mini, 24 GB, [same report](https://news.ycombinator.com/item?id=50633922) | Bonsai Q2 GGUF; the reporter separately reported fitting 128K | 22.3 | 222.9 prefill, 2.12 s TTFT |
| M4 Pro MacBook Pro, 24 GB, [Locals](https://github.com/Generated-Computers/Locals/blob/main/docs/benchmarks/README.md) | Inkling converted MLX weights with Locals kernels and drafting | 36.0 prose, 40.3 plain-reply code, 59.6–75.6 tool-driven file output | — |
| Apple M4 Pro, [PrismML](https://huggingface.co/prism-ml/Ternary-Bonsai-2.5-27B-GGUF) | Bonsai PQ2_0 on Metal, with and without MTP | 22.9 off, 25.3 on, 29.2 on with kernels in testing | 126 at 2K tokens with kernels in testing |
| Apple M5 Pro, [PrismML](https://huggingface.co/prism-ml/Ternary-Bonsai-2.5-27B-GGUF) | Bonsai text-only MLX 2-bit and llama.cpp on the same machine | 21.7 MLX, 30.2 llama.cpp | 348 MLX, 416 llama.cpp at 512 tokens |
| RTX 5090, [NInfer](https://github.com/iamwavecut/ninfer-all/blob/master/docs/performance.md) | Inkling UD-IQ4_XS, same GGUF in NInfer and llama.cpp | 182.1 NInfer, 96.9 llama.cpp | 6,664 NInfer, 2,318 llama.cpp at 10K tokens |
| RTX 5090, [Strata release notes](https://github.com/Niko1221/Strata/releases) | Inkling with DSpark | Up to 250.3 on one request | — |
| RTX 5090, [PrismML](https://huggingface.co/prism-ml/Ternary-Bonsai-2.5-27B-GGUF) | Bonsai PTQ1_0 on the PrismML runtime | 174 off, 305 with MTP, 312 with MTP and kernels in testing | 4,173 at 2K tokens with kernels in testing |
| RTX 4090, [PrismML](https://huggingface.co/prism-ml/Ternary-Bonsai-2.5-27B-GGUF) | Bonsai PTQ1_0 on the PrismML runtime | 111 off, 204 with MTP | 3,014 at 2K tokens with kernels in testing |
| H100, [PrismML](https://prismml.com/news/bonsai-2-5-27b) | Bonsai with MTP and kernels in testing | 291 | About 3,200 at 2K tokens |

MTP and DSpark use draft tokens to accelerate generation. Gains vary with
the text being generated. Total time for an agent task also includes prompt
processing, reasoning tokens, tool calls, and retries. The declared context
limit requires room for the KV cache, activations, and any drafts. It can be
larger than what fits on a particular machine.

## API and deployment

- **16 GB Apple Silicon:** Bonsai's smaller text checkpoint leaves more
  room for the runtime and context. Confirm the context budget and chosen
  pack on the target machine.
- **24 GB Apple Silicon:** Published runs demonstrate both models with
  particular packs. The paired report saw slightly higher decode for Inkling
  at short context and more context headroom with Bonsai.
- **32 GB or more Apple Silicon:** Thinking Machines lists this as its
  supported local baseline for the official checkpoint. Inkling is the first
  candidate here for difficult coding based on the reported benchmarks.
- **OpenRouter:** The dated provider row for Inkling on Prime Intellect
  Standard showed 146 output tokens/s at $0.075 input and $0.30 output per
  million tokens. The public catalog listed earlier Bonsai 2 versions.
  A Bonsai 2.5 entry was absent from the catalog snapshot.

[Official hardware guidance](https://huggingface.co/thinkingmachines/Inkling-2-Mini)
· [Paired Mac report](https://news.ycombinator.com/item?id=50633922)
· [OpenRouter Inkling endpoint](https://openrouter.ai/thinkingmachines/inkling-2-mini)
· [OpenRouter public catalog](https://openrouter.ai/api/v1/models)
