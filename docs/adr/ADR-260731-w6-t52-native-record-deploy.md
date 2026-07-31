# ADR-260731: T5.2 native guest record wire — deploy-plan

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through provision-plan
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs on `deploy_plan_core` into single guest records:

| Export | Schema | Fields |
|--------|--------|--------|
| `join-path` | `:deploy/join-path` | a, b |
| `app-manifest-path` | `:deploy/app-manifest` | manifest-dir, manifest |
| `component-build-cmd` | `:deploy/component-build` | kotoba, src-path, wit, wasm |
| `app-deploy-cmd` | `:deploy/app-deploy` | kotoba, manifest, wit, port |

Host builds `oracle/record` + `call-record` `:raw` for join-path / app-manifest-path.
`component-build-cmd` / `app-deploy-cmd` remain pure oracle exports (space-joined
string form of argv); host argv vectors stay host-assembled.

## Non-claims

- Single-arg residual (manifest-dir, pin-wit-dest, localhost-url, probes) stay scalar.
- Internal multi-arg scanners (`last-slash-index`, digit parsers) stay multi-arg.
- Vector argv assembly remains host.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `deploy_plan_core` only
- Focused deploy parity + authority green
