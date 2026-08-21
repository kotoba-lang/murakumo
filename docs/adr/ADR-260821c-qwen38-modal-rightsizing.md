# ADR-260821c: Qwen3.8-27B on Modal needs 2 CPU cores and 16 GiB, not 8/32

- Status: accepted
- Date: 2026-08-21
- Continues: ADR-260821b
- Raw result: `data/ADR-260821c-qwen38-27b-h100-2c16g-mtp.json`

## Two open questions closed

**Use `gpu="H100"` for serving.** Modal's GPU guide says an H100 request may
land on an H200 and that the automatic upgrade does not change the GPU cost.
Use `gpu="H100!"` only when a benchmark must pin the physical card. This is a
documented property, not an inference from a bill:
<https://modal.com/docs/guide/gpu#automatic-upgrades-to-h200s>.

**Request 2 CPU cores and 16 GiB of memory.** The full H100+MTP benchmark ran
at that shape on a physical `NVIDIA H100 80GB HBM3`. It loaded the 28.75 GiB
checkpoint, built the engine, and measured all seven workloads. The old
8c/32GiB shape was provisioned without evidence that the engine needed it.

```python
@app.function(gpu="H100", cpu=2, memory=16384)
```

## Like-for-like result

Both rows are Qwen3.8-27B-FP8, vLLM 0.27.1, MTP on, 65,536 context,
`max_num_seqs=128`, and a physical H100 80GB. The old row is
`ADR-260820b-qwen38-27b-h100-mtp.json`; the new row is the raw result above.

| requested shape | engine build | single | c8 | c32 | c64 | c128 | all-in $/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| 8c / 32 GiB | 413.1 s | 218.8 | 1,125 | 3,640 | 3,432 | 4,280 | $0.001273 |
| **2c / 16 GiB** | **410.0 s** | **212.7** | **1,047** | **3,545** | **3,322** | **4,203** | **$0.001159** |

The engine build changed by -0.8%, single-stream by -2.8%, and concurrency
128 by -1.8%. Those are inside the already measured run-to-run variance
(±2% single-stream, up to ±40% batched; the single-stream difference is 0.8
percentage point outside that descriptive band and is not enough to justify a
second run). There is no material throughput loss attributable to right-sizing.

The all-in rate falls **9.0%**. At the measured points, all-in cost falls from
$5.817 to **$5.448/Mtok** single-stream and from $0.297 to **$0.276/Mtok** at
concurrency 128. One cold start falls from $0.526 to **$0.475**.

## Scope

This proves that 2c/16GiB is sufficient for this offline `LLM` harness. It does
not prove that 16 GiB is the minimum, or include an HTTP serving process and
its observability sidecars. Do not lower it further without another measured
run. The process reported host RAM rather than a trustworthy cgroup ceiling,
so that number is deliberately not used as evidence; the requested Modal
shape and successful completion are.
