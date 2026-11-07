# VAE attention with a resident Q tile: measured, slower (stage M slice 35, JUN-927)

Recorded 2026-11-06T22:03:01+0900 on 21mbp.local, M1 Max, load average 10.56 10.48 14.70. Docs only; no program changed.

I had written off "keep Q resident for the VAE's 512-wide attention" because 32 queries x 512 x 2 bytes is the whole 32 KB of threadgroup memory. It fits with 16 queries per threadgroup (16 KB for Q, two simdgroups of 8 rows, 64 threads), so I built and measured it: the Q tile is loaded once from device memory into threadgroup memory and every `simdgroup_load` of Q in the key loop reads it from there, everything else as in the online-softmax kernel of slice 32.

GPU time of `attn_wide` over a 1024 px decode, alternated binaries, three runs (pass 1 / pass 2):
| variant | ms |
|---|---|
| current (32 queries per threadgroup, Q reloaded from device) | 888 to 1,404 / 807 to 876 |
| resident Q, 16 queries per threadgroup | 1,293 to 1,346 / 1,260 to 1,386 |
About 50 percent **slower**: halving the queries per threadgroup doubles the K and V traffic per query, which costs more than the Q loads it removes. Rejected; the kernel stays as it is. Together with the earlier negative results (shared Q load, untransposed K) this closes the attention tuning I know how to do without a different algorithm (for example a split over the key axis).

Also checked, for completeness of the "real checkpoint" path: there is no SDXL-class (UNet, VAE, CLIP) checkpoint on this Mac. A search of the home directory for `.safetensors`, `.ckpt` and `.gguf` files over 20 MB found only unrelated models (a 40M multilingual gguf, Qwen3-ASR-0.6B, Qwen3-TTS-Tokenizer, a murakumo onion model, a granite partial update, two gguf fixtures); the Hugging Face cache holds only a wikitext dataset, and ComfyUI's `models/checkpoints` is the empty directory of the source tree. A trained-weight check of the image pipeline therefore needs a download, which is decision D1.
