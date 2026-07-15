# ADR-260712: Liveness-first fleet operation and model provisioning

Status: Accepted — 2026-07-12

## Context

Murakumo operates Mac mini nodes through Tailscale SSH. A configured node is not
necessarily reachable: macOS can be awake while Remote Login is locked, Tailscale
can be absent from the active session, or the node can be sleeping. Selecting a
fixed canary for large Hugging Face downloads also consumes control-plane disk and
reduces recovery capacity.

Photo-to-3D additionally needs two different roles. Mac minis can host the API,
queue and model cache, while the registered TRELLIS and Hunyuan3D runtimes require
CUDA workers for inference.

## Decision

1. Model setup selects an SSH-reachable, non-canary node first and chooses the one
   with the most free home-disk space. `asher` is fallback-only unless named.
2. `fleet.edn` is the source of truth for node SSH account, LAN address and Ethernet
   MAC address.
3. Offline nodes are recovered by Wake-on-LAN relayed through a live node on the
   same LAN. SSH tunnels or ProxyJump provide access after the target sshd responds;
   they cannot power on a stopped host.
4. Fleet Macs run `tailscaled` as a system LaunchDaemon. They use `sleep 0`,
   `womp 1`, and `powernap 1` so the control plane remains reachable and recoverable.
5. `bb murakumo model setup` installs the Hugging Face CLI when needed and uses
   resumable `hf download`. Authentication tokens are never embedded in generated
   commands or committed configuration.
6. Model cache placement does not imply runtime compatibility. TRELLIS remains
   CUDA-only. Hunyuan3D Shape and official Paint are verified on `gad` using ROCm
   PyTorch and the Radeon 8060S. Mac minis remain cache/control
   nodes unless a separately validated Metal runtime is registered.

## 2026-07-13 verification amendment

On `gad` (Ryzen AI Max+ 395, Radeon 8060S/gfx1151, 48 GiB unified VRAM), ROCm
PyTorch detected the GPU, completed FP16 matrix multiplication, loaded the
Hunyuan3D-2.1 DiT/VAE, and generated a 935 KiB GLB from the official demo image.
Five-step shape generation completed in 22.4 seconds while the existing Qwen
Vulkan server remained running. The managed process entrypoint is
`~/.murakumo/bin/hunyuan3d-run-rocm`.

Official Hunyuan3D 2.1 Paint was subsequently verified on the same gfx1151 GPU.
Its memory-heavy attention runs with 4096-query chunking, peaked at about 47.8 of
51.5 GB VRAM, and produced a six-view PBR GLB in about 36 minutes. The managed
entrypoint is `~/.murakumo/bin/hunyuan3d-paint`; the generation API executes
Shape → Paint → rig/motion/morph with a single GPU job at a time.

## Consequences

- Routine setup survives individual node outages and preserves the canary.
- Recovery requires at least one live LAN peer and Wake-on-LAN-enabled hardware.
- FileVault/Remote Login lock may require one password authentication after boot;
  subsequent fleet automation uses public keys.
- Large snapshots must still pass disk-capacity checks and runtime placement is
  decided independently of cache placement.

## Verification

```bash
bb murakumo nodes
bb murakumo model plan trellis-image-large
bb murakumo model status trellis-image-large all
bb murakumo revive all
bb test
```
