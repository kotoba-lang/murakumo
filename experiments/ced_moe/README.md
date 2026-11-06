# JUN-936: Hy / MLX from-scratch CED + CSA2-style Sparse MoE

Experimental training code, explicitly requested in Hy by the owner on
2026-11-06. It is independent of the existing Murakumo inference paths.
Hy's Python runtime and MLX dependency are authorized for this experiment;
this does not establish a production runtime or fleet qualification.

## Model implemented

Text-only, random initialization; tied token embedding / output head;
RMSNorm, RoPE, shared SwiGLU expert and top-k routed SwiGLU experts.
`gather_mm` executes selected expert matrices, not all experts followed by masking.
The reduced CSA2 design has learned non-overlapping causal KV compression,
Full / Reindex / Reuse modes, per-layer local sliding-window KV, and
decoder global KV projected from the final causal encoder state. Shared
states remain in the autodiff graph. The discrete index selection is detached;
an explicit detached dense-attention target trains the indexer.

This reproduces the four selected mechanisms in a smaller research design,
not the complete DeepSeek-V4.1 implementation. Differences include one indexer
head, conventional RoPE/GQA projections, AdamW as the initial optimizer,
and an auxiliary router balancing objective. Engram, mHC, vision, FP4 KV,
hierarchical decoder candidate restriction and bounded replay are not included.
Training runs the complete encoder and decoder; inference prefill savings
must not be applied to training FLOPs.

| Preset | Total parameters | Selected parameter upper bound / token |
| --- | ---: | ---: |
| 1b | 1,043,698,432 | 123,572,992 |
| 3b | 3,003,341,824 | 313,744,384 |

The selected count includes the complete embedding and shared parameters.
It is a logical weight count, not measured FLOPs, resident memory or a
30B/3B-active scaling identity. In particular, changing expert count alone
cannot justify scaling training time by total parameter count.

## Numerical controls

Use a task-specific virtual environment outside the repository, install the
pinned `requirements.txt`, then run:

```sh
hy experiments/ced_moe/check.hy
```

The check runs all three CSA2 modes and a causal encoder/decoder split.
It checks future-token invariance (with a perturbation positive control),
finite gradients, nonzero compressor/indexer/router/source gradients, and
a shared-KV central-difference VJP. It is a small numerical correctness check,
not a billion-parameter training result or a quality benchmark.

## Research and execution record

- [JUN-936](https://linear.app/kotobalabs/issue/JUN-936): implementation and pilot.
- [JUN-931](https://linear.app/kotobalabs/issue/JUN-931): parent evaluation plan.
- [DeepSeek-V4.1 report](https://arxiv.org/html/2609.19969v1), sections 2.2,
  2.3 and 3.1.2: source of the architecture and shared-state gradient requirements.
- [MLX distributed communication](https://ml-explore.github.io/mlx/build/html/usage/distributed.html):
  the TCP ring does not support arbitrary point-to-point send/receive.
  Do not claim PP/EP support merely because an all-reduce backend exists.

As of the first code unit: local M1 Max 32 GiB numerical checks only. No
M4 fleet training, 1B/3B convergence, distributed scaling, or measured 30B
duration has been established. Local results must stay labeled by hardware.

The first local commit was prepared against `kotoba-lang/torch`, whose remote
is archived. Only this experiment was transferred to the active Murakumo repo;
the archived checkout and its local commit were preserved.
