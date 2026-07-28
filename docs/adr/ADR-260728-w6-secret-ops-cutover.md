# ADR-260728: W6 secret-custody ops cutover (named fetch)

- Status: Accepted
- Date: 2026-07-28

## Context

provider#26 / ADR 0145 landed `provider.secret` (id 21) get-only allowlist;
provider#27 / ADR 0146 landed `fn-fetch` + `keychain-fetch`. murakumo
`cmd-token` still called `System/getenv` directly for
`MURAKUMO_TOKEN_SECRET`.

## Decision

Introduce `murakumo.secret` with kit-compatible reply shape and wire
`cmd-token` through `resolve-token-secret` (named `murakumo-token` → exact
env var). No ambient env enumeration. Hosts may inject provider
transports later without changing the CLI surface.

## Consequences

- Ops path no longer special-cases raw getenv at the command layer.
- Full gap close still needs broader murakumo/cloudflare call-site audit
  and optional real provider dep for kbb.
