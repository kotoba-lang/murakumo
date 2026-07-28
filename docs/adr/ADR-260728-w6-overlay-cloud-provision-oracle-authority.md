# ADR-260728: W6 product-shell oracle authority — overlay + cloud + provision

Status: accepted after deploy/connect/cauth (#110)

## Decision

Host-wire remaining catalog-only pure cores:

| catalog | host | pure delegates |
|---|---|---|
| `:overlay-keyring` | `murakumo.overlay.keyring` | rotation/epoch, hash preimages |
| `:overlay-peer` | `murakumo.overlay.peer` | choose-via, health/via names |
| `:overlay-stream` | `murakumo.overlay.stream` | window, advance-seq, ack-accepted? |
| `:cloud-plan` | `murakumo.cloud.plan` | defaults, region, score, endpoints, id preimages |
| `:provision-plan` | `murakumo.provision.plan` | constants, p2p-port, multiaddr, mesh cmds |

### Still host

- SHA-256, catalog/remember folds, stream-id hashing
- cloud plan record assembly, choose-relay sort
- SSH/launchd host commands beyond pure strings
- overlay driver/runtime (larger argv loops) — still catalog-only artifacts

## Evidence

- authority + overlay/cloud/provision parity + unit tests

## Related

- murakumo#99 bulk catalog; completes primary pure-host dual-source trail
