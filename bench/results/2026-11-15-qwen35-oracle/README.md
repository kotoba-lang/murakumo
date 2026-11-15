# Qwen3.5-4B: numpy oracle pinned against llama.cpp (2026-11-15)

First slice of Linear AWAI-2938 (Qwen3.5 support). [`ref_forward35.py`](../../kernels/qwen35-metal/ref_forward35.py)
is a local test-time oracle. It is not a gate or a route, in line with the oracle rule. It reads the GGUF directly:
it dequantizes Q8_0, Q4_K, Q5_K, Q6_K and F32, and runs the hybrid model layer-major in numpy.

## Inputs

- Model: `unsloth/Qwen3.5-4B-GGUF` @ e87f176, `Qwen3.5-4B-Q4_K_M.gguf`, sha256
  `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4` (downloaded with owner approval for E0).
- Prompt ids: `760 6511 314 9338 369` ("The capital of France is", from `llama-tokenize`). These are the same ids
  the Qwen2.5 gate uses.

## Results

| Source | Greedy continuation |
| --- | --- |
| llama.cpp 0.7.0 b11669, `llama-server` `/completion` (temperature 0, top-k 1) ([raw](raw/llama-oracle.json)) | `11751 13 198 32 13 2912 198 33 13 3439 198 15666 25 198 32 271` (" Paris.\nA. True\nB. False\nAnswer:\nA\n\n") |
| `python3 ref_forward35.py FILE --steps 6` (Python 3.15.0, numpy 2.5.4) ([raw](raw/ref35-6.txt)) | `11751 13 198 32 13 2912` |

The first six tokens are identical. At step 3, the two best candidates are 0.106 apart in the oracle's logits
(32 against 760). llama.cpp's log-probabilities put them 0.116 apart. Step 0's gap to the runner-up is 2.146
here and 2.160 there.

## Model facts the oracle settled

- **Value-head mapping:** the 32 delta-net value heads are **tiled** onto the 16 key heads (key head = value head mod 16).
  The repeat-interleaved mapping that mlx-lm uses on the Hugging Face weights gives token 279 instead. llama.cpp's
  conversion reorders the value heads.
- **Norm weights:** the attention, post-attention, q/k and output norm weights are stored with +1 already applied
  (attn_norm mean 1.14).
- **Decay:** `ssm_a` holds −exp(A_log) (all negative), so the decay is exp(ssm_a · softplus(alpha + dt_bias)).
- **Attention q:** `attn_q` is laid out per head as [q (256) | gate (256)].
- **Convolution:** `ssm_conv1d.weight` is [channels, 4], and tap i multiplies time t − 3 + i.

Next slice: Metal K-quant matvec kernels, checked against this dequantization, then a whole-model Metal decode
gated on these tokens.
