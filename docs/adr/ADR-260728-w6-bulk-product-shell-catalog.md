# ADR-260728: W6 bulk product-shell catalog (all kotoba cores)

Status: accepted after engine (#96) product-shell cutover

## Decision

Ship **precompiled KIR artifacts for every** `kotoba/*_core.kotoba` under
`resources/murakumo/oracle/`, register them all in `murakumo.kotoba.oracle`
catalog, and discover artifacts via `murakumo.kotoba-oracle-gen`.

### Fully host-wired this slice

| catalog id | host |
|---|---|
| `:secret` | `murakumo.secret` name/env constants + validators |
| `:overlay-crypto` | `murakumo.overlay.crypto` packaging constants/gates |

### Catalog-only (artifact shipped; host wiring incremental)

cloud-plan, component-authority, config, connect, deploy-plan, fleet-inventory,
identity, infer-credits/gc/join/moe/rebalance/relay, overlay-driver/keyring/peer/runtime/stream,
persist, provision-plan, reconcile-plan, tunnel — plus prior fully-wired ids
(kekkai/token/report/plan/dash/schedule/task/engine).

### Regeneration

```bash
clojure -M:test -e '(require (quote murakumo.kotoba-oracle-gen))
                    (run! println (murakumo.kotoba-oracle-gen/regenerate-all!))'
```

`discover-artifacts` walks `kotoba/*_core.kotoba` so new cores auto-join the gen list.

## Evidence

- all 32 `*.kir.edn` present under `resources/murakumo/oracle/`
- secret + overlay-crypto authority tests
- existing secret/crypto host tests

## Related

- inventory Next: bulk catalog gen for remaining cores
- murakumo#86–#96 product-shell pattern
