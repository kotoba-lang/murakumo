# ADR-260728: W6 token version/JWT seps pure oracle expansion

Status: accepted after overlay_peer health/via tokens pure (#177)

## Decision

Expand `kotoba/token_core.kotoba` with residual **version/default claim and
JWT segment tokens**, dual-sourced on `murakumo.token`:

| export | role |
|---|---|
| `version` / `default-ttl` | wire version + default exp offset (host dual-source) |
| `default-sub` / `default-scope` / `scope-all` | claim defaults + wildcard |
| `jwt-seg-sep` / `wire-sep` | `.` between version/payload/sig |
| `json-sub-prefix`…`json-close` | encode-claims-json fragments |
| recomposed `claim-*` / `signing-input` / `wire-token` / `scope-allows?` / `version-ok?` | from tokens |

HMAC-SHA256 and base64url codecs stay host.

## Evidence

- regenerated `token_core.kir.edn`
- token unit + parity + authority green

## Related

- ADR-260728-w6-token-oracle-authority
- murakumo#177 overlay_peer health/via tokens pure
