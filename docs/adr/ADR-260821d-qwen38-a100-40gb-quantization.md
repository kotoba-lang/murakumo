# ADR-260821d: use W4A16, not FP8, for Qwen3.8-27B on A100-40GB

- Status: accepted
- Date: 2026-08-21
- Continues: ADR-260821b
- Raw FP8 result: `data/ADR-260821d-qwen38-fp8-A100-40GB.json`
- Raw W4A16 result: `data/ADR-260821-qwen38-w4a16-A100-40GB-mtp.json`

## Decision

If this model must run on an A100-40GB, use
`dbirks/Qwen3.8-27B-W4A16-AutoRound`, not the public FP8 checkpoint. Both fit,
but FP8 is slower at every measured workload because Ampere has no native FP8
compute. vLLM says it is using weight-only FP8 compression through Marlin and
warns that compute-heavy workloads may degrade.

Do not generalise this to Hopper. H100 and H200 have native FP8 and the FP8
checkpoint remains the serving decision there.

## Like-for-like measurement

Both rows are a physical `NVIDIA A100-SXM4-40GB` (39.49 GiB), vLLM 0.27.1,
MTP on, 16,384 context, `max_num_seqs=128`, and Modal's 8c/32GiB side shape.

| quantisation | engine build | single | c8 | c32 | c64 | c128 | long 4k |
|---|---:|---:|---:|---:|---:|---:|---:|
| **W4A16** | **610 s** | **108.1** | **507.5** | **959.1** | **865.9** | **943.7** | **68.7** |
| FP8 via Marlin | 687 s | 69.2 | 222.8 | 300.3 | 325.1 | 328.2 | 51.2 |

W4A16 is **1.56x faster single-stream** and **2.88x faster at concurrency
128**. FP8 also takes 13% longer to build the engine. The gap is much larger
than the measured run-to-run noise, so no repeat is needed to decide.

At Modal's A100-40GB rate and the shared 8c/32GiB side charge, W4A16 costs
$7.020/Mtok single-stream and $0.804/Mtok at concurrency 128. FP8 costs
$10.966 and $2.312 respectively. Quantising to fewer bits wins on both latency
and cost here.

## The scheduling caveat is now measured twice

Modal documents `A100-40GB` as the specific 40GB request, yet the first FP8
attempt returned `NVIDIA A100-SXM4-80GB`; this retry returned the intended
40GB card. The request string is therefore not sufficient evidence for a
like-for-like result. Every benchmark must record and compare `device` and
`vram_total_gib`, and a substituted card is a different row, not a replicate.
