# Qwen3.8-27B RunPod Serverless benchmark

This queue worker measures the same pinned vLLM 0.27.1, FP8 KV, MTP-3,
65,536-context plan used by the Modal, Hyperstack and RunPod Pod runs.

The image deliberately does not bake in the 28.75 GiB checkpoint. It first
looks for RunPod's cached-model mount and otherwise downloads the pinned public
Hugging Face revision. The result records which source was actually used.

The endpoint must use one H100-class 80 GB GPU, zero active workers, one Flex
worker maximum, FlashBoot enabled, CUDA 13.0, and an execution timeout of at
least one hour. Send `{"input":{"plan":"full"}}` to run the complete plan or
`{"input":{"plan":"single"}}` for a warm single-stream probe.
