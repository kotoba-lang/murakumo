# ADR-260824: expose the A100 Qwen3.8 aggregate-throughput profile

- Status: accepted
- Date: 2026-08-24
- Continues: ADR-260821b, ADR-260823
- Deployment: `tools/modal-throughput/qwen38_a100_server.py`

## Decision

Expose `qwen3.8-27b-throughput` through
`https://api.murakumo.cloud/v1/chat/completions` as an opt-in, text-only Modal
profile. Keep `murakumo-main` as the default and keep
`qwen3.8-27b-fastmtp-aggressive` as the single-request FastMTP profile.

The throughput profile pins:

- Modal `A100-40GB`, one GPU per container;
- `gratex/Qwen3.8-27B-W4A16-g128-sym-GPTQ` at revision
  `349f65375fc2b85c289c9c0604f7a4ec26c6b17f`;
- vLLM 0.27.1, GPTQ-Marlin, no speculative MTP, FP8 KV cache;
- the model-native 262,144-token context, 8,192 batched tokens, and 128
  scheduler sequences;
- 64 concurrent HTTP inputs, with the measured aggregate peak at 64 requests;
- zero minimum and two maximum containers, with a 300-second scale-down window.

The direct Modal origin is bearer-protected. The key remains in the Modal and
Cloudflare secret stores and is not passed in the vLLM process arguments.

## Evidence

The same-stack A100 search measured 1,242.7 aggregate output tok/s for 64
simultaneous requests (276 prompt tokens and 512 generated tokens each). An
independent repeat measured 1,235.6 tok/s. BF16 KV measured 1,211.8 tok/s and
MTP reduced the aggregate peak, although MTP remains faster for a single
request.

Production verification through `api.murakumo.cloud` returned HTTP 200 with:

- response model `qwen3.8-27b-throughput`;
- content `MURAKUMO_A100_OK`;
- 25 prompt, 10 completion, and 35 total tokens;
- 260.5 seconds for a post-deploy cold request with saved AOT artifacts;
- 0.929 seconds for the immediate warm verification request.

The first compile took 208.9 seconds and was persisted in the vLLM cache
Volume. The next container loaded the AOT compile in 19.0 seconds. Cold start
still includes model loading, profiling, and CUDA Graph initialization, so this
profile optimizes warm aggregate throughput rather than first-token latency.

## Gateway consequences

The public catalog advertises all three model choices. Explicit hosted-model
requests do not fall back to a different checkpoint when their Modal origin is
loading or unavailable. The gateway retries Modal's loading response for up to
12 minutes so the request keeps a scale-to-zero container alive through its
first compile.

`chat_template_kwargs.enable_thinking=false` is available when callers want
only the final response. Without it, a small `max_tokens` value may be consumed
by the model's reasoning output before a final answer is emitted.

## Context policy update

Murakumo Cloud uses each deployed model's real maximum context as its default
serving window. The throughput profile initially launched at 16,384 tokens to
match the peak-search benchmark, then moved to Qwen3.8's native 262,144-token
window. `max_num_batched_tokens=8192` remains a scheduler batching bound, not a
request context limit. Short-request aggregate throughput and maximum context
are therefore separate service properties.

Production validation on 2026-08-24 sent a 20,017-token prompt through
`api.murakumo.cloud` to `qwen3.8-27b-throughput`. The warm request returned HTTP
200 with the exact model id and `OK` in 7.575 seconds, proving that the previous
16,384-token boundary is no longer active. The first post-deploy request took
223 seconds while vLLM compiled and warmed the new serving shape.
