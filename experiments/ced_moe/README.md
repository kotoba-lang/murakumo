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
| 30b-target (shape accounting only) | 29,951,372,288 | 2,998,774,784 |

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

## Public-corpus training pilot

Owner correction on 2026-11-06: do not run training or GPU benchmarks on the
developer's current Mac. The examples below are for designated fleet training
hosts only, after admission. The local pilot figures below are historical
controls completed before that instruction, not permission for further local runs.

```sh
hy experiments/ced_moe/data.hy --output /path/to/task-data
hy experiments/ced_moe/train.hy --data /path/to/task-data --output /path/to/run \
  --preset tiny --steps 60 --seq 64 --memory-limit-gib 1 --max-seconds 180 --checkpoint
```

The initial corpus is WikiText-2 raw, immutable revision
`b08601e04326c79dfdd32d625aee71d232d685c3`. All three source parquet files
are checked against their published SHA-256 values (~7.75MB total). The 32K
byte-level BPE is learned from the training split only. The derived manifest
records source, tokenizer, and token-stream hashes. Exact duplicate paragraphs
are removed across splits; this is not semantic deduplication. Dataset attribution:
[Salesforce/WikiText](https://huggingface.co/datasets/Salesforce/wikitext),
CC-BY-SA-3.0 / GFDL as listed in its card. No corpus text or checkpoints are
committed. FineWeb-Edu is a larger follow-up corpus, not a downloaded prerequisite.

The runner uses FP32 optimizer/master state and BF16 or FP32 model weights,
finite gradient/loss checks, clipping, an explicit MLX memory cap, maximum
steps, a wall-clock budget checked between steps, and a task-output lock.
These are experiment limits, not qualification of the shared resource governor.
`bounded_trial.hy` supervises only its explicitly launched child process group
with a wall deadline. It requires an already admitted trial and is not an
alternative reservation or admission authority. The CLI's between-step budget
alone does not enforce a hard deadline during a long kernel or initialization.
Checkpoint directories are published by rename with component hashes; resume
rejects changed corpus/config/optimizer options or corrupted tensor files.

Only single-host and replicated DDP are implemented. DDP explicitly refuses
a singleton group when multiple hosts were requested. It neither pools memory
nor implements PP/EP/DiLoCo. Logical gradient bytes and a ring TX estimate are
labeled separately from physical wire counters, which remain unmeasured.

Local M1 Max 32GiB initial BF16 control: 2,807,616 parameters, 60 steps,
3,840 tokens, validation CE 10.41197 -> 8.65543, 8.9495s in the training phase
(429.07 tokens/s, including its periodic evaluation), MLX peak 104,003,136 bytes.
This includes initialization-era kernel warm-up in the first training step.
It establishes an operational learning control, not 1B/3B quality or sustained
M4 training throughput. A later code revision must be measured separately.

Independent FP32 uninterrupted-vs-resumed 3-step controls matched to maximum
absolute parameter difference 1.18e-5, with sampler state/options matching.
The initial BF16 controls differed by up to 0.004883. BF16 bitwise reproducibility
is **not** qualified; do not loosen the FP32 control to conceal that result.
`verify_resume.hy` compares weights, FP32 masters, moments and sampler state.

`calibration.hy` inventories measurements and currently emits
`insufficient_calibration` with a null 30B duration. A real estimate still needs
1B/3B runs, multi-host wire measurements, a capacity-qualified learner layout,
and a held-out configuration to measure prediction error. Scale compute by
measured work, optimizer/state traffic by total parameters, and PP/EP/shared-KV
traffic by the actual partition; do not multiply tiny-model tokens/s by node count.
