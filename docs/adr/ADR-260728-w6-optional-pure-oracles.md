# ADR-260728: W6 pure-planner oracle — optional persist/moe/rebalance/relay

Status: accepted optional cutover slice after medium-named-candidates-complete

## Decision

Port remaining portable-pure scalar/string cores:

| artifact | cljc | notes |
|---|---|---|
| `persist_core.kotoba` | `murakumo.persist` | constants, rkey, repo-uri/url, write-ok? |
| `infer_moe_core.kotoba` | `murakumo.infer.moe` | default capacity tiers, ratio milli, verdict |
| `infer_rebalance_core.kotoba` | `murakumo.infer.rebalance` | usable-gb, pool-for-class |
| `infer_relay_core.kotoba` | `murakumo.infer.relay` | make-id, lease-expired?, msg names |

### Not ported

- persist envelope maps / graph-cid hashing
- moe plan ranking + custom capacity tiers
- rebalance largest-remainder / moves
- relay queue/worker state machine

## Evidence

- `test/murakumo/persist_kotoba_parity_test.clj`
- `test/murakumo/infer_moe_kotoba_parity_test.clj`
- `test/murakumo/infer_rebalance_kotoba_parity_test.clj`
- `test/murakumo/infer_relay_kotoba_parity_test.clj`

## Related

- murakumo#43–#47 medium pure-planner oracles
