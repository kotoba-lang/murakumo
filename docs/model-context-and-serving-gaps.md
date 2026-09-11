# Model context is data; two serving gaps stay open

**2026-09-10.** Two decisions and two gaps, recorded because they were found by
measurement and would otherwise be rediscovered.

## Context belongs in the config as GEOMETRY, not as a number

A model's declared context is a property of the weights. What a node can hold
is a different quantity, decided by the KV cache — which grows linearly in
context and is invisible in the download size.

The measurement that forced this: `Qwen3.8-27B-GSQ-RCO-IQ2_XS` is **8.42 GB of
weights** and would want **36.5 GB of KV cache** at its own declared 262,144.
The number on the card does not fit this hardware at any quantization.

So `infer.edn` stores `:model/kv` — layers, KV heads, key/value length,
elements-per-token — which is fixed and measurable, plus
`:kv-cache/bytes-per-element`. The **fitting** context is computed, never
stored, because it changes with the node, the cache type, and what else that
node is holding.

    kbb --backend sci scripts/model-kv-geometry.cljk <node> <path.gguf>   # read geometry from a GGUF
    kbb --backend sci scripts/model-context-fit.cljk 13.8                 # what fits in 13.8 GB

**The tool was validated against something it did not know.** It computes
65,536 as murakumo-edge's maximum on a 13.8 GB budget; that is exactly the
`--ctx-size` the fleet already runs it at, arrived at independently by whoever
configured it.

### Two shapes break the flat arithmetic

- **Per-layer KV heads.** gemma4 alternates local and global attention, so the
  header carries *48 values* rather than one and the cache is their **sum**
  (328). An average would be wrong for any model whose layers genuinely differ.
  The first version of the tool skipped arrays and returned `NaN` for exactly
  the models whose geometry is most worth having.
- **Sliding windows.** Windowed layers cap their cache regardless of context,
  so the flat figure is an **upper bound**. Which layers are windowed is not in
  the header, so the bound is what gets recorded.

A third: **encoders have no KV cache at all.** `bge-m3` is BERT — not
autoregressive. Feeding its geometry to the fitting arithmetic would reserve
gigabytes for a cache that is never allocated.

### 21 of 27 models are unmeasured, and say so

Only the models whose GGUF was reachable on the fleet have `:model/kv`. The
rest carry **no key at all**, deliberately: absent means unmeasured, and a
consumer must treat that as unknown. A plausible default here would silently
size every unmeasured model wrong.

## Gap 1 — the new model does not survive a reboot

`qwen3.8-27b-gsq-iq2-xs` is served by `llama-server` on judah:8094, started by
hand. There is no launchd job, so a reboot loses it. The fleet's own
`murakumo-edge` has one; matching it is the fix, and it is a persistent change
to a shared node, so it is left for the owner to authorise.

## Gap 2 — llama-server-served models are invisible to the console's load path

The fleet console offers models through ollama, so a model served directly by
`llama-server` cannot be picked or reloaded there. Two ways out:

1. Register it with ollama — blocked on disk. `ollama create` needs **three
   copies at once** (source, the blob it copies into its store, and a full
   rewrite emitted by its `llama-quantize` validation): ~25 GB for an 8.4 GB
   model. judah had 20 GB and validation died reporting `ios_base::clear:
   unspecified iostream_category error`, which names an iostream failure and
   not the disk beneath it.
2. **Teach the console's load path about llama-server-served models.** This is
   the better fix: it is how the fleet already serves murakumo-edge and
   murakumo-27b, it costs no disk, and it removes the console's assumption that
   ollama is the only inference plane — the same assumption that had it
   reporting `resident: nothing` across a fleet that was serving models the
   whole time.
