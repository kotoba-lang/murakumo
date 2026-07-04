# claude-murakumo

Run Claude Code against **murakumo.cloud** instead of Anthropic's own models —
the same shape of setup as z.ai's `claude-zai` wrapper for GLM. Claude Code
(and any Anthropic SDK client) just needs `ANTHROPIC_BASE_URL` pointed at an
Anthropic-Messages-API-compatible endpoint; `gftdcojp/local-murakumo`'s
`/v1/messages` is that endpoint, translating to/from whatever model is
actually serving on the murakumo distributed fleet.

## Use

```bash
./claude-murakumo                      # same args as `claude`
bb claude                              # equivalent, via the bb task
```

Or add it to your shell so `claude-murakumo` is just a command:

```bash
ln -s "$(pwd)/claude-murakumo" ~/.local/bin/claude-murakumo   # or anywhere on PATH
```

## Env vars

| var | default | what |
|---|---|---|
| `MURAKUMO_CLAUDE_BASE_URL` | `https://api.murakumo.cloud` | the Anthropic-compatible endpoint |
| `MURAKUMO_CLAUDE_MODEL` | `qwen-agentworld-35b-a3b` | sets `ANTHROPIC_MODEL` + the default/opus/sonnet/haiku overrides (murakumo currently serves one model at a time, so all four point at it) |
| `MURAKUMO_CLAUDE_TOKEN` | resolved from 1Password (`gftd.murakumo/ANTHROPIC_PROXY_TOKEN`, vault `gftdcojp`) if unset | must match the `api.murakumo.cloud` Worker's `ANTHROPIC_PROXY_TOKEN` secret |

## Why not just `export ANTHROPIC_BASE_URL=... && claude`

Some dev shells define a `claude` shell **function** that unsets
`ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN`/etc. before launching the real
binary (a guard against a live Claude Code session accidentally routing
through an experiment). `claude-murakumo` uses `command claude` (or
`$CLAUDE_CODE_EXECPATH` if set) to bypass that function and reach the real
binary directly, so the murakumo env vars actually take effect.

## What you get / don't get

- Chat, code generation, and tool-calling (Anthropic `tool_use`/`tool_result`
  blocks ⇄ OpenAI `tool_calls`) are translated, streaming and non-streaming.
- Extended-thinking display: the model's `reasoning_content` becomes a
  `thinking` content block.
- The model is qwen-agentworld-35b-a3b, **not** Claude — expect different
  behavior, especially on anything relying on Claude-specific system-prompt
  conventions or Claude's own tool-use judgment. See
  `/itonami/benchmark/clj-datomic` on murakumo.cloud for a real (execution-
  verified, not LLM-judged) sense of its Clojure/Datomic coding ability, and
  `/itonami/benchmark` for a HumanEval-style read on other coding models
  worth trying on the fleet.
- Only one model serves at a time across the fleet's shared 7-node ring —
  `MURAKUMO_CLAUDE_MODEL` picking a different registered model than what's
  currently `bb murakumo infer up`/`serve`'d will fail to reach an endpoint,
  not silently fall back.
