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
Those initial candidate checks did not establish public fleet availability or
producer readiness. The following public qualification records the later
routing, failover and scheduled review/publish verification.

Validation:

- `kbb --backend sci --classpath src test/resident_policy_test.cljk`
- Build the canonical CLI with `npm run release:node`.
- `node --test test/multimodal_admission_test.mjs test/fleet_resilience_test.mjs test/replicated_topology_test.mjs`

## Public qualification on 2026-10-06

Signed topology revision `2026100604` exposes two supervised, authenticated
vision replicas: `jacob-vision` on Metal M4 and `k16` on Vulkan Radeon 680M.
Loopback inference is reached through resident receipts and the two existing
fleet gateways; no public llama-server listener was added. K16 vision shares
its admission group with Mishima, so active shared-GPU work may legitimately
refuse new vision admission with `executed:false`. Do not bypass this mutex.

The deployed public Murakumo API (version
`2bf4f990-8f8e-4732-968a-152185fa1d3f`) answered real inline red-square pixels
correctly despite a contradictory blue-circle prompt on Jacob, job
`jacob-vision_vision_85a95ec1d9a541d18da455c24de22598`. With Jacob's idle
vision backend deliberately stopped, the same public route succeeded on K16,
job `k16_vision_95836363730a4d5ab2a7b64fdfa0a2bc`, and Jacob was restored
to two ready replicas. The first capacity check while K16 was occupied correctly
refused execution; successful failover required a free shared-GPU slot.

Bounded native review approved the clothed story fixture and rejected a white
fixture with the same expectation. Individual booleans can still be wrong or
contradictory, so approval requires a complete decision and all contract checks
to agree; generated descriptions alone must never approve an image. These
smokes do not establish general classifier accuracy, age or exact face identity.

The canonical Hermes hourly runner now calls the same stdio MCP
`producer_review`, `producer_publish` and `producer_verify_post` tools, with
no external or free-alias provider. The native-reviewed story
`https://oppai.fans/#post/producer-20261006T10Z` was deployed at version
`5f081fa5-f672-4802-aae0-c22feb137fff`; its public PNG matched SHA256
`f0a404c319ad5790f98c182d30a087607b87d96c91f05af9f04a2671ae960e27`.
The scheduler verification succeeded and a separate actual browser displayed
the clothed character, coast and lighthouse. Browser proof is not fabricated
by the runner. This production loop is a deterministic Hermes job; native
Itonami Bot execution and the Itonami public status quorum remain separate.
