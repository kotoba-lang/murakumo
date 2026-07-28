# ADR-260728: cljs dual-source for reconcile.plan + component-authority

Status: accepted after identity cljs dual-source (#128)

## Decision

Dual-source pure scalar helpers when oracle is loadable:

| host | oracle id | dual-source when ready |
|---|---|---|
| `murakumo.reconcile.plan` | `:reconcile-plan` | `desired`, `deficit`, `action-name`, `watch-sleep-ms` |
| `murakumo.component-authority` | `:component-authority` | event-version, identifier-len-ok?, place/revoke-epoch, next-sequence, event-kind, format/alg |

Host remains: eligible/observed set algebra, pick-targets sort, event map
assembly, ed25519 signing, CLI flag parse. Mirrors + `try-oracle` for cljs
KIR failures (`blank?` substring on cljs — identifier uses host-projected
blank + len into `identifier-len-ok?`).

### Related prior

- #122–#128 cljs oracle load through identity

### Still incremental

- infer/* / overlay/* / deploy.plan / report
- Delivery 5–8 shells

## Evidence

- reconcile + component-authority unit/parity green on JVM
- nbb smoke: ready? + desired/deficit/action + place-epoch/event-kind

## Related

- inventory Next: incremental cljs host rewire
