# Optimal context per head, measured 2026-09-10

Qwen3.8-27B's KV geometry, read out of the GGUF: 64 blocks x 4 KV heads x
(256+256) = **131,072 elements per token**. Per token that is 256 KiB at f16,
136 KiB at q8_0, 72 KiB at q4_0. Everything below follows from that number and
the weights, and nothing below is copied from a model card.

    nbb scripts/model-kv-geometry.cljs <node> <path.gguf>
    nbb scripts/model-context-fit.cljs <budget-GB>

## What a 32 GB node can hold (Q4_K_M, 16.81 GB of weights)

| cache | 1 slot  | 2 slots | 4 slots |
|-------|---------|---------|---------|
| f16   |  56,035 |  28,017 |  14,008 |
| q8_0  | 105,477 |  52,738 |  26,369 |
| q4_0  | 199,236 |  99,618 |  49,809 |

So 26k–32k is exactly right **for f16 at two slots**, and it is the f16 default
that puts it there. On the same 32 GB, q8_0 gives **52,738 per slot** and q4_0
gives 99,618 — the context is bounded by the cache type at least as much as by
the memory.

With the GSQ IQ2_XS build (8.42 GB) instead, a 32 GB node reaches 82,853 per
slot at q8_0 across two slots.

## The heads as they actually run

| head | box | weights | slots | per-slot ctx | cache |
|------|-----|---------|-------|--------------|-------|
| b70 (ubuntu-server) | 15.56 GB RAM, **iGPU sharing it**, 4.77 GB available, 68 GB swap | Q4_K_M 16.81 GB | 2 | 32,768 | **f16 (default, no flag)** |
| xavier | not reachable over SSH | Q4_K_M | 1 | 8,192 | unknown |
| gad | 50 GB | — | — | 262,144 | — |
| judah, dan | 17.2 GB each | GSQ IQ2_XS 8.42 GB | 1 | 65,536 | q4_0 |

### b70 is already at its optimum, and the lever is the cache type

`--ctx-size` in llama.cpp is the TOTAL budget and each slot gets ctx/parallel,
so b70's `--ctx-size 65536 --parallel 2` is 32,768 per slot. On a box with
4.77 GB available that is the right number. The available change is not more
context, it is **f16 -> q8_0**, which at the same total context costs 9.13 GB
of KV instead of 17.18 GB.

⚠ NOT DONE, AND NOT BECAUSE IT LOOKS WRONG. Two things are unverified: whether
the SYCL build accepts q8_0 K/V with `--flash-attn on`, and whether KV on an
iGPU is accounted where this arithmetic assumes. Restarting drops 2 of the
pool's 3 free slots (gad reports 0), so this wants a probe first, not a
`systemctl restart` on an assumption.

## Hermes cannot use xavier or b70, and this is arithmetic, not configuration

Hermes refuses any model under 64,000 tokens
(`agent/model_metadata.py MINIMUM_CONTEXT_LENGTH`). b70 serves 32,768 per slot
and xavier 8,192. Raising b70 to 65,536 per slot means `--ctx-size 131072`,
which at q8_0 is 18.25 GB of KV on a box with 4.77 GB free — it would run in
the 64 GB swapfile, which is the simeon failure mode (18 GB swapped, 180 s for
zero bytes) rather than a working head.

That is why the hermes cluster is judah + dan: 16 GB boxes, but the IQ2_XS
build is 8.42 GB instead of 16.81 GB, which leaves enough for 65,536 at q4_0.

## Separately: the gateway reports b70's context wrong

`/v1/models` capacity-members says b70 ctx **16,384**. The head's own `/props`
says **32,768**, and its command line says `--ctx-size 65536 --parallel 2`. The
gateway is a full factor of two low.

This is not cosmetic: the pool routes by fit and EXCLUDES heads a request
cannot fit, so every request between 16,385 and 32,768 tokens is being steered
away from a head that can serve it — toward gad, which reports 0 available.
Measured on the same day as `degraded: ["fleet-incomplete",
"primary-unavailable"]`.
