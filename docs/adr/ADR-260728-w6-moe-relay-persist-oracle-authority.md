# ADR-260728: W6 product-shell oracle authority — moe + relay + persist

Status: accepted after join+gc (#105) product-shell cutover

## Decision

Wire three catalog-only pure cores to kotoba SSoT:

| catalog id | host | KIR |
|---|---|---|
| `:infer-moe` | `murakumo.infer.moe` | `infer_moe_core.kir.edn` |
| `:infer-relay` | `murakumo.infer.relay` | `infer_relay_core.kir.edn` |
| `:persist` | `murakumo.persist` | `persist_core.kir.edn` |

### moe (JVM)

- default `capacity-for-usable` via `capacity-default` (custom tiers stay host)
- `expert-ratio` via `expert-ratio-milli`
- `verdict` keyword via `verdict-name` (why strings host)
- `resident-bytes-estimate` via `resident-est`

### relay (JVM)

- `make-id`, `lease-expired?`, `msg-idle`/`msg-job`/`msg-settled` keywords

### persist (JVM)

- constants (authority, collections, ports, settle-ms)
- `snapshot-rkey` / `reconcile-rkey` / `repo-uri` / `repo-write-url` / `write-ok?`

### Still host

- moe: custom capacity tiers, node ranking plan fold
- relay: queue/worker map state machine
- persist: envelope maps, graph-cid hashing, curl argv assembly

## Related

- murakumo#86–#105 product-shell pattern
