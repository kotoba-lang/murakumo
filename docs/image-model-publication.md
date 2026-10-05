# Image model publication

The shared catalog at `https://api.murakumo.cloud/infer/image-models` drives the free image selectors on murakumo.cloud and oppai.fans. A new compatible model does not require another website deployment.

Prepare pinned weights on two compatible GPU workers first. Native Qwen Image 2.1 and ComfyUI SDXL checkpoints are supported. Other architectures require a separately qualified runtime adapter. The ComfyUI worker discovers registered checkpoint profiles and advertises only files found on its own node; existing GPU jobs are preserved.

Run from this repository:

```sh
kbb --backend sci scripts/image-model.cljk resources/image-models/qwen-image21.json --apply
# Task entrypoint:
kbb --backend sci scripts/run-task.cljk image-model resources/image-models/qwen-image21.json --apply
```

Without `--apply` the command prints a plan. With it, the workflow registers the immutable model definition, requires two ready replicas, renders two harmless still-life canaries, waits for completion, verifies their actual PNG dimensions and distinct workers on the server, and publishes to the selected sites. Inference can take several minutes. Transient network and HTTP errors are retried twice with the same immutable definition or job UUID; completed proofs wait up to three minutes for worker readiness to refresh. No node is pinned. To reuse completed jobs less than 24 hours old, add `--proof-jobs UUID1,UUID2`. If both proofs came from one worker, publication is refused; retry with a second independent proof.

The operator credential must already be present in `MURAKUMO_SERVICE_TOKEN` or `MURAKUMO_OPERATOR_TOKEN`. Never put a credential in the manifest, command arguments, or MCP tool arguments. The GPU worker and site-adapter credentials cannot register or publish models.

MCP exposes the same workflow as `murakumo.image_model_add` with `{manifest: {...}, apply: true, proof_jobs: [...]}`. The existing stdio server runs from this repository using `kbb --backend sci --classpath src:../org-anthropic-mcp/src scripts/mcp-server.cljk`. It inherits the operator environment and returns only the publication receipt. Canaries can take up to 90 minutes, plus publication readiness and request retries; the MCP runner allows up to 100 minutes and the client must permit that tool timeout. `MURAKUMO_KBB` may specify the trusted local kbb executable.

The manifest contains an opaque model id, display label, target sites (`murakumo`, `oppai`), Hugging Face repository and immutable 40-character revision, weight filenames and full SHA-256 hashes, and an execution profile. Profile keys `execution-ms` and `job-ms` match the queue contract. Reusing an id with different weights or an execution profile is refused: choose a new id. Failed qualification leaves a draft hidden from site catalogs, without publishing an unverified model.

This command automates registration, generation qualification and publication; it does not download arbitrary architectures or silently grant GPU credentials broader permissions. Deployment and installation of a new runtime remain explicit prerequisites.
