# macOS native Murakumo Client

Run the platform adapter and inference on macOS. No Linux VM or container is
in the GPU path. The shared device/claim/Community flow is the existing frozen
Kotoba Node CLI (`scripts/node-cli.cljk` → `release/node.mjs`), delegated by
`murakumo-native node ...`; this adapter does not create a parallel account system.

- `client.mjs`: authenticated loopback OpenAI adapter, single bounded inference
  slot, streaming/cancellation, model binding, GPU evidence and measured status.
- `mlx-engine.py`: one native MLX model; Metal is required, remote tokenizer code
  is disabled and CPU fallback is refused. Attached llama.cpp engines must have
  the Metal library loaded and positive GPU-layer offload configured.
- `install.mjs`: verified versioned release, pinned CLI dependencies, private
  per-user token, LaunchAgent, CLI and native menu-bar application. Existing
  model engines, models, identities and services are preserved. Reinstallation
  restarts only `com.murakumo.native-client` and, in managed mode, its own `.mlx`.
- `MenuBar.swift`: native status UI; generation stays on the user's main PC/phone.

Configuration/releases/logs/models use
`~/github/workspaces/murakumo/native-client`; credentials use
`~/.local/share/murakumo-native/client.token` (0600). The LaunchAgent runs in
`gui/<uid>` when a login session exists (otherwise `user/<uid>`) and persists via `~/Library/LaunchAgents` at user login, not before
login. The menu-bar app is installed at `~/Applications/Murakumo Client.app`.
Local builds use ad-hoc signing; App Store publication/notarization is separate.

## Build and install

Build `MenuBar.swift` with SwiftUI/AppKit for arm64 macOS 13+, ad-hoc sign it,
then run `node macos-client/package.mjs /absolute/package /absolute/executable`.
The manifest pins every distributed file. Node.js 22+ is required.

For an existing Metal server:

```
node PACKAGE/install.mjs --engine-url http://127.0.0.1:18094/v1 \
  --backend llama-metal --model EXACT_ID_FROM_V1_MODELS
```

For managed MLX, prepare a compatible local checkpoint and an isolated or
existing tested Python interpreter; then add `--backend mlx-metal`,
`--python PATH --mlx-model PATH`, `--engine-url http://127.0.0.1:19841/v1`.
The tested lightweight checkpoint is `mlx-community/Qwen3-0.6B-4bit` revision
`73e3e38d981303bc594367cd910ea6eb48349da8` (Apache-2.0), with mlx-lm 0.31.3.
The installer never downloads a model implicitly.

`murakumo-native status` reports measured process/GPU/engine state.
`murakumo-native bench` alternates three short direct/native-client requests
against the same model. The HTTP adapter needs its private bearer token even on
loopback. Use SSH forwarding through Tailscale; the new API is not exposed on
all interfaces or anonymously to the tailnet.

`murakumo-native node init` preserves an existing identity;
`murakumo-native node doctor --model ID --local-url http://127.0.0.1:19840/v1`
checks readiness. Claiming, qualification and `join --idle-only` remain explicit
shared-CLI operations. Native installation does not silently enable public jobs,
change payout identity or claim account linkage. Existing fleet participation is
left intact.

The direct-vs-adapter samples verify native execution and response preservation;
short arithmetic tests do not establish full-model throughput or zero overhead.
Different models/hardware and production traffic require their own benchmarks.
