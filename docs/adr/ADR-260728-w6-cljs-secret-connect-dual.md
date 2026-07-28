# ADR-260728: cljs dual-source for secret pure + connect pure

Status: accepted after tunnel cljs dual-source (#125)

## Decision

Expand incremental cljs host rewire:

| host | oracle id | dual-source when ready |
|---|---|---|
| `murakumo.secret` | `:secret` | name/env constants, `valid-env-var-name?`, `valid-path-ref?` |
| `murakumo.connect` | `:connect` | `default-class`, `node-class`, `serves-plane?` |

Env/map/kagi fetch and Windows path absolute stay host. Set algebra for
class-transports intersection remains host-projected into options.

Load-time secret name/env constants use oracle when ready at ns load (same
pattern as kekkai `default-ledger-path`); mirrors are fallback.

### Related prior

- #122 cljs oracle load
- #123–#125 dash / token+kekkai / tunnel dual-source

### Still host / incremental

- report.clj is JVM-only (bb/CLI shell; not nbb path)
- remaining cljc pure hosts (infer/*, overlay/*, config, identity, …)

## Evidence

- secret + connect unit/parity/authority suites green
- nbb smoke: ready? :secret/:connect + constants + serves-reach?

## Related

- inventory Next: incremental cljs host rewire (secret/connect/report)
