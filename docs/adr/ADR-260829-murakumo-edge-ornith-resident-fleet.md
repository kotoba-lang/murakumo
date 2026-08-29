# ADR-260829: make Ornith 1.5 9B the resident `murakumo-edge` model

- Status: accepted
- Date: 2026-08-29
- Scope: Apple M4 16 GiB edge replicas and `api.murakumo.cloud`

## Decision

Serve `ornith-ai/Ornith-1.5-9B-GGUF` as the OpenAI-compatible
`murakumo-edge` model. Pin Hugging Face revision
`abdd624b12ebf020b767fff532ff44fe552b28c3`, with these exact artifacts:

- `Ornith-1.5-9B-Q4_K_M.gguf`: 5,780,090,816 bytes,
  SHA-256 `70c112196e0b7023803c9762752e46d29e612a92c83f995bc3ba1ceb07e8fab6`;
- `mmproj-Ornith-1.5-9B-BF16.gguf`: 921,704,672 bytes,
  SHA-256 `626f9f90627402a6bf4a999111d0fbd69b5fcca7aa8ba089d69e5f10e8858e1d`.

Each replica runs llama.cpp b9334 (`192d8ae8b`) with one slot, Metal,
flash-attention, Q8 key/value cache, Jinja tool templates, the multimodal
projector, and a 65,536-token serving window. The checkpoint declares a
262,144-token native context; 65,536 is the admitted resident edge profile,
not a claim that the model itself stops there.

The inference plan is owned in layers rather than duplicated in deployment
scripts:

1. `num` decides exact unified-memory byte admission;
2. `torch` renders the admitted llama.cpp resident argv and resource plan;
3. `inference` owns the pinned Ornith artifact and OpenAI vision/tool contract;
4. `murakumo` renders the system LaunchDaemons and runs the model-scoped queue
   worker;
5. `local-murakumo` exposes the synchronous public route and isolates
   model-bound queue entries from legacy workers.

The resident processes are system LaunchDaemons, not GUI LaunchAgents:
`com.murakumo.edge-server` and `com.murakumo.edge-join`, both with an explicit
unprivileged `UserName`, `RunAtLoad`, and `KeepAlive`.

## Fleet boundary

The current authoritative Murakumo registry contains ten 16 GiB Mac minis:
asher, benjamin, dan, issachar, joseph, judah, levi, naphtali, simeon, and
zebulun. The eleventh canonical `/ready` entry is `gad`, which is not a 16 GiB
Mac mini. `jacob` appears in Tailscale history but was never enrolled in
`fleet.edn` and has been offline since 2026-07-06. Therefore this deployment
does not turn the requested phrase "11 16 GiB Macs" into an unsupported
inventory claim.

The initial batch contained six minis reachable directly over Tailscale;
Judah returned during deployment and provided a verified same-LAN route to
issachar, naphtali, and zebulun while their Tailscale clients were offline.
Issachar's SSH host key had changed; deployment proceeded only
after its LAN IP's ARP address exactly matched the registered hardware MAC
`1c:f6:4c:55:db:4b`, and the new key was stored under a separate alias without
deleting the old evidence.

All ten canonical minis now hold byte-identical artifacts, run both system
LaunchDaemons, complete a real local chat request, and appear simultaneously
in `/infer/nodes` as `model=murakumo-edge`, `live=true`, `ready=true`, and
`liveness=fresh`. A temporary LAN artifact server exposed only the two public
model files and a llama.cpp binary archive; it and its temporary archive were
removed after SHA verification.

## Production evidence

The public Worker version
`7d9ea940-ec7d-4de3-8b8b-430b68eab3d9` receives 100% of production traffic.
It was built from `local-murakumo` `8907f15a` against exact west-pinned
dependencies. `GET /v1/models` advertises `murakumo-edge` with context window
65,536.

On six independently exercised M4 replicas, a 48-token direct completion
measured 15.73--18.14 output tok/s (mean 17.21 tok/s). Through
`api.murakumo.cloud`, post-deployment end-to-end checks returned HTTP 200:

