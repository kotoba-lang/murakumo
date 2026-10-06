# Native vision admission

An owned vision lane remains a `text` execution lane because its output is text.
Declare `max-image-tokens` and `max-images` only after the backend enforces those
same limits. The gateway advertises and uses those observed limits. Resident
admission independently validates them before durable dispatch. Inline PNG/JPEG
image data is bounded by the HTTP body limit; remote image URLs are refused.

Image payload bytes do not consume text context. Admission reserves the
conservative serialized size of the request without image data, 256 template
tokens, and each declared image maximum plus 64 boundary tokens. Unqualified
text lanes refuse image blocks. Ordinary text admission keeps its existing byte
budget. Oversize transport and oversize text remain distinct refusal gates.

`deploy/vision/qwen3-vl-2b-instruct.json` pins the official source revision and
both weight hashes. Run the backend loopback-only with context 4096, one slot,
image min/max 1024 and prompt RAM cache disabled. Never advertise training
context as serving capacity. Use the existing signed topology and resident-owned
receipt protocol when adding qualified capacity; do not publish an unguarded
llama-server endpoint.

On 2026-10-06, candidate backends on Jacob (Metal M4) and K16 (Vulkan Radeon
680M) identified a red square despite a contradictory blue-circle prompt and
read a published story's bob haircut, coat, pale scarf, open map and fountain.
Jacob structured review approved the matching story and rejected a white image
with the same expected-scene prompt. Its rejection still repeated prompt details
in the description: the description alone is not evidence or an approval.
These are scoped perception checks, not general safety or identity certification.
Candidate qualification is not public fleet availability or producer readiness.
The producer's unattended visual-review hold remains until public routing,
replica failover and actual scheduled review/publish verification pass.

Validation:

- `kbb --backend sci --classpath src test/resident_policy_test.cljk`
- Build the canonical CLI with `npm run release:node`.
- `node --test test/multimodal_admission_test.mjs test/fleet_resilience_test.mjs test/replicated_topology_test.mjs`
