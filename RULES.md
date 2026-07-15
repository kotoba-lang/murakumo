# Murakumo fleet operation rules

These rules govern operator actions against `fleet.edn`.

1. Prefer observed liveness over inventory order. A node must pass BatchMode SSH
   before receiving downloads, deployment, or model setup work.
2. Preserve the canary. Do not select a node labeled `:role "canary"` for routine
   large downloads while another eligible node is live.
3. Check free disk before downloading. Require the recorded HF snapshot size plus
   at least 2 GiB headroom. Hugging Face downloads go under
   `~/.murakumo/models/<model-id>` unless an explicit cache directory is supplied.
4. Never place HF tokens, passwords, auth keys, or operator seeds in `fleet.edn`,
   command output, plans, ADRs, or Git. Gated repositories must be authenticated on
   the destination node beforehand.
5. Recover safely: Wake-on-LAN from a live LAN peer, then SSH/ProxyJump. Do not treat
   a successfully created tunnel as proof that the destination is running.
6. Keep Tailscale resident as a system LaunchDaemon and keep fleet Macs configured
   with `sleep 0`, `womp 1`, and `powernap 1`. Verify observed state after changes.
7. Use public-key SSH for automation. Password authentication is only for unlocking
   a booted Mac; never persist the password.
8. Do not schedule CUDA-only models on Apple Silicon. Caching weights does not prove
   execution. Hunyuan3D Shape and official Paint are explicit exceptions verified on `gad`
   (ROCm/Radeon 8060S); its paint pipeline remains unverified.
9. Run `bb test` and `git diff --check` after CLI, inventory, or policy changes.
10. Preserve unrelated worktree changes, including generated inference plans.
