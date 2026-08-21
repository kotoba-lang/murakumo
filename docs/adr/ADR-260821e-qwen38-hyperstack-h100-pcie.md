# ADR-260821e: Hyperstack H100 PCIe spot is slower than Modal H100, and cheaper per token

- Status: accepted
- Date: 2026-08-21
- Continues: ADR-260821b
- Raw result: `data/ADR-260821e-qwen38-27b-hyperstack-h100-mtp.json`
- Reproduce with: `tools/hyperstack-bench/qwen38_27b_bench.py`

## Decision

Hyperstack's `n3-H100x1-spot` is now a measured resident-serving option for
Qwen3.8-27B-FP8. It clears the ¥300k/month ceiling, completes the full 64k/MTP
workload, and costs less per generated token than Modal despite lower
throughput. Use it when the priority is the lowest resident price. Keep Modal
for burst because Hyperstack does not scale to zero.

This removes the load-bearing H100 PCIe estimate. RunPod H100 NVL remains an
estimate and is now the only vendor measurement that could still change the
resident vendor choice.

## Measured hardware and configuration

- Hyperstack `n3-H100x1-spot`, CANADA-1
- physical `NVIDIA H100 PCIe`, 79.19 GiB
- 28 vCPU, 180 GiB RAM, 100 GB root plus 750 GB ephemeral disk
- Ubuntu 24.04, NVIDIA driver 570.195.03
- CUDA toolkit 12.9, PyTorch 2.13.0+cu129, vLLM 0.27.1+cu129
- model revision `017b9c7af6b5689d5dd426a76e0bc077eb5ca20a`
- FP8 weights and KV cache, MTP 3 tokens, 65,536 context,
  `max_num_seqs=128`, CUDA graphs enabled

## Like-for-like result

The Modal row is the right-sized physical H100 result from ADR-260821c. Both
rows use the same model, vLLM release, MTP, context, sequence ceiling, prompts,
and output lengths. They differ in physical H100 form factor and CUDA build.

| provider / card | engine load | single | c8 | c32 | c64 | c128 | long 4k | long 32k |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Modal H100 80GB HBM3 | 410.0 s | 212.7 | 1,046.5 | 3,544.5 | 3,322.0 | 4,203.0 | 175.9 | 64.1 |
| **Hyperstack H100 PCIe** | **446.4 s** | **151.2** | **877.3** | **2,501.9** | **2,450.9** | **2,787.5** | **122.1** | **44.4** |

The PCIe card delivers 71.1% of Modal single-stream throughput and 66.3% at
concurrency 128. Long-prompt throughput is 69.3–69.4%. The prior blanket
`×0.60` PCIe estimate was therefore conservative for this card: it
under-predicted single-stream by 18.5% and concurrency 128 by 10.5%.

## Price and the actual bill

Hyperstack's pricebook returned $2.00/GPU-hour, $0 for the bundled vCPU, RAM,
and local storage, plus $0.00672043/hour for the public IP: **$2.00672043/hour
all-in**. The billing API recorded 20 billable minutes and **$0.66890681** for
the complete provisioning, environment setup and failed initialization
attempts, successful run, and deletion. Credit moved from $5.00 to
$4.33109319.

| workload | Hyperstack $/Mtok | Modal $/Mtok | Hyperstack saving |
|---|---:|---:|---:|
| single | **$3.686** | $5.448 | 32.3% |
| c32 | **$0.223** | $0.327 | 31.8% |
| c64 | **$0.227** | $0.349 | 35.0% |
| c128 | **$0.200** | $0.276 | 27.5% |

The old Hyperstack c128 estimate was $0.216/Mtok. The measured value is 7.4%
better. At 730 resident hours the current all-in rate is $1,464.91/month; the
yen table in ADR-260821 used the GPU-only $2/hour rate and remains within its
rounding precision.

Hyperstack documents that billing begins at `ACTIVE`, and its pricebook API is
the source of the rates above:
<https://docs.hyperstack.cloud/docs/api-reference/getting-started-api/create-virtual-machine/>,
<https://docs.hyperstack.cloud/docs/api-reference/get-pricebook/>.

## Reproduction traps found on the real VM

The CUDA 12.8 Hyperstack image is usable, but `pip install vllm==0.27.1`
selects the default CUDA 13 vLLM binary, which fails with a missing
`libcudart.so.13`. Install the official cu129 wheel, use CUDA 12.9 `nvcc`, and
install both `python3-dev` and `ninja-build`. Omitting the last two fails during
Triton or FlashInfer JIT before the engine starts. The successful invocation
was:

```bash
python qwen38_27b_bench.py \
  --out ADR-260821e-qwen38-27b-hyperstack-h100-mtp.json \
  --hourly-usd 2.00672043 --flavor n3-H100x1-spot
```

The successful 446.4-second load reused the checkpoint downloaded by the first
attempt, but rebuilt the CUDA 12.9 graphs and DeepGEMM warmup. It is comparable
to the volume-backed Modal load, not a bare-machine provisioning time.

## Cleanup and remaining gap

VM 996718 was deleted immediately after the result was copied; a subsequent
VM list returned no instance. The temporary Hyperstack SSH keypair and local
private/public key files were also deleted. No credential appears in this ADR,
the harness, the result JSON, or Git history.

RunPod H100 PCIe/NVL remains unmeasured. The NVL estimate is the only open row
claiming better cost per token than this measured Hyperstack result under the
¥300k ceiling, so one RunPod NVL run is the next experiment if that account is
made available.
