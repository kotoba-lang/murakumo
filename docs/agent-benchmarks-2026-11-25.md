# External benchmarks for long-running agents

Snapshot recorded 2026-11-25 JST. These are published results and prices.
Murakumo did not run these evaluations or measure these endpoints.

## Model choice

- **Routine Codex work: GPT-6.2 Sol.** ALE pass rate 41.8%, about 15.6 minutes per task, and $111.32 in estimated model API cost across the suite. At the OpenAI endpoint on OpenRouter it showed 56 output tok/s at $2 input and $10 output per million tokens.
- **Difficult work or short turnaround: GPT-6.2 Astra xhigh.** Highest ALE pass rate (47.5%) and shortest mean task runtime (14.3 minutes) among these entries. It cost $372.01 across the suite. The OpenAI endpoint on OpenRouter showed 38 output tok/s at $8 input and $40 output per million tokens.
- **Budget batch work: DeepSeek V4.1 Flash 1017 with OpenClaw.** ALE pass rate 36.1%, about 37.8 minutes per task, and $19.05 across the suite. OpenRouter showed 153 output tok/s on Relace and 547 on Baseten Fast at the prices below. These endpoints were observed separately from the ALE run.

Sol's OpenRouter endpoint emits tokens faster than Astra's in this snapshot.
Astra finished the ALE task suite faster with Codex. Per-request output speed
and end-to-end task runtime answer different questions.

## Agents' Last Exam v1.1

