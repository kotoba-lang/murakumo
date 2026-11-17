# Qwen3.5-4B with DFlash drafts (2026-11-17)

Linear AWAI-3012 (under AWAI-2938). The DFlash block drafter (z-lab/Qwen3.5-4B-DFlash) now drives a drafted greedy
decode on the Metal route. Its output is lossless: every emitted token is the main model's argmax. It is not yet
faster than plain decode.

## How it runs

The drafter is a 6-layer Qwen3 stack:

| | |
| --- | --- |
| Shape | hidden 2560; 32 query heads and 8 KV heads of 128; MLP 9216 |
| Input | the main model's residual rows after layers 1, 5, …, 29, joined and projected by `fc` |
| Output | a block of up to 16 tokens per cycle |
| Source | z-lab/Qwen3.5-4B-DFlash @ 9a1996c, BF16 safetensors |
| sha256 | `1eb221d36abb13a5f1b972f8d031a9723fad8cbb7d275abe548b60e77577eb42` (Kapitsa 0.2.36 downloaded the same file for [E0](../2026-11-15-e0-kapitsa/README.md), and its manifest records this digest) |

The program checks the pinned digest before any other read. It converts the matrices to Q8_0 on the GPU once.

The semantics follow `dflash/model.py` of z-lab/dflash @ 07ebd93, read on GitHub (not vendored):

- `fc` and `hidden_norm` turn the captured rows into the drafter's context.
- In each layer:
  - q comes from the block;
  - k and v come from the context rows and from the block;
  - q and k get an RMSNorm per head and NEOX RoPE (base 1e7) at their positions;
  - attention covers the whole context and the block.
- The five sliding-window layers attend to the block causally. The full-attention layer sees the whole block. The
  first version ran every layer non-causally; the reference code says otherwise.
- The block is the last emitted token followed by mask tokens (id 248077). The tied head of the main model gives
  the drafts.

One cycle is one command buffer:

1. **Replay** (`dnr`). The last verify pass saved the delta-rule inputs of each delta-net layer: q and k after norm,
   v, the decay, beta and the conv inputs. `dnr` advances the recurrent states over the tokens that pass accepted.
   A rejected draft never reaches the state, and per-token state snapshots (2 MB per layer per token) are not needed.
2. **Context update.** The accepted rows go into the drafter's context: `fc`, then k and v into its six KV caches.
3. **Drafted block.** The block of bs rows (`qkn`, `attb`) produces bs − 1 drafts.
4. **Verify pass.** One pass of bs tokens through the main model (`mm`, `dnfn` from #699) checks the drafts. It also
   captures its residual rows (`cpy`) and saves the delta-rule inputs.

Draft j + 1 is accepted while it equals the verify argmax j. The cycle emits the verify argmaxes up to the first
rejected draft's position.

The prompt runs in 16-token passes, and its captured rows fill the drafter's context.

The program is `bench/kernels/qwen35-metal/qwen35_dflash.kotoba.in`. The block size bs is a build parameter (3–16).

## Speed

Builds ran interleaved, on the same prompts as before. Raw data: [`raw/rounds.txt`](raw/rounds.txt).

**Chat prompt with an empty think block** (34 tokens), 128 tokens:

| Build | ms/token | Cycles / accepted drafts |
| --- | ---: | ---: |
| plain | 16.9–17.0 | — |
| DFlash, block 4 | 23.9 | 59 / 68 |
| DFlash, block 8 | 24.6 | 59 / 68 |
| DFlash, block 16 | 26.1–30.1 | 59 / 68 |

That is 2.15 tokens per cycle, and no accepted run was longer than three drafts.

**The same prompt opening a think block** (30 tokens), 256 tokens, three rounds:

| Build | ms/token | Cycles / accepted drafts |
| --- | ---: | ---: |
| plain | 18.2–19.5 | — |
| DFlash, block 8 | 16.6–18.7 | 79 / 179 (3.3 tokens per cycle) |
| DFlash, block 16 | 16.8–21.0 | 75 / 184 (3.5 tokens per cycle) |

Every build produced the same tokens as plain decode.

A cycle costs about 56 ms, and most of that does not shrink with the block:

- **The verify pass.** `mm` always computes 16 token columns, so a 4-token verify costs nearly as much as a 16-token
  one. The head adds a 16-row pass over the 248,320-row embedding.
- **The drafter.** About 10 ms.

So drafting pays off only when a cycle yields more than about three tokens, and at that rate it roughly breaks
even. For reference, Kapitsa's E0 run reported 0.73 of tokens from accepted drafts, but with tree verification and
on output flagged as a repetition loop.

The next levers:

- an `mm` sized to the verify width (4 or 8 columns);
- the head only on the rows that can still be accepted;
- a cheaper drafter (Q4 weights, fused small stages);
- tree verification.

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row. New rows:

- **dflash-exact.** On the think prompt, 48 tokens equal plain decode's.
- **dflash-accept-mutant.** Accepting every draft without the check changes the tokens.
- **dflash-replay-mutant.** Advancing the delta-net states over the whole block instead of the accepted tokens
  changes the tokens.
- **dflash-ctx-mutant.** Not updating the drafter's context with the accepted tokens leaves the tokens equal, and
  fewer than half as many drafts are accepted.
- **dflash-digest.** A digest that is off by one character refuses before any token.

The drafter was also checked against a numpy reference (`bench/kernels/qwen35-metal/ref_dflash.py`, a local oracle
run once, not part of the gate). Both took the same captured rows of the 5-token prompt. The engine's layer-0 values
(q after RoPE, the KV cache of context and block, the causal attention output) agree within the Q8_0 rounding
(largest differences 0.012–0.07 on values of size 1–5). The non-causal variant's 15 drafts were equal token for
token.
