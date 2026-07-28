# ADR-260728: W6 product-shell oracle authority — moe + rebalance + relay

Status: accepted after join/gc (#105)

## Decision

Host-wire catalog ids:

| catalog | host | pure delegates |
|---|---|---|
| `:infer-moe` | `murakumo.infer.moe` | capacity-default, expert-ratio-milli, verdict-name, resident-est |
| `:infer-rebalance` | `murakumo.infer.rebalance` | shard-ceiling-gb, usable-gb, largest-remainder-3 seats unpack |
| `:infer-relay` | `murakumo.infer.relay` | make-id, lease-expired?, msg-idle/job/settled |

### Still host

- custom moe capacity tiers, plan ranking
- rebalance pool assignment / moves / demand-from-runs fold
- relay queue/worker map state machine
- cljs host-mirrors

## Evidence

- authority + moe/rebalance/relay parity + unit tests

## Related

- murakumo#99 bulk catalog; incremental host wiring trail