[Official leaderboard](https://agents-last-exam.org/leaderboard), All Tasks
view, results updated 2026-11-18 UTC. The rank follows full-pass rate. Score
includes partial credit. Average minutes are reported total runtime divided by
122 tasks (113 for Fable). Runtime includes completed and timed-out attempts
and excludes setup and grading. Output tokens are ALE's totals. Estimated API
cost uses recorded usage and standard rates as of 2026-11-17 and excludes tool
fees. Missing usage can understate cost.

| ALE rank | Model | Harness | Full pass | Score | Runtime | Avg min/task | Output tokens | Est. API cost |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | GPT-6.2 Astra xhigh | Codex | 47.5% | 78.3% | 29h 7m | 14.3 | 2.6M | $372.01 |
| 2 | Claude Fable 5.2 Max¹ | Claude Code | 46.9% | 72.8% | 227h 20m | 120.7 | 28.2M | $6,785.69 |
| 3 | Qwen4 Max xhigh | Codex | 46.7% | 74.4% | 185h 6m | 91.0 | 13.6M | $1,201.45 |
| 4 | Gemini 4 Carbon high | ALE-Claw | 45.9% | 77.4% | 156h 47m | 77.1 | 10.2M | $561.14 |
| 5 | Kimi K3.1 Pro Max | Kimi Code | 43.4% | 74.4% | 61h 26m | 30.2 | 8.5M | $854.42 |
| 6 | Claude Opus 5.5 Max | Claude Code | 43.4% | 71.6% | 177h 11m | 87.1 | 21.5M | $2,143.41 |
| 7 | Gemini 4 Hydrogen | Antigravity CLI | 43.4% | 71.4% | 166h 15m | 81.8 | 25.5M | $524.89 |
| 8 | GPT-6 Astra | Codex | 42.6% | 73.1% | 80h 12m | 39.4 | 5.1M | $1,097.06 |
| 9 | GPT-6.2 Sol | Codex | 41.8% | 72.5% | 31h 40m | 15.6 | 3.1M | $111.32 |
| 10 | GPT-6.1 Terra | Codex | 41.8% | 69.8% | 124h 36m | 61.3 | 5.5M | $106.80 |
| 11 | Tencent Hy4 high | Claude Code | 41.0% | 70.3% | 104h 8m | 51.2 | 19.6M | $82.83 |
| 12 | Gemini 4 Argon high | ALE-Claw | 40.2% | 69.0% | 87h 4m | 42.8 | 6.5M | $568.36 |
| 13 | Muse Wonder 1.0 high | Muse Code | 38.5% | 68.4% | 116h 27m | 57.3 | 4.7M | $380.14 |
| 14 | DeepSeek V4.1 Flash 1017 high | OpenClaw | 36.1% | 65.4% | 76h 51m | 37.8 | 9.3M | $19.05 |
| 15 | Kimi K3 Max | Kimi Code | 35.2% | 63.0% | 107h 48m | 53.0 | 10.3M | $580.81 |
| 16 | GPT-5.6 Sol Max | Codex | 30.3% | 50.2% | 78h 22m | 38.5 | 3.1M | $402.42 |
| 17 | MiniMax M3.1 Flash Max | Codex | 29.5% | 58.9% | 117h 37m | 57.8 | 17.4M | $59.25 |
| 18 | MiniMax M3.1 Flash Max | Claude Code | 29.5% | 57.9% | 122h 12m | 60.1 | 18.1M | $136.99 |
| 19 | GLM-5.3 Max | ZCode | 26.2% | 54.4% | 181h 10m | 89.1 | 17.4M | $580.77 |
| 20 | GPT-5.6 Luna off | Codex | 5.7% | 26.5% | 40h 35m | 20.0 | 1.1M | $14.71 |

¹ Fable excludes nine downgraded tasks. The table names the effort when
available for the displayed result. ALE pairs each model with a harness,
and these runs used different harnesses and settings.

## OpenRouter endpoint throughput and token prices

Provider rows on the linked OpenRouter model pages, observed on 2026-11-25 JST.
Output throughput is the provider-row p50. Input and output are the prices on
that same row in USD per million tokens, before cache discounts or surcharges
unless the row shows a promotion. The chosen endpoints illustrate available
routes and tiers. Provider, region, model effort, service tier, and traffic
mix can change the observed rates. Prices and throughput are independent of the
ALE evaluation.

| Model | OpenRouter endpoint | Output tok/s (p50) | Input $/1M | Output $/1M |
| --- | --- | ---: | ---: | ---: |
| [GPT-6.2 Astra](https://openrouter.ai/openai/gpt-6.2-astra) | OpenAI | 38 | 8.00 | 40.00 |
| [GPT-6.2 Sol](https://openrouter.ai/openai/gpt-6.2-sol) | OpenAI | 56 | 2.00 | 10.00 |
| [DeepSeek V4.1 Flash 1017](https://openrouter.ai/deepseek/deepseek-v4.1-flash-1017) | Relace | 153 | 0.12 | 0.50 |
| [DeepSeek V4.1 Flash 1017](https://openrouter.ai/deepseek/deepseek-v4.1-flash-1017) | Baseten Fast | 547 | 0.60 | 2.40 |
| [Tencent Hy4](https://openrouter.ai/tencent/hy4) | Tencent Cloud (50% off) | 56 | 0.417 | 1.251 |
| [Claude Fable 5.2](https://openrouter.ai/anthropic/claude-fable-5.2) | Anthropic | 83 | 8.00 | 40.00 |
| [Claude Fable 5.2](https://openrouter.ai/anthropic/claude-fable-5.2) | Anthropic Fast | 248 | 16.00 | 80.00 |
| [Claude Opus 5.5](https://openrouter.ai/anthropic/claude-opus-5.5) | Anthropic | 87 | 4.00 | 20.00 |
| [Gemini 4 Neon](https://openrouter.ai/google/gemini-4-neon) | Google AI Studio | 67 | 3.00 | 15.00 |
| [Gemini 4 Carbon](https://openrouter.ai/google/gemini-4-carbon) | Google AI Studio (50% off) | 48 | 2.00 | 10.00 |
| [Gemini 4 Hydrogen](https://openrouter.ai/google/gemini-4-hydrogen) | Google AI Studio | 105 | 1.50 | 7.50 |
| [Gemini 4 Argon](https://openrouter.ai/google/gemini-4-argon) | Google AI Studio (50% off) | 39 | 2.00 | 10.00 |
| [GPT-6.1 Terra](https://openrouter.ai/openai/gpt-6.1-terra) | OpenAI | 30 | 1.00 | 5.00 |
| [GPT-6 Astra](https://openrouter.ai/openai/gpt-6-astra) | OpenAI | 35 | 10.00 | 50.00 |
| [Qwen4 Max](https://openrouter.ai/qwen/qwen4-max) | Alibaba Cloud Int. (5% off) | 54 | 1.425 | 4.275 |
| [Kimi K3.1 Pro](https://openrouter.ai/moonshotai/kimi-k3.1-pro) | Relace | 61 | 0.2403 | 8.4023 |
| [Kimi K3](https://openrouter.ai/moonshotai/kimi-k3) | Relace | 64 | 0.1824 | 11.0055 |
| [Muse Wonder](https://openrouter.ai/meta/muse-wonder) | Meta | 67 | 4.00 | 20.00 |
| [MiniMax M3.1 Flash](https://openrouter.ai/minimax/minimax-m3.1-flash) | Inferact vLLM | 210 | 0.075 | 0.30 |
| [GLM-5.3](https://openrouter.ai/z-ai/glm-5.3) | Tenstorrent | 123 | 0.60 | 2.50 |
| [GPT-5.6 Sol](https://openrouter.ai/openai/gpt-5.6-sol) | OpenAI | 83 | 4.00 | 20.00 |
| [GPT-5.6 Luna](https://openrouter.ai/openai/gpt-5.6-luna) | OpenAI | 70 | 0.20 | 1.20 |

## Artificial Analysis Intelligence Index v5.0.2

[AA model leaderboard](https://artificialanalysis.ai/leaderboards/models) as
observed on 2026-11-25 JST, first 20 entries in the Current view. Each row shows
the listed effort and AA benchmark mean cost. AA uses its own task mix and
weights. For effort selection, [AA's GPT-6.2 comparison](https://artificialanalysis.ai/models/comparisons/gpt-6-2-astra-vs-gpt-6-2-sol)
lists Astra xhigh at 62 and $2.52/task, Sol high at 55 and $0.56/task, and Sol
Max at 57 and $1.01/task. An em dash marks a missing estimate.

| AA rank | Model | Index | AA avg USD/task |
| ---: | --- | ---: | ---: |
| 1 | Claude Fable 5.2 Max (default fallback) | 65 | $26.54 |
| 2 | GPT-6.2 Astra Max | 62 | $4.00 |
| 3 | Gemini 4 Neon xhigh | 62 | $6.44 |
| 4 | Gemini 4 Carbon high | 60 | $7.76 |
| 5 | Claude Opus 5.5 Max (default fallback) | 59 | $10.04 |
| 6 | GPT-6.2 Sol Max | 57 | $1.01 |
| 7 | Claude Sonnet 5.5 Max (default fallback) | 55 | $10.02 |
| 8 | Gemini 4 Hydrogen xhigh | 54 | $3.16 |
| 9 | GPT-6.1 Terra Max | 51 | $0.96 |
| 10 | Gemini 4 Argon high | 51 | $9.95 |
| 11 | Muse Wonder Full Max | 50 | — |
| 12 | Kimi K3.1 Pro | 47 | $3.68 |
| 13 | Muse Wonder Max | 44 | $6.95 |
| 14 | Qwen4 Max | 43 | $3.27 |
| 15 | Claude Haiku 5.5 Max | 37 | $0.41 |
| 16 | Muse Ember Max | 36 | $0.14 |
| 17 | Grok 4.7 xhigh | 35 | $7.77 |
| 18 | Tencent Hy4 | 35 | $1.03 |
| 19 | DeepSeek V4.1 Flash 1017 Max | 32 | $0.60 |
| 20 | GLM-5.3 Max | 32 | $5.01 |
