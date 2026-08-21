# ADR-260821f: RunPod H100 NVL does not beat Hyperstack for resident batched serving

- Status: accepted
- Date: 2026-08-21
- Continues: ADR-260821b, ADR-260821e
- Raw result: `data/ADR-260821f-qwen38-27b-runpod-h100-nvl-mtp.json`
- Reproduce with: `tools/hyperstack-bench/qwen38_27b_bench.py`

## Decision

Keep Hyperstack H100 PCIe spot as the resident-serving value pick for
Qwen3.8-27B-FP8. A physical RunPod H100 NVL 94GB is faster for single-stream
and moderate batches, but is effectively tied at concurrency 128 and costs
58.0% more per generated token at the currently available Secure rate.

The load-bearing RunPod estimate is now closed. RunPod Community NVL was out
of stock. Even if the measured Secure result is repriced at the old $2.59/hour
Community rate, its measured c128 throughput implies $0.256/Mtok, not the old
$0.145/Mtok estimate, and it still loses to Hyperstack's measured $0.200.

## Measured hardware and configuration

- RunPod Secure Cloud, physical `NVIDIA H100 NVL`, 93.09 GiB usable VRAM
- 16 vCPU, 180 GiB RAM, 100 GB container disk
- RunPod PyTorch Ubuntu 24.04 image, NVIDIA driver 580.126.09
- PyTorch 2.13.0+cu129, vLLM 0.27.1+cu129
- model revision `017b9c7af6b5689d5dd426a76e0bc077eb5ca20a`
- FP8 weights and KV cache, MTP 3 tokens, 65,536 context,
  `max_num_seqs=128`, CUDA graphs enabled

This is the same model, revision, vLLM build, MTP setting, context, prompts and
output lengths as ADR-260821e. The RunPod image exposed system NVCC 12.8 and
vLLM warned that NVCC 12.9 or newer gives the best DeepGEMM performance. This
can make the RunPod result conservative, but cannot change the c128 decision:
RunPod would need another 58% throughput merely to match Hyperstack's cost.

## Like-for-like result

Output tokens/s:

| provider / card | engine load | single | c8 | c32 | c64 | c128 | long 4k | long 32k |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Modal H100 80GB HBM3 | 410.0 s | 212.7 | 1,046.5 | 3,544.5 | 3,322.0 | 4,203.0 | 175.9 | 64.1 |
| Hyperstack H100 PCIe | 446.4 s | 151.2 | 877.3 | 2,501.9 | 2,450.9 | 2,787.5 | 122.1 | **44.4** |
| **RunPod H100 NVL** | **446.4 s** | **215.9** | **1,117.8** | **2,606.3** | **2,959.7** | **2,804.9** | **148.4** | 42.1 |

Against Hyperstack, NVL is 42.8% faster single-stream, 4.2–27.4% faster from
c8 through c64, and only 0.6% faster at c128. It is 5.2% slower on the 32k
long-prompt case. The old memory-bandwidth scaling was therefore not a usable
predictor of batched throughput.

## Price, resident ceiling, and actual bill

At measurement time Community NVL and both Community H100 PCIe/SXM returned no
available price or stock. Secure NVL returned `Low` stock at **$3.19/hour**,
and the allocated Pod reported the same rate. RunPod documents per-second
billing for compute and storage:
<https://docs.runpod.io/accounts-billing/billing>.

| workload | RunPod Secure NVL | Hyperstack PCIe | RunPod premium |
|---|---:|---:|---:|
| single | $4.104/Mtok | **$3.686/Mtok** | 11.3% |
| c32 | $0.340/Mtok | **$0.223/Mtok** | 52.5% |
| c64 | $0.299/Mtok | **$0.227/Mtok** | 31.7% |
| c128 | $0.316/Mtok | **$0.200/Mtok** | 58.0% |

Secure NVL is $2,328.70/month at 730 resident hours, about ¥369,099 at the
same ¥158.5/USD planning rate. It exceeds the ¥300k ceiling. Repricing the
measurement at the unavailable $2.59 Community rate gives $1,890.70/month,
about ¥299,676, and $0.256/Mtok at c128. Community would be 9.6% cheaper than
Hyperstack for single-stream output, but 28.2% more expensive at c128.

The account balance after termination was $9.4312066559 from the owner's $10
deposit, so the observed provisioning-through-deletion spend was about
**$0.5688**. The successful benchmark itself took 501.6 seconds; engine load
was 446.4 seconds.

## Reproduction and cleanup

The successful invocation on the Pod was:

```bash
python qwen38_27b_bench.py \
  --out ADR-260821f-qwen38-27b-runpod-h100-nvl-mtp.json \
  --hourly-usd 3.19 --provider RunPod \
  --flavor "H100 NVL 94GB Secure"
```

Pod `u9ub6r5mpr73yr` was terminated immediately after the result was copied.
A subsequent Pod list was empty and current spend was $0/hour. No network
volume was created. The API credential is not present in this ADR, the raw
result, the harness, or Git history and must be rotated because it passed
through the task conversation.
