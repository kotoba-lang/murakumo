# Orca agent CLI comparison

External evidence reviewed on 2026-11-25 JST. This report covers the 45 entries
in the Orca catalog snapshot under review. Resource numbers describe the cited
versions and workloads. No Murakumo benchmark was run for this report. See the
[model and endpoint comparison](agent-benchmarks-2026-11-25.md) for the
full ALE, Artificial Analysis, and OpenRouter tables.

## Which agents to use

- **Many concurrent sessions:** Jcode has the lowest incremental RAM in its
  published comparison. Its author reports 3.5 MB per additional headless
  session and 10.4 MB per additional interactive session.
- **Routine headless work and API cost:** Pi reached 154 MiB peak cgroup memory
  while retaining the full test input. It added 108 MiB to its container base.
  In the GPT-6 Luna Max evaluation below, it averaged $0.11 and 18.9 min per task.
- **Long jobs where completion matters:** Codex with GPT-6.2 Astra xhigh
  achieved 47.5% full pass and 14.3 min per task on ALE v1.1. The Sol configuration
  reached 41.8% and 15.6 min for $111.32 across the suite versus Astra's $372.01.
- **Small Apple Silicon download:** Ante was the smallest of the five
  comparable official CLI archives checked, at 14.7 MB compressed.

Sources: [Jcode resource comparison](https://jcode.sh/),
[headless benchmark](https://aictrl.dev/blog/headless-coding-agent-cli-benchmark),
[ALE leaderboard](https://agents-last-exam.org/leaderboard),
[Ante release](https://github.com/AntigmaLabs/ante/releases/tag/v0.3.9).

## Host RAM, disk footprint, and overhead

### Incremental session RAM

The Jcode project published the following additional interactive session
footprints, in MB: Jcode 10.4 (9.9 with local embeddings off), Codex 21.6,
Pi 76.5, Antigravity 86.4, Cursor 157.5, GitHub Copilot 158.1, Claude Code
212.7, and OpenCode 318.4. The same source reports 3.5 MB for an additional
Jcode headless session and 168.6 MB for Claude Code. Treat these as
author-reported increments. Total memory includes the first session and
any shared processes. [Jcode benchmark and scope](https://jcode.sh/)

### Headless full-context workload

The benchmark author used a mock provider and a 51-call workload. RAM is the
median peak cgroup reading, which includes memory charged for caches. Disk is
the installed image increment above each harness's own runtime base. Turn
overhead excludes model response time. [Methodology and raw data](https://aictrl.dev/blog/headless-coding-agent-cli-benchmark)

| Harness | Median peak RAM, MiB | Added image size, MiB | Median turn overhead, ms |
| --- | ---: | ---: | ---: |
| Pi | 154 | 108 | 9 |
| Goose | 247 | 292 | 72 |
| Hermes | 344 | 388 | 64 |
| OpenCode 2.1.0 | 424 | 818 | 32 |
| Codex | 132¹ | 410 | 45 |

¹ Codex truncated the middle of the test files. Pi had the lowest memory
among configurations retaining the full input. OpenCode's npm installation
in this test included four Linux x64 binary variants, so the result depends
on how it is installed.

A separate mock-provider experiment measured process-tree peak RSS during
100 scripted tool rounds. The configurations below include separate probe
batches and are useful as workload-specific observations. The `dsh` row
covers the core used by the Orca DeepSeek entry. [Benchmark repository](https://github.com/KonghaYao/harness-perf-benchmark)

| Harness | Version | Peak RSS, MB |
| --- | --- | ---: |
| Grok Build | 1.0.41 | 102.6 |
| Codex | 0.155.1 | 163.7 |
| Hermes | 0.21.3 | 201.1 |
| Antigravity | 1.2.7 | 225.1 |
| DeepSeek Harness | 0.1.5-rc.2 | 231.2 |
| Pi | 0.85.1 | 234.4 |
| Claude Code | 2.1.277 | 351.0 |
| Kimi Code | 2.0.0 | 603.6 |
| Cline | 3.0.62 | 706.1 |
| OpenCode 2 | 2.0.10 | 849.6 |

The [OpenDev author's macOS ARM64 comparison](https://github.com/opendev-to/opendev)
also reports startup and installed size for three catalog entries. Codex
0.116.0 took 37.8 ms to start, peaked at 43.7 MB, and occupied 116 MB.
Claude Code 2.1.87 took 87.3 ms, peaked at 214.6 MB, and occupied 188 MB.
OpenCode 1.2.27 took 557.4 ms, peaked at 285.9 MB, and occupied 90 MB.
These are startup observations for the versions the author tested.

### Apple Silicon downloads

These are the compressed CLI assets as listed by GitHub, expressed in decimal
MB. Installed size includes the expanded executable and can grow with logs,
caches, dependencies, and tool downloads.

| CLI and release | Archive | Download size, MB |
| --- | --- | ---: |
| [Ante v0.3.9](https://github.com/AntigmaLabs/ante/releases/tag/v0.3.9) | macOS ARM64 tar.gz | 14.7 |
| [Charm Crush v0.102.0](https://github.com/charmbracelet/crush/releases/tag/v0.102.0) | macOS ARM64 tar.gz | 29.0 |
| [Goose v1.61.0](https://github.com/aaif-goose/goose/releases/tag/v1.61.0) | macOS AArch64 CLI tar.gz | 57.9 |
| [OpenCode v1.19.6](https://github.com/anomalyco/opencode/releases/tag/v1.19.6) | macOS ARM64 CLI zip | 64.5 |
| [Codex v0.172.0](https://github.com/openai/codex/releases/tag/rust-v0.172.0) | macOS AArch64 CLI tar.gz | 120.2 |

Cloud backends such as OpenRouter keep the model weights on the provider's
side. A local backend adds its own weight files, inference runtime and memory
for the KV cache and working buffers. For example, Qwen publishes
[Qwen3 0.6B Q8_0 at 639 MB](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/main)
and [Qwen3 8B Q4_K_M at 5.03 GB](https://huggingface.co/Qwen/Qwen3-8B-GGUF/tree/main).
These two numbers are weight-file sizes.

## Coding performance with the model held constant

Artificial Analysis Coding Agent Index v2.1 with GPT-6 Luna Max.
Score, runtime, API cost, and token totals use AA's definitions and tested
configurations. [Methodology](https://artificialanalysis.ai/methodology/coding-agents-benchmarking/)
and [harness coverage](https://artificialanalysis.ai/agents/coding-agents/#harness-comparisons).

| Harness | Index | Avg min/task | Avg API cost/task | Avg tokens/task |
| --- | ---: | ---: | ---: | ---: |
| [Codex](https://artificialanalysis.ai/agents/coding-agents/comparisons/codex-vs-pi) | 26 | 23.5 | $0.19 | 10.1M |
| [Grok Build](https://artificialanalysis.ai/agents/coding-agents/comparisons/grok-build-vs-kimi-code-cli) | 25 | 26.5 | $0.28 | 13.6M |
| [OpenCode](https://artificialanalysis.ai/agents/coding-agents/comparisons/opencode-vs-pi) | 23 | 20.4 | $0.16 | 7.31M |
| [Muse Code](https://artificialanalysis.ai/agents/coding-agents/comparisons/deepseek-harness-vs-muse-code) | 23 | ~72 | $0.62 | 26.9M |
| [Claude Code](https://artificialanalysis.ai/agents/coding-agents/comparisons/claude-code-vs-codex) | 22 | 23.0 | $0.18 | 9.2M |
| [DeepSeek Harness](https://artificialanalysis.ai/agents/coding-agents/comparisons/deepseek-harness-vs-pi) | 22 | 25.0 | $0.35 | 18.1M |
| [Pi](https://artificialanalysis.ai/agents/coding-agents/comparisons/codex-vs-pi) | 21 | 18.9 | $0.11 | 5.59M |
| [GitHub Copilot](https://artificialanalysis.ai/agents/coding-agents/comparisons/copilot-cli-vs-deepseek-harness) | 21 | 26.6 | $0.16 | 7.18M |
| [Mistral Vibe](https://artificialanalysis.ai/agents/coding-agents/comparisons/codex-vs-mistral-vibe) | 21 | 32.6 | $0.23 | 10.3M |
| [Kimi Code](https://artificialanalysis.ai/agents/coding-agents/comparisons/grok-build-vs-kimi-code-cli) | 20 | 44.6 | $0.34 | 14.2M |
| [Qwen Code](https://artificialanalysis.ai/agents/coding-agents/comparisons/pi-vs-qwen-code) | 17 | 30.7 | $0.17 | 7.37M |

With GLM-5.3-Flash Max, AA reports OpenCode at **39 / $0.60**, Pi at
**30 / $0.41**, and Codex at **28 / $0.59** (index / mean API cost).
Choose the harness and backend together. [OpenCode vs Pi](https://artificialanalysis.ai/agents/coding-agents/comparisons/opencode-vs-pi)
and [Codex vs Pi](https://artificialanalysis.ai/agents/coding-agents/comparisons/codex-vs-pi).

## Orca catalog entries

Implementation describes the CLI or its core where public evidence supports
the claim. A package manager or native installer establishes a distribution
method. Unknown fields indicate that the cited source did not verify the
implementation language. [Orca agent documentation](https://orca.dev/docs/agents/supported)

| Agent | Implementation or distribution | Model and features | Source |
| --- | --- | --- | --- |
| Claude | Language unconfirmed. Native installer | Claude, headless, MCP. Official host requirement is 4 GB+ RAM | [Anthropic](https://code.claude.com/docs/en/setup) |
| Claude Agent Teams | Multiple Claude Code sessions managed by Orca | One lead plus independent teammate contexts. Token use grows with active teammates | [Anthropic](https://code.claude.com/docs/en/agent-teams) |
| Codex | Rust, standalone builds | GPT family, headless, app server. See ALE and AA results above | [OpenAI](https://github.com/openai/codex) |
| GitHub Copilot | Language unconfirmed. npm option requires Node 22+ | Managed model selection. Copilot Fusion scored 71.2% on Terminal-Bench 4.1 | [GitHub](https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/install-copilot-cli), [benchmark](https://snorkel.ai/leaderboard/terminal-bench/) |
| OpenCode | TypeScript / Bun | Multiple providers, including local servers. Version 2 has a separate Orca entry | [Source](https://github.com/anomalyco/opencode), [providers](https://opencode.ai/docs/providers/) |
| Muse | Language unconfirmed. Meta CLI | Muse Wonder and Spark, reasoning effort, custom agents, CI | [Meta](https://dev.meta.ai/docs/muse-code) |
| OMP | TypeScript / Bun with Rust components | Oh My Pi, multi-provider CLI with IDE integration and extended tools | [Source](https://github.com/can1357/oh-my-pi) |
| Antigravity | Language unconfirmed. agy binary | Gemini 4 Neon xhigh scored 61.2% in Terminal-Bench 4.1 with this CLI | [Google](https://antigravity.google/docs/cli/tutorial), [benchmark](https://snorkel.ai/leaderboard/terminal-bench/) |
| Aider | Python | Multi-model, Git-focused edits. GPT-5 high scored 88.0% on its separate Polyglot benchmark | [Source](https://github.com/Aider-AI/aider), [benchmark](https://aider.chat/docs/leaderboards/) |
| Continue | TypeScript / Node.js | cn CLI with provider settings, rules and headless execution | [Source](https://github.com/continuedev/continue/tree/main/extensions/cli), [docs](https://docs.continue.dev/cli/quickstart) |
| Cursor | Language unconfirmed | Cursor Agent, headless and ACP | [Cursor](https://cursor.com/docs/cli/using) |
| Hermes | Python. Docker image also includes Node and other tools | Multiple providers, persistent memory, messaging. Docker guidance starts at 1 GB RAM and 500 MB for its data volume | [Nous](https://hermes-agent.nousresearch.com/docs/user-guide/docker) |
| OpenClaude | TypeScript / Bun | OpenAI-compatible APIs, Gemini, Ollama and others | [Gitlawb](https://github.com/Gitlawb/openclaude) |
| Grok | Rust | Grok Build, interactive and headless | [xAI source](https://github.com/xai-org/grok-build), [docs](https://docs.x.ai/build/overview) |
| OpenCode 2 | TypeScript / Bun | OpenCode 2.x. Use version-specific resource figures | [Source](https://github.com/anomalyco/opencode), [Orca](https://orca.dev/docs/agents/supported) |
| MiMo Code | Language unconfirmed. Native distribution, npm launcher | Xiaomi MiMo | [Xiaomi](https://mimo.mi.com/docs/en-US/tokenplan/integration/mimo-code), [releases](https://github.com/XiaomiMiMo/MiMo-Code/releases) |
| Ante | Rust, single executable | Multi-provider. Experimental local inference integration | [Antigma](https://docs.antigma.ai/), [local inference](https://docs.antigma.ai/experimental/agent-native-inference/) |
| Trae | Language unconfirmed | Orca launches TRAE CN CLI via traecli | [TRAE](https://www.trae.ai/), [Orca](https://orca.dev/docs/agents/supported) |
| DeepSeek Harness | TypeScript / Node.js core | Orca uses the dsh-tui profile. Shared core handles model access, tools and session state | [DeepSeek](https://github.com/deepseek-ai/deepseek-harness), [Orca](https://orca.dev/docs/agents/supported) |
| Qoder CLI | Language unconfirmed. Native distribution | Qoder account and credits, interactive and scripted use | [Qoder](https://docs.qoder.com/cli/installation) |
| Qoder CLI China | Language unconfirmed | China-specific Qoder command. No matched performance comparison found | [Orca](https://orca.dev/docs/agents/supported), [Qoder](https://docs.qoder.com/cli/quickstart) |
| ZCode | TypeScript-family CLI and runtime | Z.ai harness, with GLM configurations | [Source](https://github.com/zai-org/ZCode/tree/main/apps/zcode-cli) |
| Pi | TypeScript / Node.js | Multi-provider, small default tool set, extensions and JSON/RPC | [Pi](https://pi.dev/), [CLI](https://upd.dev/badlogic/pi-mono/src/branch/stable/packages/coding-agent/README.md) |
| Prime Agent | TypeScript-family source, Node development and standalone builds | RLM harness for long-context coding and evaluations | [Source](https://github.com/PrimeIntellect-ai/prime-agent), [distribution](https://github.com/PrimeIntellect-ai/prime-agent/discussions/2377) |
| Gemini | TypeScript / Node.js | Gemini CLI with MCP and headless execution | [Google](https://github.com/google-gemini/gemini-cli) |
| Goose | Rust | Multiple providers, MCP, and other CLIs as backends | [Source](https://github.com/aaif-goose/goose), [CLI providers](https://github.com/aaif-goose/goose/blob/main/documentation/docs/guides/cli-providers.md) |
| Amp | JavaScript-family bundle compiled using Bun | Amp service selects models by mode | [Package format](https://ampcode.com/news/npm-package-changes), [CLI](https://ampcode.com/docs/cli) |
| Kilocode | TypeScript / Bun | OpenCode-family CLI, Kilo Gateway, multiple providers | [Source](https://github.com/Kilo-Org/kilocode/tree/main/packages/opencode) |
| Kiro | Current CLI closed source. Amazon Q CLI predecessor was Rust | AWS CLI agent, custom agent settings and MCP | [Kiro](https://kiro.dev/docs/getting-started/installation/), [predecessor](https://github.com/aws/amazon-q-developer-cli) |
| Charm | Go | Crush, multi-provider, LSP and MCP | [Source](https://github.com/charmbracelet/crush) |
| Auggie | Language unconfirmed. Node 22+ | Augment codebase context, headless and CI | [Augment](https://github.com/augmentcode/auggie) |
| Autohand Code | TypeScript-family, Node 22+. Bun to build | Multiple providers and local models, plan mode and config import | [Source](https://github.com/autohandai/code-cli) |
| Cline | TypeScript, bundled Bun, Zig TUI component | Multiple providers, plan/act, MCP and checkpoints | [Distribution](https://github.com/cline/cline/blob/main/apps/cli/DISTRIBUTION.md) |
| Codebuff | TypeScript-family | Specialized coding subagents, service and SDK | [Source and SDK](https://github.com/CodebuffAI/freebuff) |
| Freebuff | TypeScript-family | Related free CLI, configurable agents, tools and registry | [Source](https://github.com/CodebuffAI/freebuff) |
| Command Code | Language unconfirmed | Hosted agent service, skills and API-key use in CI | [CLI reference](https://commandcode.ai/docs/reference/cli) |
| Droid | Language unconfirmed | Factory coding agent with managed models and BYOK | [Factory](https://docs.factory.ai/), [BYOK](https://docs.factory.ai/pricing/individuals) |
| Kimi | Current CLI TypeScript-family. Legacy CLI was Python | Kimi Code CLI with configurable providers | [Current](https://github.com/MoonshotAI/kimi-code), [legacy](https://github.com/MoonshotAI/kimi-cli) |
| Mistral Vibe | v3 Rust core and TUI, Python distribution and integration components | Mistral and custom providers. Older releases used a Python TUI | [v3 architecture](https://github.com/mistralai/mistral-vibe/issues/1301), [build](https://github.com/mistralai/mistral-vibe/blob/main/CONTRIBUTING.md) |
| Qwen Code | TypeScript / Node.js | Qwen and external providers, headless and SDK | [Qwen](https://github.com/QwenLM/qwen-code) |
| Rovo Dev | Language unconfirmed. Atlassian ACLI extension | Jira and Confluence integration. Linux docs specify glibc 2.17+ | [Installation](https://support.atlassian.com/rovo/docs/install-and-run-rovo-dev-cli-on-your-device), [Linux requirements](https://support.atlassian.com/bitbucket-cloud/docs/rovo-dev-advanced-agentic-configuration/) |
| Devin | Language unconfirmed | Cognition terminal agent. AA's separate Fusion configuration scored 58 at $8.75/task | [CLI](https://devin.ai/cli), [Fusion evaluation](https://artificialanalysis.ai/agents/coding-agents/comparisons/claude-code-vs-devin-fusion-cli) |
| OpenClaw | TypeScript / Node.js | Persistent gateway, messaging and multiple providers. Hosting guidance recommends 2 GB+ RAM | [Installation](https://github.com/openclaw/openclaw/blob/main/docs/install/index.md), [hosting](https://docs.openclaw.ai/help/faq-first-run/providers-and-hosting) |
| CodeBuddy | Language unconfirmed. Node npm option and standalone installer | Tencent CLI, interactive and headless modes | [Tencent](https://www.codebuddy.ai/docs/cli/installation) |
| Jcode | Rust | Multi-model, parallel sessions and headless execution | [Source](https://github.com/1jehuang/jcode), [resource evidence](https://jcode.sh/) |
