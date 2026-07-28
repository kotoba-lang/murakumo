# ADR-260728: overlay.cert residual ops config inject

Status: accepted after ops-config-inject (#137) + kekkai-config-inject (#146)

## Decision

Route remaining **overlay.cert** exact-name config getenv through `murakumo.config`
inject helpers (no bare `System/getenv "MURAKUMO_KAGI_DIR"` in resolve path):

| helper | env | role |
|---|---|---|
| `config/kagi-dir` | `MURAKUMO_KAGI_DIR` | legacy material store dir override |

`murakumo.overlay.cert/resolve-store-dir` still prefers `:store-dir` then
`:path-ref`/`:root-dirs` (scoped-fs). The final fallback uses
`config/kagi-dir` with inject `getenv` (fn or map); process default remains
exact-name only via config 0-arity / default inject. `*store-opts* :getenv`
is the test/host inject surface.

### Secrets unchanged

Named secret fetch remains `murakumo.secret`. Cert PEM material is local
store content, not env dump.

## Evidence

- config unit inject tests for `kagi-dir` / `ops-config-keys`
- overlay_cert resolve-store-dir inject + default-store-dir tests

## Related

- ADR-260728-w6-ops-config-inject
- ADR-260728-w6-kekkai-config-inject
- handoff Delivery residual ops shells
