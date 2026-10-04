# Owned image pull pool

The image worker uses outbound HTTPS to api.murakumo.cloud and loopback ComfyUI. There is no inbound tunnel, pinned gateway node, coordinator process, or copied tunnel credential. Each owned Mac reuses its existing edge/join.env and Node/NBB runtime.

Install from a merged checkout with `kbb --backend sci scripts/install-image-worker.cljk joseph` (also zebulun, dan, benjamin). The system LaunchDaemon survives logout/reboot and restarts failures. Stop only this service with `sudo launchctl bootout system/ai.gftd.murakumo-image-worker`; ComfyUI and other jobs continue.

A worker reports actual installed checkpoints, queue occupancy and reclaimable memory. One job per worker is admitted with a fenced 90-second lease; renewal is every 20 seconds. A dead worker's job can be claimed elsewhere after expiry, at most three attempts. Generation has a 20-minute deadline per attempt; jobs expire after 45 minutes. Results carry worker, attempt and an unguessable lease token. Late results are refused. Result upload is retried without rendering again.

Admission, quotas, leases and output references use the existing Merkle-LSM head CAS. Edge instances are stateless and workers are independent, but the shared CAS head and Cloudflare storage/API remain common dependencies: this is distributed execution, not a fully decentralized metadata network. No community-provider reward or confidentiality claim follows from owned-fleet image registration.

Capacity: public GET /infer/image-jobs/capacity. Worker APIs and job input/output access require the operator credential; public adapters additionally enforce the caller's network proof. No prompt or bearer token is logged by the node worker.
