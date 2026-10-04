# Owned image pull pool

The image worker uses outbound HTTPS to api.murakumo.cloud and loopback ComfyUI. There is no inbound tunnel, pinned gateway node, coordinator process, or copied tunnel credential. Each owned Mac reuses its existing edge/join.env and Node/NBB runtime.

Install from a merged checkout with `kbb --backend sci scripts/install-image-worker.cljk joseph` (also zebulun, dan, benjamin). The system LaunchDaemon survives logout/reboot and restarts failures. Stop only this service with `sudo launchctl bootout system/ai.gftd.murakumo-image-worker`; ComfyUI and other jobs continue.

A worker reports actual installed checkpoints, queue occupancy and reclaimable memory. One job per worker is admitted with a fenced 90-second lease; renewal is every 20 seconds. A dead worker's job can be claimed elsewhere after expiry, at most three attempts. Generation has a 20-minute deadline per attempt; jobs expire after 45 minutes. Results carry worker, attempt and an unguessable lease token. Late results are refused. Result upload is retried without rendering again.

Admission, quotas, leases and output references use the existing Merkle-LSM head CAS. Edge instances are stateless and workers are independent, but the shared CAS head and Cloudflare storage/API remain common dependencies: this is distributed execution, not a fully decentralized metadata network. No community-provider reward or confidentiality claim follows from owned-fleet image registration.

The image pool currently supports installation on the owned Macs `zebulun`, `joseph`, `dan`, `benjamin` and the existing reserve `asher`. Installed checkpoint files are capabilities, not proof of available GPU capacity. A worker claims only with an empty local ComfyUI queue and at least 4 GiB of reclaimable memory; it releases idle ComfyUI models after an acknowledged result. Existing running or pending work prevents cache release. Only the independent heartbeat timer publishes readiness, so idle polling does not repeatedly write the shared CAS head.

Image bodies are stored separately as immutable private R2 objects under `image-artifacts/v1/`; Merkle metadata carries their references. This avoids encoding large images inside metadata shards. The public adapter receives an image only through an authenticated job lookup with the matching network owner.

Capacity: public GET /infer/image-jobs/capacity. Worker APIs and job input/output access require the operator credential; public adapters additionally enforce the caller's network proof. No prompt or bearer token is logged by the node worker.