- text: 7 completion tokens in 5.10 seconds;
- required tool call: exact `get_weather({"city":"東京"})`, 26 completion
  tokens in 8.88 seconds, `finish_reason=tool_calls`;
- image input: a real inline PNG was decoded and answered in 6.08 seconds.

The public jobs were observed in the new resident logs: Levi settled the text
and vision jobs, and Joseph settled the tool-call job. This distinguishes real
generation from catalog, health, or readiness-only evidence.

## MTP canary amendment

Ornith's one-layer MTP head is supported as an explicit
`murakumo-edge-mtp-canary` profile, not as the resident production default.
The five-layer ownership is:

1. Amu compiles and extracts the native `mtp-admitted?` policy;
2. `num` charges speculative-head and verification scratch bytes explicitly;
3. `torch` emits full-offload llama.cpp `draft-mtp` argv only after admission;
4. `inference` pins the MTP artifact and b10472 runtime separately from the
   production Q4_K_M artifact;
5. `murakumo` requires observed drafting, exact token parity, repeatability,
   full offload, and end-to-end speed before promotion.

The Asher M4/16 GiB canary used the same IQ4_XS artifact for MTP off and on,
loaded the vision projector, retained the 65,536-token resident window, and
generated 128 tokens at temperature 0 and seed 42. llama.cpp reported
`qwen35.nextn_predict_layers=1`, `offloaded 34/34 layers to GPU`, and a live
`draft-mtp` context. Each measured MTP run drafted 150 tokens and accepted 75.
All off/on/repeat token arrays were identical; their canonical token-array
SHA-256 is
`61a1e907e9fb94fe83ef97b6ffbc59469a272e5feaa38b866b9fd341f4bc91e8`.

Despite correct execution, MTP failed the speed gate:

| metric (three runs) | MTP off | MTP on | on/off |
|---|---:|---:|---:|
| prefill tok/s | 106.11 | 98.26 | 0.926x |
| decode tok/s | 18.35 | 11.84 | 0.645x |
| end-to-end tok/s, including prefill | 17.66 | 11.56 | 0.655x |

Resident RSS also rose from 7,356,176 KiB to 8,070,080 KiB. Therefore the MTP
profile is implemented and execution-qualified, but `speed-qualified=false`;
it is not assigned to fleet replicas and the existing `murakumo-edge`
LaunchDaemons remain unchanged. The exact evidence record is
[`docs/evidence/ornith-mtp-asher-260829.edn`](../evidence/ornith-mtp-asher-260829.edn).

## Failures found and closed

- A user LaunchAgent cannot be relied on in a headless session. The plan now
  emits a system LaunchDaemon with `UserName`.
- nbb does not implement the JavaScript Promise `.finally` method used by the
  first poll worker. The worker now settles cleanup through explicit resolve
  and reject arms; its focused suite passes 10 tests / 42 assertions.
- All legacy poll workers originally read the same unscoped queue and could
  steal a `murakumo-edge` job. Unscoped readers now see only unbound jobs, and
  edge readers request `/infer/queue?model=murakumo-edge`. The Worker suite
  passes 195 ClojureScript tests / 1,003 assertions.

## Open operational gaps

- Asher, Benjamin, Naphtali, and Zebulun had exhausted macOS's default
  ephemeral TCP range (roughly 11,000--16,600 `TIME_WAIT` sockets), making even
  localhost HTTP fail with `Can't assign requested address`. Widening the
  runtime range from 49152 to 32768 restored service. The generating workload
  and a durable reboot-safe correction remain separate follow-ups.
- Dan has about 8 GiB free after model placement. It is running, but this is a
  low-disk warning, not healthy spare capacity.
- `jacob` cannot be provisioned while offline and is not silently counted as a
  deployed replica. Adding an eleventh 16 GiB Mac requires restoring and
  enrolling that concrete machine (or naming a different concrete machine),
  then repeating artifact, LaunchDaemon, readiness, and real-generation proof.
