# Qwen Image 2.1 native image lane

Model ID: `qwen-image-2.1-uncensored`. Source: `abenzerps/Qwen-Image-2.1-Uncensored-GGUF`, revision `6b34e59458d3eb7ba6a6f86a116aed5253dc02c3`, UC Q4_K_M. The companion text encoder is the official Qwen3-VL-8B-Instruct Q4_K_M; this combination is explicitly supported by sd.cpp's Qwen Image 2.1 documentation. The VAE is the model's own 2.1 VAE. Earlier Qwen/Wan VAEs are not interchangeable.

The owned K16 at `root@100.66.205.17` uses the pinned sd.cpp revision `3f8527a46c54ecf4cb4ed6003da8e8982283c73c`. This lane explicitly assigns every component and its weights to CPU and disables auto-fit: the resident Mishima text service keeps exclusive ownership of Vulkan. A GPU idleness snapshot would not prevent another text request arriving during an image render. This lane has no ComfyUI dependency and preserves other node services. Current fixed profile: 512×512, 25 Euler steps, CFG 1. Only text-to-image is supported by this lane; edit and alpha output are not qualified.

From a merged checkout:

```
kbb --backend sci scripts/prepare-native-image-node.cljk root@100.66.205.17
kbb --backend sci scripts/install-native-image-worker.cljk root@100.66.205.17
```

Preparation checks SHA-256 for all three artifacts, builds the pinned source with its pinned submodules, and renders a real 512px PNG in a transient systemd unit before writing `qualified.json`. `--qualify` skips download/build but still rechecks revision and all artifact hashes before rendering. Preparation refuses an active image service, invalidates the previous marker before changing or testing anything, and recreates it only after success. The marker also records the CPU backend; earlier GPU-based qualifications cannot advertise this lane.

The permanent `murakumo-native-image-worker.service` reuses `/etc/murakumo/join.env`; credentials remain on the node. Its name is `aiueos-k16-qwen-image21`. The service caps memory at 14 GiB, starts reclaiming above 12 GiB, caps swap at 2 GiB and CPU at four cores, and runs with lower priority. At least 12 GiB of available memory and the qualification marker are required before reporting readiness. The process tree is stopped together, so a service failure does not leave a renderer behind. Reinstalling an active service is refused; first stop only an idle image worker.

Jobs use the same outbound HTTPS image-job API, fenced leases, renewals, retries and immutable private image-body storage as the existing image pool. Native prompts are individual process arguments, never shell code; renderer output is suppressed in production logs. A lost lease or execution deadline terminates only the job's own process, with a bounded SIGKILL fallback, and removes its temporary output directory.

Submit with the existing owned operator credential to `POST https://api.murakumo.cloud/infer/image-jobs`, using the model ID above and the existing image-job body/consent protocol. Poll the returned job ID with the matching owner network. The Murakumo catalog records this asynchronous protocol explicitly; this is not a chat model or a synchronous OpenAI image endpoint. Public capacity is `/infer/image-jobs/capacity`.

Tests:

```
kbb --backend sci test/image_worker_test.cljk
kbb --backend sci test/image_native_test.cljk
```

The native test checks profile rejection, absence of unqualified advertisement, literal prompt arguments, artifact cleanup and cancellation of a renderer after lease loss. Image API tests additionally exercise the 25-step native profile after real compiled-bundle admission/claim.
