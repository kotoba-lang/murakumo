# ADR-260728: cljs dual-source for token pure + kekkai.gate

Status: accepted after cljs oracle load (#122) and dash.state dual-source (#123)

## Decision

Expand incremental cljs host rewire to high-traffic pure helpers:

| host | oracle id | dual-source when ready |
|---|---|---|
| `murakumo.token` | `:token` | claims/encode/signing/wire/version/parts/expired/CT-eq/scope |
| `murakumo.kekkai.gate` | `:kekkai-gate` | ledger path, dir, parse-status, authorized?, denial-line |

HMAC/b64 and subprocess shells remain host. Mirrors stay fallback when
`oracle/ready?` is false.

### Related prior

- #122 cljs oracle load + task.failed? + fleet.inventory
- #123 dash.state pure helpers dual-source

### Still host / incremental

- secret name constants (def-at-load; later delay/fn form)
- connect / remaining cljc pure hosts
- full ops.cljs shell rewrite

## Evidence

- token + kekkai unit/authority suites green on JVM
- nbb smoke: ready? :token/:kekkai-gate + claims/wire/parse-status/authorized?

## Related

- inventory Next: incremental cljs host rewire
