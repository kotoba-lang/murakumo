# ADR-260803: `:engine :waste` — single-node serving with disk-streamed experts

- Status: accepted
- Date: 2026-08-03
- Depends: ADR-260728-w6-product-shell-oracle-authority, ADR-260731-w6-t62-precompiled-kir-default

## Context

[sqliteai/waste](https://github.com/sqliteai/waste) (WASTE — Weight-Aware
Streaming Tensor Engine) runs the full 2.78T-parameter Kimi K3 on a 64 GB
MacBook Pro at 0.45–0.62 tok/s by keeping the model trunk resident and
streaming only the selected experts off NVMe, using the remaining RAM as a
bounded expert cache. Experts are stored at 3-bit residual vector
quantization, shared weights at 4/8 bits; the container is laid out so one
expert is one aligned read, and a lookahead router starts those reads before
the real router needs them.

murakumo already had this shape for `:engine :mlx-moe` — one node, partial
expert residency, cache capacity derived from usable RAM. What it did not
have is the constraint that actually decides a waste deployment: **disk**. A
K3 container is 982 GB and every token reads experts out of it. The fleet
probe has collected `:disk-free-bytes` since the media-model gate
(`schedule.cljc`), but no planner had ever decided on it.

Two independent questions had to be answerable **before** provisioning,
because conversion is a one-way ~5-hour job on ~1.5 TB of downloaded weights:

1. what will this model cost in RAM on a given node, and
2. what ceiling will the engine actually run under there.

`waste plan` answers both exactly, but reads the converted container's
`manifest.json` — which only exists afterwards.

## Decision

Add `:engine :waste` as a third single-node engine, with the scalar core in
Kotoba as a product-shell oracle.

1. **`kotoba/infer_waste_core.kotoba`** (oracle `:infer-waste`, 31 exports).
   Two models, both **ported from upstream rather than invented**:
   - the pre-flight RAM floor, from waste `tools/memplan.py` — trunk, KDA/MLA
     state, scratch, minimum expert cache, computed from a HuggingFace
     `config.json` alone;
   - the engine's **own budget resolution**, from waste `src/waste.c`
     (`waste_open`): `cap = usable - usable/8`, then the recommended budget
     `floor + 3*working-set` is stepped DOWN one whole working set at a time
     until it fits under `cap`. It never spends the remainder up to the cap —
     that is what turns a cache hit into a page fault, measured upstream at
     0.07 tok/s against 0.63.
   Integer-only, so bit widths arrive as milli-bits (trunk 4250, experts 2120)
   and the Gate-5 hit curve is interpolated in milli. No capabilities.

2. **`murakumo.infer.waste`** — `config->shape` (HF config → `:model/*`,
   unwrapping a multimodal `text_config` the way memplan.py does), `memplan`,
   `budget`, `throughput`, `verdict`, and a `plan` with the same 2-arg shape as
   `murakumo.infer.moe/plan` so `infer.clj`'s `probe-and-plan` drives either.
   **Ranking differs: candidates are ordered by container fit BEFORE memory.**
   A 512 GB node with 40 GB free cannot serve K3 at any speed.

3. **`:saturating-budget-bytes`** — the budget at which the cache holds the
   entire routed expert set and disk leaves the decode loop. The engine cannot
   choose this itself: its recommendation is `floor + 3` working sets, which
   for a model smaller than the machine leaves most of RAM idle while every
   token still reads experts off disk. On this fleet's 32 GiB M4 with
   Kimi-Linear that is 2.44 GB chosen against 13.72 GB available — this is the
   concrete reason to plan with murakumo rather than run `waste` by hand.

4. **Measured beats analytic, visibly.** `:model/ram-floor-bytes`,
   `:model/expert-set-bytes`, `:model/working-set-bytes` override the analytic
   estimate and `:floor-source` reports which was used. memplan.py's own
   docstring notes its accurate path reads exact tensor sizes from safetensors
   headers; the analytic path is the rough one. Measured against upstream's
   published figures it lands within ~1% on Kimi-Linear (1.27 GB against a
   documented 1.28) and **is out by 40% on K3** — 17.51 GiB against a real
   29.06 GB floor, and 1344 GB of experts against a real 982 GB container. A
   planner that believed the analytic K3 floor would green-light a 24 GB node
   that cannot open the model, so `kimi-k3` carries the measured floor.

5. **`engine/waste-cmd` + `waste-serve-cmd`**, `:waste` in `engine/commands`.
   Budgets pass in **bytes** — waste's `parse_size` accepts a bare number as
   bytes, so the planner's figure goes through exactly instead of being rounded
   to whole GiB. `waste-serve-cmd` binds `0.0.0.0` rather than serve/'s own
   `127.0.0.1`: a head the fleet cannot reach is not a head.

6. **`cmd-serve-waste` has a local path.** Unlike mlx-moe, the container is a
   file tree on some machine's NVMe and that machine may be the operator's own;
   `cmd-plan-waste`'s chosen node without a `:host` runs in the foreground.

## Non-claims

- **This does not make K3 runnable on this fleet.** Registered so `plan`
  reports it with numbers: 982 GB of container against gad's 466 GB NVMe and
  16 GB Mac minis. `plan kimi-k3` returns `no-container-space`.
- The analytic floor is an estimate. `waste plan --json` on a converted
  container is the truth and its numbers belong in the registry.
- Throughput is a **disk-bound ceiling** — bytes read per token against
  measured random-read bandwidth. Compute is not in it.
- The Gate-5 hit curve was measured upstream on Kimi-Linear batch-1 decode;
  K3's 896-expert routing may differ.
- waste itself is vendored as an external engine binary, like llama.cpp and
  mlx-moe. No C is authored here.

## Evidence

