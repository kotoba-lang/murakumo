# ADR-260728: cljs dual-source for identity pure helpers

Status: accepted after config+persist cljs dual-source (#127)

## Decision

Expand incremental cljs host rewire to `murakumo.identity`:

| export family | dual-source |
|---|---|
| seed preimages | `seed-node` / `seed-p2p` / `seed-x25519` / `seed-overlay` |
| DID | `did-derive-cmd` argv, `did-from-output` |
| CID / JWT | `cid-b-prefix`, `jwt-header-json`, `jwt-payload-json`, `op-token-sig-seg` |
| constants | `graph-name-fleet` |

SHA-256 / base32 CID body / b64url remain host codecs.

### cljs host codec notes

- Prefer Node `crypto` + `Buffer` (nbb path)
- Dropped `goog.crypt` / `goog.crypt.base64` (unavailable under nbb)
- Browser SHA-256 not provided synchronously — Node-only for digest on cljs

### Related prior

- #122 cljs oracle load
- #123–#127 dash / token / tunnel / secret / connect / config / persist

### Still host / incremental

- component_authority (ed25519 + abi maps; pure epochs later)
- infer/* / overlay/* remaining pure hosts

## Evidence

- identity unit/parity/authority suites green
- nbb smoke: ready? :identity + seeds + graph-cid + op-token + graph-name

## Related

- inventory Next: incremental cljs rewire (identity/infer/overlay)
