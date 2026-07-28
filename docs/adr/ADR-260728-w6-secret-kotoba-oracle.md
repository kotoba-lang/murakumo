# ADR-260728: W6 murakumo.secret name/policy kotoba oracle

- Status: Accepted
- Date: 2026-07-28

## Context

Secret-custody ops cutover already uses named secrets and path refs
(`murakumo.secret`). Fetch transports stay host; stable name strings and
validation policy are pure and oracle-eligible.

## Decision

Port to `kotoba/secret_core.kotoba`:

| function | notes |
|---|---|
| `*-secret-name` / `*-env` | five known secret identities |
| `env-for-secret-name` / `known-secret-name?` | known-env-secrets lookup |
| `valid-env-var-name?` | blank / `*` / `/` / `\` / space / length |
| `valid-path-ref-unix?` | absolute `/…`, no PEM / `*` / NUL |
| `classify-fetched` | missing/blank → kit tag bare names |

### Not ported

- `env-fetch` / `map-fetch` / `kagi-fetch` closures
- `System.getenv` / process env
- Windows drive-letter absolute path branch

## Evidence

- `test/murakumo/secret_kotoba_parity_test.clj`
