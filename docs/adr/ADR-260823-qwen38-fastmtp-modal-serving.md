# ADR-260823: serve the FastMTP GGUF on Modal; keep RTX PRO 6000 pending demand data

- Status: accepted
- Date: 2026-08-23
- Continues: ADR-260821b, ADR-260822
- Deployment implementation: `tools/modal-fastmtp/qwen38_fastmtp_server.py`
- Raw result: `data/ADR-260823-qwen38-fastmtp-modal-gpu-comparison.json`

## Decision

Expose `qwen3.8-27b-fastmtp-aggressive` through
`https://api.murakumo.cloud/v1/chat/completions`, backed by a scale-to-zero
Modal deployment. Keep the production GPU on RTX PRO 6000 for now. Do not
switch to H100 for this approximately 20 GB target-plus-draft-plus-projector
deployment, and do not enable two llama.cpp slots merely to increase
throughput.

A100 40GB is the cost-first alternative if observed demand shows that lower
single-request speed is acceptable. Here and in follow-up discussion,
"40GB" means Modal's explicit `A100-40GB` allocation.

The deployed boundary is:

- public model id: `qwen3.8-27b-fastmtp-aggressive`;
- target GGUF: Q4_K_P, approximately 17.9 GB;
- FastMTP draft GGUF: approximately 0.9 GB, three speculative tokens;
- BF16 multimodal projector: approximately 0.93 GB, checksum-pinned and passed
  to llama.cpp with `--mmproj` so OpenAI-compatible `image_url` inputs reach
  the model rather than being accepted by the gateway but dropped at serving;
- llama.cpp pinned at `4df29be4f4c3673f428170fda944a5b19f743bb8`
  with the model author's pinned patch;
- 32,768 total context tokens, one slot, four physical CPU cores, 32 GiB RAM;
- zero minimum and two maximum Modal containers, with a 300-second scale-down
  window;
- direct Modal origin protected by a secret; the public gateway injects it;
- merged in `kotoba-lang/murakumo` PR #323 (`c31e705`) and routed by
  `network-awai/local-murakumo` PR #141 (`856cb7b`).

The public model list and completion route were verified after merge. A cold
public request was transparently retried by the gateway and returned HTTP 200
in approximately 17 seconds.

## Same-stack GPU measurement

These results use the GGUF llama.cpp FastMTP stack above. They must not be
combined with the vLLM FP8 figures earlier in this ADR series.

| GPU | 256-token decode | relative to RTX | measured cold/load | all-in active rate |
|---|---:|---:|---:|---:|
| A100 40GB | 63.52 tok/s | 0.82x | 21.67 s | $2.543/h |
| RTX PRO 6000 | 77.47 tok/s | 1.00x | ~17 s public cold request | $3.476/h |
| H100 80GB | 96.39 tok/s | 1.24x | 17.02 s | $4.394/h |

H100 did not improve the short 41-token run: 98.13 tok/s against the RTX
production range of 95.63–99.16 tok/s. On the 256-token run it saved about
646 ms of model decode, but cost 26% more per active second. One warm,
single-request million generated tokens derives to approximately $11.12 on
A100 40GB, $12.46 on RTX PRO 6000, and $12.66 on H100. Those derived values
exclude prompt processing, cold load, idle retention, and network time.

This result is specific to single-request Q4 llama.cpp execution. The H100's
capacity and batching advantages are not exercised by a 19 GB model with one
slot. The H100 and A100 measurements are one-shot, and different FastMTP draft
acceptance counts explain part of the observed spread.

## Cold start costs less than idle retention

[Modal bills per second with no minimum increment](https://modal.com/docs/guide/billing).
At the [2026-08-22 published rates](https://modal.com/pricing), adding the
requested four CPU cores and 32 GiB RAM gives:

| GPU | measured/estimated load charge | charge for 300 idle seconds |
|---|---:|---:|
| A100 40GB | $0.0153 | $0.2119 |
| RTX PRO 6000 | $0.0164 | $0.2896 |
| H100 | $0.0208 | $0.3661 |

Therefore the current five-minute warm window, not the model load itself,
dominates a solitary request's compute bill. Reducing `scaledown_window` to
60–120 seconds is the next cost lever if production traces show that requests
rarely recur within five minutes. Do not change it from benchmark evidence
alone: the same trace must measure the user-visible cost of an approximately
17–22 second cold start.

## Parallel requests

The production service currently has `--parallel 1` and
`@modal.concurrent(max_inputs=1)`. One container serves one request at a time,
while Modal may scale the function to two GPU containers. Thus two requests
can run at full per-request speed only after a second model replica is ready;
requests beyond that queue.

An explicit A100 40GB two-slot test accepted two requests at once, but did not
increase throughput:

| mode | per-request decode | aggregate decode | wall time for 2 x 256 outputs |
|---|---:|---:|---:|
| one slot | 63.52 tok/s | 63.52 tok/s | sequential |
| two slots | 32.77–33.04 tok/s | 60.90 tok/s | 8.41 s |

With `--ctx-size 32768 --parallel 2`, llama.cpp assigned 16,384 context tokens
to each slot. The test therefore traded per-request latency and context length
for simultaneous progress, while slightly reducing aggregate throughput. Keep
one slot per GPU. If bursts require low latency, scale replicas and pay the
extra GPU load; if they allow queueing, retain one replica and serialize.

## Comparison with hosted frontier agents

The measured 63–99 tok/s is model-native completion throughput. It includes
`reasoning_content`; it is neither visible-text speed nor completed coding
work per second. Tokenizers also differ across model families.

[Anthropic publishes Claude Opus 4.8 Fast mode as 2.5x its Standard
mode](https://www.anthropic.com/news/claude-opus-4-8), and [OpenAI publishes
GPT-5.6 Sol Fast mode as up to 2.5x
Standard](https://openai.com/index/advancing-the-price-performance-frontier-with-gpt-5-6/).
Neither source provides one absolute output-tokens-per-second number that makes
an honest conversion to this llama.cpp result. Opus and Sol additionally
include model reasoning, network scheduling, agent orchestration, tool calls,
and retries. Use task-level evaluations for that comparison, not raw decode
rate.

## Consequences and resume point

1. Leave production on RTX PRO 6000 until request arrival and queue traces
   justify a change.
2. If cost per warm generated token is the constraint, deploy an A100 40GB
   canary with one slot and accept roughly 18% lower single-request speed.
3. If long-output latency is the constraint, H100 gives about 24% more decode
   throughput in this one-shot test; short replies and cold load did not
   improve materially.
4. Do not use one-GPU/two-slot A100 serving for throughput. Use replica
   autoscaling for truly parallel latency, or queue for cost control.
5. Before changing the 300-second warm window, measure the distribution of
   inter-arrival gaps at the public gateway.

## Standing caveats

- Quality was not measured. A quantized aggressive 27B model is not a
  capability substitute for Claude Opus or GPT-5.6 Sol on long-horizon coding
  and tool-use work.
- H100 and A100 results are n=1. Repeat before making a capacity purchase or
  promising an SLO.
- FastMTP acceptance varied between runs, so the GPU is not the only changing
  contributor to observed tok/s.
- Modal prices are time-sensitive. The raw record pins the rates used for the
  arithmetic and links the pricing source.
- Rotate the Hyperstack and RunPod credentials that passed through the task
  conversation; no credential is recorded here.
