# ADR-260728: Optional cljs/nbb product-shell oracle load

Status: accepted after Product Value ABI trail (#112–#121)

## Decision

Extend `murakumo.kotoba.oracle` so precompiled KIR can load outside the JVM
classpath:

| mechanism | role |
|---|---|
| `register-kir!` | inject pre-parsed KIR (tests / bundlers / preloads) |
| `set-resource-loader!` | custom `(fn [path] → string)` |
| nbb/node default | `fs.readFileSync` of `resources/<catalog-path>` from `process.cwd()` |
| `clear-cache!` | drop cached docs |

`ready?` remains the host gate: pure helpers may dual-source when true and
fall back to cljc mirrors when false.

### First dual-source cljs hosts

- `murakumo.task.plan/failed?` — oracle when ready, else mirror
- `murakumo.fleet.inventory` port/url/selector/offline — oracle when ready

### nbb packaging

- `nbb.edn` `:paths` includes `resources`
- `nbb.edn` `:deps` pins `kotoba-kir` (same SHA as `deps.edn`)

### Still host / unchanged

- Full cljs rewire of all 32 catalog hosts (incremental)
- Browser/webpack resource packaging (use `register-kir!` / custom loader)
- schedule `eligible?` bit-pack residual
- Some KIR string ops (`string-from-i64` in health-url) may fall back on cljs;
  host mirrors remain the safety net via try/catch

## Evidence

- JVM authority + task/fleet unit tests green
- `register-kir!` / `set-resource-loader!` unit tests
- nbb smoke: `oracle/ready? :task-plan` from repo root

## Related

- inventory Next: cljs oracle load optional
- murakumo product-shell dual-source #86–#113
