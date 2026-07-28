# ADR-260728: cljs dual-source for config + persist pure helpers

Status: accepted after secret/connect cljs dual-source (#126)

## Decision

Dual-source path/persist pure helpers when oracle is loadable:

| host | oracle id | dual-source when ready |
|---|---|---|
| `murakumo.config` | `:config` | path constants, kotoba-dir, pinned/release/wit/bin resolve, peers/launchd |
| `murakumo.persist` | `:persist` | authority/collections/ports, repo-uri, rkeys, write-url, write-ok? |

EDN I/O, env folds, filesystem probes, envelope maps, and graph-cid stay host.
Mirrors + `try-oracle` remain for cljs KIR failures (persist rkey/url use
i64-str / substring).

### Related prior

- #122–#126 cljs oracle load + dash/token/kekkai/tunnel/secret/connect

### Still incremental

- identity / infer/* / overlay/* / report / reconcile / component-authority
- Delivery 5–8 shells

## Evidence

- config + persist unit/parity green on JVM
- nbb smoke: ready? :config/:persist + path resolve + write-ok? / repo-uri

## Related

- inventory Next: incremental cljs host rewire
