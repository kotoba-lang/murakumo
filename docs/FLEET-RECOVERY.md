# Fleet recovery and Hugging Face setup

## Normal model setup

Omit the node to choose a live non-canary node with the most free disk:

```bash
bb murakumo model plan trellis-image-large
bb murakumo model setup trellis-image-large
bb murakumo model status trellis-image-large all
```

Use an explicit node only when placement is intentional:

```bash
bb murakumo model setup hunyuan3d-2.1 issachar
```

## Recover an offline node

```bash
bb murakumo revive levi
bb murakumo revive all
```

`revive` sends Wake-on-LAN through a currently reachable LAN peer. After wake,
verify Tailscale and SSH separately. A Mac may accept one password login to unlock
Remote Login after boot; do not store that password.

When MagicDNS is unavailable, use a live node as a jump host:

```bash
ssh -J issachar user@192.168.1.26
ssh -fN -L 22026:192.168.1.26:22 issachar
ssh -p 22026 user@localhost
```

## Residency check

On each fleet Mac, the expected state is:

```text
tailscaled LaunchDaemon: running
sleep:                  0
womp:                   1
powernap:               1
```

Check with:

```bash
launchctl print system/homebrew.mxcl.tailscale
pmset -g custom
tailscale status
```

TRELLIS remains CUDA-only. Hunyuan3D Shape and official six-view Paint run on the
verified `gad` ROCm worker through `hunyuan3d-run-rocm` and `hunyuan3d-paint`.
Paint uses 4096-query attention chunking and requires exclusive access to the
gfx1151 GPU; the API serializes generation jobs and allows up to 60 minutes.
