# ADR-260822: use Modal, not generic RunPod Serverless Flex, for bursty Qwen3.8-27B

- Status: accepted
- Date: 2026-08-22
- Continues: ADR-260821b, ADR-260821c, ADR-260821f
- Raw results:
  `data/ADR-260822-qwen38-27b-modal-h100-serverless-mtp.json`,
  `data/ADR-260822-qwen38-27b-runpod-serverless-flex.json`
- Reproduce with: `tools/modal-bench/qwen38_27b_bench.py`,
  `tools/runpod-serverless-bench/`

## Decision

Use **Modal for request volume with gaps**. Keep Hyperstack for resident load.
Do not put the generic custom-image RunPod Serverless Flex path in front of
this model yet.

RunPod Flex and the right-sized Modal container cost almost exactly the same
while active: $0.00116000/s against $0.00115872/s. Modal completed the pinned
64k/MTP plan. RunPod allocated a healthy H100 worker but did not make the queue
handler ready inside a deliberate 25-minute limit, spent $1.7611, and produced
no token. There is therefore no measured RunPod throughput with which to claim
a cost advantage.

This is a decision about **generic RunPod Serverless with a custom vLLM image**.
It does not measure RunPod's console-configured cached-model mount. That path
remains the only plausible reason to revisit the decision.

## Like-for-like setup and outcome

Both paths pinned `Qwen/Qwen3.8-27B-FP8` revision
`017b9c7af6b5689d5dd426a76e0bc077eb5ca20a`, vLLM 0.27.1, FP8 KV cache,
MTP 3, 65,536 context, `max_num_seqs=128`, CUDA graphs, and one H100-class
worker. Both scale to zero.

| | Modal H100 | RunPod Serverless Flex H100 |
|---|---:|---:|
| active rate | $0.00115872/s | $0.00116000/s |
| hourly equivalent | $4.1714 | $4.1760 |
| handler / engine ready | **374.6 s** | **not ready at 1,500 s** |
| single-stream | **212.7 tok/s** | could not measure |
| concurrency 128 | **4,204.6 tok/s** | could not measure |
| c128 all-in | **$0.276/Mtok** | could not measure |
| observed experiment spend | Modal workspace credit | **$1.7611 cash** |

The Modal harness completed in 452.1 seconds including every workload. Its
fresh measurement agrees with the earlier right-sized result: 212.7 tok/s
single-stream and 4,204.6 tok/s at concurrency 128.

## What happened on RunPod

The first endpoint was pinned to US-TX-3 so that a 50 GB persistent model cache
could be attached. The request remained queued for more than two minutes with
zero workers assigned. This was cancelled before GPU execution.

The second endpoint removed the region and volume constraints. RunPod assigned
an H100 worker in about 40 seconds, after which the worker remained `running`
and not `unhealthy`. The submitted job nevertheless remained `IN_QUEUE`; it
never received `delayTime` or `executionTime`, meaning the Python queue handler
had not finished its import-time model download and vLLM construction. It was
cancelled at 1,500 seconds to bound spend.

The result is deliberately kept as `could-not-measure`. Treating the active
rate as token cost would be false: this run paid for initialization and emitted
zero tokens.

## Operational consequence

For irregular requests, billing granularity is not the deciding axis. Both
vendors can bill active compute by the second and scale to zero. The deciding
axis is whether a worker becomes useful before the next gap:

- **Modal:** measured cold start about 6.2 minutes; reliable completion; use for
  bursty and queued jobs.
- **RunPod Flex, generic image:** no useful response inside 25 minutes in this
  run; do not use until cached-model startup is separately proven.
- **Hyperstack:** VM billing, not request billing; use only when utilization is
  high enough to keep the model resident.

One more RunPod experiment would be meaningful: configure the documented
cached-model feature in the RunPod console, prove the first and second cold
starts, and then run the same full plan. A normal network volume is not a
drop-in substitute because pinning it to one data center reduced H100
availability in this test.

## Cleanup

Both queued jobs were cancelled. The two endpoints, temporary template,
50 GB network volume, and temporary GHCR registry authentication were deleted.
The subsequent endpoint list was empty and RunPod reported current spend of
$0/hour. Account balance moved from $9.3343901559 to $7.5733225541. No secret
is present in the image harness, raw data, ADR, or Git history; rotate the
RunPod API key because it passed through the task conversation.
