# Image models for a 16 GB Apple silicon Mac

Surveyed 2026-11-25. Scope: text-to-image models whose published weights are marked Apache-2.0 or MIT, small enough for a 16 GB Apple silicon Mac, ranked by public preference data.

The author did not download or run any model. Memory, time and download sizes are third-party reports and were not re-checked (see Verification). A listing is not a legal opinion: check the LICENSE at the pinned revision, the bundled text encoder and VAE, and the rights to any training images before commercial use.

## How to read it

- **Quality**: Elo on [Artificial Analysis AA-Image-T2I v2.1](https://artificialanalysis.ai/image/leaderboard/text-to-image/open-weights), read 2026-11-25 (FLUX.2 [dev] = 1000). "—" means not listed. English prompts; Japanese is not measured.
- **Size**: smallest 4-bit transformer / denoiser file found (GGUF or MLX) in community builds, plus the text encoder. It is a download size, not peak memory.
- **16 GB fit**: `reported` = someone measured a peak at or below 16 GB on Apple silicon (the reporter's machine is named); `claimed` = a source says it runs on 16 GB; `unverified` = no Mac measurement found.

## Candidates

| # | Model | License (weights) | Elo | Size (4-bit) | 16 GB fit | Notes |
|---|---|---|---|---|---|---|
| 1 | [Z-Image-Turbo](https://huggingface.co/Tongyi-MAI/Z-Image-Turbo) `f332072aa78be7aecdf3ee76d5c247082da564a6` | Apache-2.0 (card metadata; no LICENSE file in the repo) | 937±11 | 3.7–5.0 GB + Qwen3-4B text encoder | reported: peak 8.5 GB at 1024 px (M1 Max, 4-bit); 5.5 GiB (M6 mini 32 GB, 4-bit mflux) | 8 steps. 6B. Upstream says bilingual EN/ZH text |
| 2 | [ERNIE-Image-Turbo](https://huggingface.co/baidu/ERNIE-Image-Turbo) `bc68c81e2a1730a394d5fc9fae70713dee940140` | Apache-2.0 (LICENSE) | 916±10 | 4.8–5.0 GB + Ministral-3-3B text encoder (about 2 GB) | unverified | 8 steps. 8B. Optional prompt enhancer adds memory |
| 3 | [FLUX.2 [klein] 4B](https://huggingface.co/black-forest-labs/FLUX.2-klein-4B) `e7b7dc27f91deacad38e78976d1f2b499d76a294` | Apache-2.0 (LICENSE.md; card says commercial use, unverified) | 839±12 | 2.4–2.6 GB + Qwen3-4B | reported: peak 8.2–10.7 GiB (M6 mini 32 GB, 4-bit mflux), 10.1–14.1 GB (M1 Max); one source says 16 GB is enough, another says 24 GB | 4 steps, fastest, also edits. Lowest listed quality here |
| 4 | [Z-Image](https://huggingface.co/Tongyi-MAI/Z-Image) `04cc4abb7c5069926f75c9bfde9ef43d49423021` | Apache-2.0 (card metadata; no LICENSE file in the repo) | 896±16 | 3.9–5.0 GB + Qwen3-4B | unverified | Undistilled; upstream recommends it as a LoRA base. 28–50 steps |
| 5 | [HiDream-O1-Image](https://huggingface.co/HiDream-ai/HiDream-O1-Image) `0b0901d99f200389e138c61946af1185f5f49a13` | MIT (card metadata; no LICENSE file in the repo) | 978±10 | 5.5 GB (single file) | unverified | 8B, best listed quality of the licenses here. The card lists an undistilled model and a distilled Dev variant; the Elo is for the undistilled one. The leaderboard also lists HiDream-O1-Image-1.5 (1020); its Hugging Face page is not publicly readable, so its license is unchecked. Community MLX port has no adoption. Optional prompt agent needs a separate model |

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
| Qwen-Image-2.1, Qwen-Image-2.1-Turbo and their GGUF/fine-tunes | Hugging Face license field is `other` / `qwen-research`; the draft says non-commercial unless a separate license is obtained (license text not re-read) |
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
- Elo values and the ± (95% interval half-width) match the leaderboard: HiDream-O1-Image 978.28 ±10, Z-Image Turbo 937.18 ±11, ERNIE Image Turbo 915.92 ±10, Z-Image Base 896.49 ±16, FLUX.2 [klein] 4B 839.03 ±12, against FLUX.2 [dev] = 1000. Leaderboard is "AA-Image-T2I v2.1 (Open Weights)".
- Parameter counts: Z-Image-Turbo 6B (card; 6.15B in safetensors metadata), MOSS-Image-SFT 5.08B (safetensors metadata), HiDream-O1-Image 8B (card). The Z-Image-Turbo card states bilingual English and Chinese text rendering.

Not verified: all 4-bit file sizes (they refer to community GGUF/MLX builds, not the upstream repos, whose weights are unquantized), all memory, speed and "fits 16 GB" reports, step counts, ERNIE and Iris-3B parameter counts, the text encoder and VAE licenses, license text of the Qwen, FLUX 3, Krea and Hunyuan models, the Qwen-Image-2512 text encoder size, and the 32 GB minimum of the Mac app.
