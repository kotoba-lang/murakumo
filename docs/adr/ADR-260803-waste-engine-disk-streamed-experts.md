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

## Addendum: regenerating KIR

ADR-260731-w6-t62 documents `clojure -M:test -m murakumo.kotoba-oracle-gen`.
That does not work — command-line main opts are appended to an alias's
`:main-opts`, not substituted, so the `-m` reaches `cognitect.test-runner` as
an unknown option and exits 1. A later alias's `:main-opts` does win, so
`deps.edn` gains a deps-free `:gen` alias and the command is:

```bash
clojure -M:test:gen
```
