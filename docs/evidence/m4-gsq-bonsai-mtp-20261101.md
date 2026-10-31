# Apple M4 16 GB: GSQ-RCO and Bonsai 2 MTP observations

These are single-request observations on one Apple M4 Mac mini with 16 GB unified memory and macOS 26.2. They are measurements of third-party engines, not measurements of kotoba inference or Murakumo's self-hosted inference engine. They do not establish model quality or a general performance ranking.

## Qwen3.8 27B GSQ-RCO IQ2_XS: llama.cpp

Observed 2026-11-01 (JST). The [ISTA-DASLab GSQ-RCO IQ2_XS MTP GGUF](https://huggingface.co/ISTA-DASLab/Qwen3.8-27B-GSQ-RCO-GGUF) was verified against SHA-256 `f3369f8d36968ab7dd12f5ff11452680aef9371aa19c9922d22ccd5e58c2d9c8`. The same MTP-containing file was used for all three configurations; the baseline used `--spec-type none`.

Runtime: llama-server 0.2.0-dev, build 10709, commit `9a9394a89`, Apple Metal, requested `-ngl 99`, Flash Attention on, context 2048, one slot, four CPU threads. An existing inference service was stopped for the measurements and restored afterwards; other OS/background processes remained.

Each configuration had one warm-up request followed by three measured requests. The raw-completion prompt was `Explain how a database index works, including examples and trade-offs.\n` (15 prompt tokens). Each request used temperature 0, prompt caching off, 128 output tokens, and `ignore_eos=true`. The server-reported decode rates are:

| Configuration | Run 1 (tok/s) | Run 2 (tok/s) | Run 3 (tok/s) | Mean (tok/s) | Draft accepted |
| --- | ---: | ---: | ---: | ---: | --- |
| No speculation | 7.5860 | 7.5880 | 7.5777 | **7.5839** | — |
| MTP, max 1 | 7.2329 | 7.2266 | 7.2327 | **7.2307** | 54/72 per run (75.0%) |
| MTP, max 3 | 5.3525 | 5.3556 | 5.3483 | **5.3521** | 80/137 per run (58.4%) |

With this prompt and runtime, MTP max 1 was about 4.7% slower and max 3 about 29.4% slower than the no-speculation baseline. The server logged creation of an MTP draft context. Returned completion **text** was identical across all measured runs and settings; token-ID parity was not independently established. This is not a multi-prompt quality or long-context evaluation.

## Ternary Bonsai 2 27B: MTPLX

Observed 2026-10-31 (JST), on the same class of machine. The [MTPLX Bonsai 2 pack](https://huggingface.co/Youssofal/Ternary-Bonsai-2-27B-MTPLX-Optimized-Speed) was checked by `mtplx inspect`. The main weights had SHA-256 `130de5925082c168b7866b2e91b52e44abbafc99017e3ca352b77b5b55a269ed`, and the MTP sidecar had SHA-256 `f803737b0e64bcce54fcfa22f2c7ee342ad37192b8e2a3299fc940387a828c9d`.

Runtime: MTPLX 2.12.2, requested MTP depth 1 or autoregressive mode, default fan mode, reasoning disabled. One warm-up request and three measured requests used the same database-index question in a chat message (26 prompt tokens), temperature 0 and max 128 output tokens.

| Configuration | Run 1 (tok/s) | Run 2 (tok/s) | Run 3 (tok/s) | Mean (tok/s) |
| --- | ---: | ---: | ---: | ---: |
| Autoregressive | 12.9989 | 13.0659 | 13.0497 | **13.0382** |
| MTP depth 1 | 19.8464 | 19.8814 | 19.8933 | **19.8737** |

Rates are MTPLX's server-reported decode rates. MTP was about 52.4% faster on this one request. All returned completion text matched in this test; this does not establish token-ID parity or general quality equivalence.

## Interpretation and limits

- These two tables use different models, runtimes, prompt formats and measurement definitions. They do not constitute an engine-to-engine or quality-matched comparison.
- Conditions were not paired or interleaved, and no independent repeated session, thermal sweep, sustained-load test, quality benchmark or context-length sweep was performed.
- An earlier exploratory distributed exo run did not reach a successful inference measurement. No distributed speedup or exo compatibility is claimed.
- Raw local result files contain the synthetic prompt and responses; only the numerical observations and artifact identifiers are reproduced here.
- The results are a bounded baseline for further paired, alternating and correctness-gated optimization. They do not satisfy the self-hosted comparison gate tracked in [JUN-187](https://linear.app/kotobalabs/issue/JUN-187).
