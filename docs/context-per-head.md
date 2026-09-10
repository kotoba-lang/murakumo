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


---

# One weight across the fleet: IQ3_XXS, not IQ2_XS (2026-09-10)

The fleet was running two different weights for the same model — Q4_K_M
(16.81 GB) on b70/xavier/gad, GSQ-RCO IQ2_XS (8.42 GB) on judah/dan. Aligning
them raised the obvious question of which, and the answer is neither of those.

## The quality difference at IQ2_XS is real

From ISTA-DASLab's own published evaluation, against the BF16 base:

| variant | GB | AIME25 | GPQA-D | LiveCodeBench v6 |
|---------|----|--------|--------|------------------|
| BF16     | 53.8 | 100.00 | 89.90 | 85.71 |
| IQ2_XS   |  8.4 |  96.67 | 84.85 | **76.57** |
| IQ2_S    |  9.3 | 100.00 | 86.36 | 82.29 |
| **IQ3_XXS** | **10.1** | **100.00** | **88.89** | **84.57** |
| IQ3_S    | 11.8 | 100.00 | 89.39 | 85.71 |

IQ2_XS gives up **9.14 points of LiveCodeBench** and 5.05 of GPQA-Diamond.
IQ3_XXS recovers nearly all of it — matching the base model exactly on AIME25
— for 1.7 GB more.

## And IQ3_XXS still fits every node at hermes's floor

q4_0 cache, one slot, largest context each node can hold:

| node | IQ2_XS | **IQ3_XXS** | IQ3_S |
|------|--------|-------------|-------|
| judah / dan (17.18 GB) | 112,033 | **89,382** | 66,596 |
| b70 (15.56 GB)         |  90,060 | **67,409** | 44,623 |

IQ3_S is where it breaks: 44,623 on b70, under the 64,000 hermes requires. So
IQ3_XXS is the highest-quality quant that is uniform across the fleet AND
hermes-capable everywhere. That is why it, and not the one already deployed.

## What this fixes on b70, incidentally

b70 runs Q4_K_M today: **16.81 GB of weights on a 15.56 GB box — 1.75 GB over
RAM before any cache at all.** It has been running out of the 64 GB
"host-memory safety swap" the unit file provisions. IQ3_XXS at 10.09 GB makes
the weights resident with 4.97 GB left for cache, which is a stability change,
not just a quality one.

## Standard config

    --ctx-size 65536 --parallel 1 --cache-type-k q4_0 --cache-type-v q4_0

65,536 clears hermes's 64,000 on every node with margin, and one slot is what
buys it. b70 trades its second slot for that; the unit's own traffic
measurement (0 of 3795 requests above 16,384) says general traffic does not
need the window, so the slot is the better thing to spend where hermes is not
the caller.

## xavier is not covered

No SSH as junkawasaki, root, or ubuntu — only its HTTP API on :8090 answers.
It serves Q4_K_M at 8,192 per slot and cannot be realigned from here.


---

# Applied 2026-09-10 — and two things the arithmetic missed

All three heads now run **GSQ-RCO IQ3_XXS**:

| head | weight | ctx/slot | slots | measured |
|------|--------|----------|-------|----------|
| judah | IQ3_XXS | 65,536 | 1 | 7.07 tok/s |
| dan   | IQ3_XXS | 65,536 | 1 | 7.43 tok/s |
| b70   | IQ3_XXS-**mtp** | 32,768 | 2 | serving, 0 restarts |

## The Macs needed a sysctl, not a smaller context

IQ3_XXS loaded on judah and then every request died with **"Compute error."** —
at 65,536 and equally at 32,768, so it was never the context. Metal caps
GPU-wired memory at roughly two thirds of RAM, ~10.6 GB on a 16 GB machine,
and the weights alone are 10.09 GB. The model loads and then cannot compute,
which reads like a broken quantization rather than a ceiling.

    sudo sysctl -w iogpu.wired_limit_mb=13000

made it work immediately. Persisted as
`/Library/LaunchDaemons/com.murakumo.wired-limit.plist` on both Macs, because
the setting resets on reboot and the failure it causes does not look like a
memory problem.

## b70 needed the -mtp weight, and crash-looped 91 times before I saw it

b70's unit runs `--spec-type draft-mtp`. The repo publishes **two** files per
quant and I fetched the plain one, which carries no MTP draft head:

    E srv load_model: failed to create MTP context
    murakumo-b70-llama.service: Scheduled restart job, restart counter is at 91

`Restart=always` turned a config error into a silent crash loop. Fixed by
fetching `Qwen3.8-27B-GSQ-RCO-IQ3_XXS-mtp.gguf` (10.44 GB, sha verified).

## What the switch did for b70

It ran **Q4_K_M: 16.81 GB of weights on a 15.2 GB box** — 1.6 GB over RAM
before any cache — out of the 64 GB "host-memory safety swap" its unit
provisions. On IQ3_XXS: available went 4.77 GB -> 12.3 GB and swap to
essentially zero.

b70 keeps 2 slots at 32,768 rather than 1 at 65,536: its own unit measured
0 of 3795 requests above 16,384 and a largest observed prompt+generation of
14,487, so concurrency is worth more to it than a window nothing asks for.
That is why hermes runs on judah+dan, which do hold 65,536.

## Still open

- **The gateway reports b70 at ctx 16,384** — static, not probed. It was wrong
  when the head served 32,768 and is wrong now.
- **xavier is untouched.** No SSH as junkawasaki, root or ubuntu; only its HTTP
  API answers. It still serves Q4_K_M at 8,192.
- `--verify` printed `usable for bots false` from a timed-out probe while still
  exiting 0 with "no findings". A probe that could not measure should be a
  finding, not a pass.
