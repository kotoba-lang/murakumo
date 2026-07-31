# ADR-260731: T5.2 call-record expand wave 5

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#264
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` to more composite host boundaries:

| Host | Export |
|------|--------|
| `identity` | `seed-node`, `seed-p2p`, `seed-x25519`, `seed-overlay`, `did-derive-cmd`, `did-from-output` |
| `deploy.plan` | `join-path`, `pin-wit-dest`, `version-bin-path`, `manifest-dir`, `app-manifest-path`, `publish-selector` |
| `secret` | `classify-fetched`, `reply-is-value?`, `secret-error-code`, `secret-error-message`, `valid-env-var-name?`, `valid-path-ref-unix?` |

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 nested kit EDN codec remains W4-gated.
- Host crypto (SHA-256) and env/kagi fetch stay host.

## Evidence

- `oracle-call-record-test` + identity/deploy/secret suites green
