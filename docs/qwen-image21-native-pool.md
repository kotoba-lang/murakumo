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

Jobs use the same outbound HTTPS image-job API, 90-second fenced leases, renewals, retries and immutable private image-body storage as the existing image pool. CPU generation has a model-specific 45-minute execution deadline and 90-minute job lifetime; the existing SDXL profiles retain their 20/45-minute limits. Native prompts are individual process arguments, never shell code; renderer output is suppressed in production logs. A lost lease or execution deadline terminates only the job's own process, with a bounded SIGKILL fallback, and removes its temporary output directory.

Submit with the existing owned operator credential to `POST https://api.murakumo.cloud/infer/image-jobs`, using the model ID above and the existing image-job body/consent protocol. Poll the returned job ID with the matching owner network. The Murakumo catalog records this asynchronous protocol explicitly; this is not a chat model or a synchronous OpenAI image endpoint. Public capacity is `/infer/image-jobs/capacity`.

Tests:

```
kbb --backend sci test/image_worker_test.cljk
kbb --backend sci test/image_native_test.cljk
```

The native test checks profile rejection, absence of unqualified advertisement, literal prompt arguments, artifact cleanup and cancellation of a renderer after lease loss. Image API tests additionally exercise the 25-step native profile after real compiled-bundle admission/claim.

## Metal replicas

The Metal profile assigns `diffusion=MTL0,te=cpu,vae=cpu` explicitly for both computation and parameters, with auto-fit disabled. MTL0 is the device reported by the pinned sd.cpp build on Apple M4; a missing GPU fails qualification rather than advertising a CPU fallback. The same three checked model artifacts and 512x512/25-step profile are retained.

`prepare-metal-image-node.cljk` permits only `jacob@100.117.208.83` and `junkawasaki@100.86.235.122`. It builds the fixed runtime with `SD_METAL=ON`, checks all artifact hashes and the device inventory, invalidates old proof, and renders an actual image before writing a `metal` marker. The qualification supervisor bounds the entire process group. `--qualify` repeats checks and rendering without downloading/building. These nodes have no resident llama-server GPU service; pre-existing Ollama/Comfy services are preserved.

`install-metal-image-worker.cljk HOST PRIVATE_CREDENTIAL_JSON` enrolls `jacob-qwen-image21-metal` or `25mbair-qwen-image21-metal`. The credential is the API's new `MURAKUMO_IMAGE_GPU_WORKER_TOKEN`, restricted to these image-worker identities and the Qwen image model. It cannot submit jobs, read image results, change the model catalog, or administer other services. The file must be private; it is copied only after qualification and is never printed. Node-local nbb 1.5.212 and a per-user launch agent restart a failed worker. The launch agent kills remaining process-group members; graceful shutdown additionally marks the current renderer cancelled and stops new polling.

At least 10GiB of available memory (macOS memory-pressure report) is required for admission. Ollama resident models, a llama-server process, and Comfy pending/running jobs exclude GPU admission. These signals are rechecked before rendering and during execution; unexpected external occupation, unavailable occupancy observations, or less than 2GiB headroom cancel only our renderer. The common 90s fenced lease/retry logic then allows another ready replica to take over. This is coexistence protection, not a mechanism that controls unrelated local applications. Mac sleep/offline observations expire after45s and cannot remain advertised as ready.

Migration disables CPU admission first by moving its marker aside, lets any existing render finish, then stops only the owned native CPU worker. Model files and CPU qualification evidence remain available for an explicit recovery. Normal production work is then handled by the two qualified Metal replicas. Acceptance requires independent GPU images, parallel API jobs on both workers, real interrupted-job handoff, image retrieval, and post-job readiness.

GPU occupancy and memory observations run asynchronously; memory samples are shared for three seconds and observation timeouts allow five seconds under GPU load. This prevents synchronous host probes from blocking fenced-lease renewals. A bounded private stderr tail records the reason for a cancelled render without logging prompts.

A lost result-upload reply is resolved through `POST /infer/image-jobs/:id/receipt`. Only the exact worker/attempt/lease fence can confirm an already committed result; this endpoint returns metadata only. A pending receipt does not authorize stale work or extend a lease. Run `kbb --backend sci test/image_worker_receipt_test.cljk` to check committed replies, expired fences and bounded pending-upload retries.

The launch script holds a process-scoped `caffeinate -s -w $$` assertion while its worker lives. It prevents system sleep on AC power without changing host power preferences or holding a laptop awake on battery. The worker PID remains the Node process; the helper exits with that process and is included in launch-agent process-group cleanup. Reboots still require the existing user session to launch this per-user agent.