- Budget resolution reproduces waste's README run exactly: on 64 GB with K3,
  `cap 56.00 GB / budget 46.25 GB / cache 17.56 GB` against the README's
  printed `using 46.25 GB of 64.00 GB (expert cache 17.56 GB)`.
- `memplan` reproduces `tools/memplan.py` to its printed precision for both
  Kimi-Linear (TRUNK 1.01, FLOOR 1.18, experts 12, working set 0.4 GiB) and
  K3 (TRUNK 16.38, FLOOR 17.51, experts 1344, working set 24.0 GiB).
- `test/murakumo/infer_waste_kotoba_parity_test.clj` — fresh compile of the
  `.kotoba` source vs the host path executing the shipped KIR.
- `test/murakumo/infer_waste_test.cljc` — upstream goldens, the disk-before-RAM
  ranking, and the engine command strings.
- Catalog is now 33 cores (`kotoba_oracle_cljs_load_test`).

## Addendum 1 (2026-08-03, same day): Kimi-Linear converted and run — the
## analytic path is low across the board, not "within rounding"

Kimi-Linear-48B-A3B-Instruct was downloaded (92 GB), converted (waste 0.6.3
`tools/convert.py --jobs 3`, ~54 min on this M4, 26 layers × 651 MB + a
1354 MB trunk) and run. **A claim in the original Evidence section was wrong
and is corrected here.**

Measured (`waste plan --json` at ctx 4096, byte-exact container listing):

| | analytic | measured | error |
|---|---:|---:|---:|
| RAM floor | 1.18 GiB | **1.28 GiB** | −8% |
| expert set on disk | 11.63 GiB | **16.53 GiB** | −30% |
| working set / token | 0.36 GiB | **0.54 GiB** | −32% |
| container | — | **19,171,317,244 B** | — |

The original text said the analytic path lands "within ~1% on Kimi-Linear
(1.27 GB against a documented 1.28)". That was a **unit error**: upstream's
"1.28 GB minimum RAM" is GiB, and it was compared against 1.27 *decimal* GB.
The real miss is 8%, and the two disk terms are out by ~30%.

Two causes, both checkable:

1. memplan.py assumes **2.12 bits/weight** for experts. The container
   `convert.py` actually produced stores 682,622,976 B per layer for
   256 × 3·2304·1024 params = **3.014 bits/weight**.
2. waste leaves `embed_tokens` **on disk** (`src/waste.c` skips it when
   summing the resident trunk) while memplan.py counts it — 383 MB here.
   This is why the floor is only 8% off while the expert set is 30% off: the
   two errors partly cancel.

The direction does not generalise either: on K3 the same path is **low** on
RAM (17.5 GiB vs 29.06 GB) and **high** on disk (1344 GB vs 982 GB).

Consequences already applied: the registry entry carries the measured floor,
expert set, working set, min expert cache and container size, plus
`:model/expert-milli-bits 3014`; `memplan` reads `:model/trunk-milli-bits` /
`:model/expert-milli-bits` / `:model/min-cache-bytes` so a family that has
converted once informs the estimate for the next member. The port stays
faithful to memplan.py — parity with it is a checkable property — and the
measured-override mechanism is where accuracy comes from.

### The saturating budget, measured

Same prompt, same container, same machine (M4 / 32 GiB, container on a USB
volume), `-n 48`:

| budget | source | cache | hit | tok/s |
|---|---|---:|---:|---:|
| 2.89 GB | engine's own default | 1.65 GB | 78% | **0.13** |
| 19.08 GB | `:saturating-budget-bytes` | 17.7 GB | 94% | **0.44** |

**3.4×**, from a number the engine cannot choose for itself — its
recommendation is `floor + 3` working sets and it stops there. This is the
concrete answer to "why plan with murakumo instead of running `waste` by
hand".

### Why 0.44 and not upstream's 10.65

`tools/diskbench` on the volumes involved:

| volume | random read |
|---|---:|
| upstream test machine (M5 Pro internal) | 12.78 GB/s |
| this Mac, internal SSD | 2.89 GB/s |
| **this Mac, the USB volume holding the container** | **0.03 GB/s** |

30 MB/s. The container had to go there because the internal disk has 15 GiB
free against a 17.9 GiB container. That single number accounts for both runs:
the default-budget run missed 2236 experts × 2.67 MB ≈ 6.0 GB → ~199 s at
30 MB/s against 378 s measured (plus a 1.4 GB cold trunk read); the saturating
run missed 599 × 2.67 MB ≈ 1.6 GB → ~53 s + 47 s trunk ≈ 100 s against 108 s
measured. Both within ~10%. `time` confirms it: 84 CPU-seconds against 193 s
wall — the machine was waiting, not computing.

So Kimi-Linear on this node is **storage-bound by two orders of magnitude**,
and the fix is not a bigger budget but ~3 GiB of free space on the internal
SSD. Recorded rather than acted on: that is the operator's data to delete.

One honest gap in the model: the Gate-5 hit curve predicted ~52% at the
default budget's 9.3% cache fraction, and the run measured 78%. The prompt
produced a degenerate repetitive continuation (no chat template ships for
kimi-linear, so the CLI falls back to raw continuation), which gives
unusually high expert locality. The curve is not wrong so much as measured on
a different workload — it is an estimate and is labelled as one.

## Addendum 2: regenerating KIR

ADR-260731-w6-t62 documents `clojure -M:test -m murakumo.kotoba-oracle-gen`.
That does not work — command-line main opts are appended to an alias's
`:main-opts`, not substituted, so the `-m` reaches `cognitect.test-runner` as
an unknown option and exits 1. A later alias's `:main-opts` does win, so
`deps.edn` gains a deps-free `:gen` alias and the command is:

```bash
clojure -M:test:gen
```
