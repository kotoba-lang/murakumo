# Image models for a 16 GB Apple silicon Mac

Surveyed 2026-11-25. Scope: text-to-image models whose published weights are marked Apache-2.0 or MIT, small enough for a 16 GB Apple silicon Mac, ranked by public preference data.

The table is sorted by Elo, highest first. It is not sorted by measured fit: only Z-Image-Turbo and FLUX.2 [klein] 4B have third-party Mac memory reports.

The author did not download or run any model. Memory, time and download sizes are third-party reports and were not re-checked (see Verification). A listing is not a legal opinion: check the LICENSE at the pinned revision, the bundled text encoder and VAE, and the rights to any training images before commercial use.

## How to read it

- **Quality**: Elo on [Artificial Analysis AA-Image-T2I v2.1](https://artificialanalysis.ai/image/leaderboard/text-to-image/open-weights), read 2026-11-25 (FLUX.2 [dev] = 1000). "—" means not listed. Z-Image (base) is not on that open-weights page (its entry has no open-weights link); its 896±16 comes from the all-models [text-to-image leaderboard](https://artificialanalysis.ai/image/leaderboard/text-to-image). English prompts; Japanese is not measured.
- **Size**: 4-bit file sizes from the Hugging Face API (`blobs=true`) for the community GGUF and MLX builds linked in each row, 2026-11-25. Q4 GGUF files hold the transformer only; the MLX 4-bit builds also ship a text encoder, whose size is given. Upstream repos are unquantized. It is a download size, not peak memory.
- **16 GB fit**: `reported` = someone measured a peak at or below 16 GB on Apple silicon (the reporter's machine is named); `claimed` = a source says it runs on 16 GB; `unverified` = no Mac measurement found.

## Candidates

| # | Model | License (weights) | Elo | Size (4-bit) | 16 GB fit | Notes |
|---|---|---|---|---|---|---|
| 1 | [HiDream-O1-Image](https://huggingface.co/HiDream-ai/HiDream-O1-Image) `0b0901d99f200389e138c61946af1185f5f49a13` | MIT (card metadata; no LICENSE file in the repo) | 978±10 | GGUF Q4_K_M 5.5 GB, single file ([moonbox-dev](https://huggingface.co/moonbox-dev/HiDream-O1-Image-GGUF), base model listed as HiDream-O1-Image) | unverified | 8B, best listed quality of the licenses here. The card lists an undistilled model and a distilled Dev variant; the Elo is for the undistilled one. The leaderboard also lists HiDream-O1-Image-1.5 (1020); its Hugging Face page is not publicly readable, so its license is unchecked. A community MLX port (moonbox-dev/HiDream-O1-Image-MLX) had 0 downloads at check time. Optional prompt agent needs a separate model |
| 2 | [Z-Image-Turbo](https://huggingface.co/Tongyi-MAI/Z-Image-Turbo) `f332072aa78be7aecdf3ee76d5c247082da564a6` | Apache-2.0 (card metadata; no LICENSE file in the repo) | 937±11 | GGUF Q4 3.7–4.9 GB ([leejet](https://huggingface.co/leejet/Z-Image-Turbo-GGUF), [unsloth](https://huggingface.co/unsloth/Z-Image-Turbo-GGUF)); MLX Q4 transformer 3.5 GB ([deepsweet](https://huggingface.co/deepsweet/Z-Image-Turbo-6B-MLX-Q4)). Text encoder: Qwen3 type, 2.3 GB in that MLX build | reported: peak 8.5 GB at 1024 px (M1 Max, 4-bit); 5.5 GiB (M6 mini 32 GB, 4-bit mflux) | 8 steps. 6B. Upstream says bilingual EN/ZH text |
| 3 | [ERNIE-Image-Turbo](https://huggingface.co/baidu/ERNIE-Image-Turbo) `bc68c81e2a1730a394d5fc9fae70713dee940140` | Apache-2.0 (LICENSE) | 916±10 | GGUF Q4 4.8–5.8 GB ([unsloth](https://huggingface.co/unsloth/ERNIE-Image-Turbo-GGUF)); MLX 4-bit transformer 4.5 GB ([themindstudio](https://huggingface.co/themindstudio/ERNIE-Image-Turbo-MLX-4bit)). Text encoder: Ministral-3 type, 1.9 GB in that MLX build | unverified | 8 steps. 8B. Optional prompt enhancer adds memory |
| 4 | [Z-Image](https://huggingface.co/Tongyi-MAI/Z-Image) `04cc4abb7c5069926f75c9bfde9ef43d49423021` | Apache-2.0 (card metadata; no LICENSE file in the repo) | 896±16 (all-models page only) | GGUF Q4 3.9–4.9 GB ([unsloth](https://huggingface.co/unsloth/Z-Image-GGUF)). Text encoder: Qwen3 type | unverified | Undistilled; upstream recommends it as a LoRA base. 28–50 steps |
| 5 | [FLUX.2 [klein] 4B](https://huggingface.co/black-forest-labs/FLUX.2-klein-4B) `e7b7dc27f91deacad38e78976d1f2b499d76a294` | Apache-2.0 (LICENSE.md is Apache-2.0 text; card says "Open weights available for commercial use under the Apache 2.0 license") | 839±12 | GGUF Q4 2.5–2.7 GB ([unsloth](https://huggingface.co/unsloth/FLUX.2-klein-4B-GGUF), [leejet](https://huggingface.co/leejet/FLUX.2-klein-4B-GGUF)); MLX 4-bit transformer 2.2 GB ([mlx-community](https://huggingface.co/mlx-community/FLUX.2-Klein-4B-4bit)). Text encoder: Qwen3 type, 2.3 GB in that MLX build | reported: peak 8.2–10.7 GiB (M6 mini 32 GB, 4-bit mflux), 10.1–14.1 GB (M1 Max); one source says 16 GB is enough, another says 24 GB | 4 steps, fastest, also edits. Lowest listed quality here |

## To test first, not ranked

- [MOSS-Image-SFT](https://huggingface.co/OpenMOSS-Team/MOSS-Image-SFT) `f972bd4b4b9e3852133cc5da954d87bc32cd8fb9`, Apache-2.0. 5B. Not on the leaderboard. Very slow at default settings on Apple silicon (about 16 minutes at 1024 px on an M1 Max; peak 9.1 GB in one app). Community few-step LoRAs exist.
- [Iris-3B](https://huggingface.co/speridlabs/iris-3b) `adc05b909fb05d4276d26aae5a52b36406b2ef26`, Apache-2.0. 3B, pixel space, 4-step turbo. Not on the leaderboard. English prompts.

## Training bases

| Base | Why | Not settled |
|---|---|---|
| Z-Image | undistilled, upstream says good for LoRA; Turbo sibling for serving | whether a LoRA trained on it works on Turbo |
| FLUX.2 [klein] base 4B `a3b4f4849157f664bdbc776fd7453c2783562f4d` | undistilled, smallest | lower quality than Z-Image |
| ERNIE-Image `5346b31d68c9c23758ba56ef8be5e9dc174c7f99` | SFT model of the Turbo | 50 steps |

## Excluded

| Model | Reason |
|---|---|
| Qwen-Image-2.1, Qwen-Image-2.1-Turbo and their GGUF/fine-tunes | Hugging Face license field is `other` / `qwen-research`. The Qwen-Image-2.1-Turbo LICENSE (read) grants use "FOR NON-COMMERCIAL PURPOSES ONLY" (section 2a) and bars commercial use without a separate commercial license from Qwen (2b). Qwen-Image-2.1 carries the same license field |
| FLUX.2 [klein] 9B | License field is `flux-non-commercial-license` |
| FLUX 3 [klein], Krea 2, HunyuanImage 3.0 | Community licenses with conditions; not checked here (unverified) |
| Qwen-Image-2512 | Apache-2.0 (license field checked), but 4-bit transformer is 10–13 GB plus a 16.6 GB (unquantized) text encoder; a Mac app lists 32 GB minimum |
| Ming-Image-0.1-Design, GLM-Image, LongCat-Image | Open license, but the text side is 17–34 GB unquantized, or quality is below the candidates |

## Before promoting a model to a manifest

1. Read the LICENSE at the pinned revision, and the licenses of the bundled text encoder and VAE (not stated on the Z-Image cards).
2. Run on a real 16 GB Mac under the workload it will share the machine with. Record peak memory, swap growth and time at 768 and 1024 px.
3. Compare the same prompts, including Japanese text, against a fixed baseline.
4. Pin the revision and SHA-256 of every file, as `resources/image-models/` requires.

## Sources

Model cards and API metadata at the revisions above; [turbo-mlx](https://github.com/rinste/turbo-mlx); [Teamday, Oct 2026](https://www.teamday.ai/blog/best-local-image-generation-models-2026); [ModelFit](https://modelfit.io/guides/local-image-generation-mac/); Artificial Analysis leaderboard above.

## Verification (2026-11-25)

Checked against Hugging Face API metadata and files, and the Artificial Analysis page:

- Model ids exist and are not gated. Each revision prefix matches the head of `main` at check time; full 40-character shas are used above, and the list is not pinned to them beyond that.
- The license field matches the table for every listed model. The LICENSE or LICENSE.md file exists and is Apache-2.0 text for ERNIE-Image-Turbo, FLUX.2-klein-4B and MOSS-Image-SFT (and for the klein base and ERNIE-Image files, by the same listing). Z-Image, Z-Image-Turbo, HiDream-O1-Image and Iris-3B have only the card metadata field.
- Elo values and the ± (95% interval half-width) match the leaderboard (Z-Image Base only on the all-models page): HiDream-O1-Image 978.28 ±10, Z-Image Turbo 937.18 ±11, ERNIE Image Turbo 915.92 ±10, Z-Image Base 896.49 ±16, FLUX.2 [klein] 4B 839.03 ±12, against FLUX.2 [dev] = 1000. Leaderboard is "AA-Image-T2I v2.1 (Open Weights)".
- klein 4B and klein base 4B: LICENSE.md is Apache-2.0 text (176 lines each) and both READMEs state Apache-2.0. The 4B card says "Open weights available for commercial use under the Apache 2.0 license"; the base card says "Outputs can be used for commercial purposes, as described in the Apache 2.0 license". Both cards say the 9B model is non-commercial.
- Qwen-Image-2.1-Turbo LICENSE read: non-commercial only (2a), separate commercial license required (2b).
- 4-bit sizes: re-read through the API with `blobs=true` on the builds linked in the table. Z-Image-Turbo GGUF Q4: 3.68 (leejet Q4_0) to 4.85 GB (unsloth Q4_1); MLX Q4 transformer 3.46 GB. ERNIE-Image-Turbo GGUF Q4: 4.76 to 5.78 GB; MLX 4.52 GB. klein 4B GGUF Q4: 2.46 to 2.69 GB; MLX 2.18 GB. Z-Image GGUF Q4: 3.85 to 4.85 GB. HiDream-O1-Image GGUF Q4_K_M: 5.53 GB. The earlier ranges differed slightly and were replaced by these.
- Text encoders, from each repo's `text_encoder/config.json`: Z-Image, Z-Image-Turbo, klein 4B and klein base 4B use `Qwen3ForCausalLM`; ERNIE-Image-Turbo uses `Mistral3Model` with a `mistral` text config (ERNIE-Image: `ministral3`). Qwen/Qwen3-4B and mistralai/Ministral-3-3B-Instruct-2512 are Apache-2.0 on their own cards. The model sizes (4B, 3B) were not read from the configs, and the license of the exact copy bundled in each repo is not stated there.
- Parameter counts: Z-Image-Turbo 6B (card; 6.15B in safetensors metadata), MOSS-Image-SFT 5.08B (safetensors metadata), HiDream-O1-Image 8B (card). The Z-Image-Turbo card states bilingual English and Chinese text rendering.

Not verified: that the community builds' quality matches upstream, all memory, speed and "fits 16 GB" reports, step counts, ERNIE and Iris-3B parameter counts, the VAE licenses, license text of FLUX 3, Krea and Hunyuan models, the Qwen-Image-2512 text encoder size, and the 32 GB minimum of the Mac app.
