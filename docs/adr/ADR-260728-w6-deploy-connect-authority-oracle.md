# ADR-260728: W6 product-shell oracle authority — deploy + connect + component-authority

Status: accepted after persist (#109)

## Decision

Host-wire catalog ids:

| catalog | host | pure delegates |
|---|---|---|
| `:deploy-plan` | `murakumo.deploy.plan` | defaults/ports, manifest-dir, app-manifest-path, publish-selector, localhost-url, command-output |
| `:connect` | `murakumo.connect` | default/node class names, serves-plane? |
| `:component-authority` | `murakumo.component-authority` | event-version, identifier-len-ok?, place/revoke-epoch, next-sequence, event-kind, format/alg |

### Still host

- regex extract, argv vectors, distribution folds
- set intersection projection for live transports
- event map assembly + ed25519 signing
- cljs host-mirrors

## Evidence

- authority + deploy/connect/component-authority parity + unit tests

## Related

- murakumo#99 bulk catalog; incremental host wiring trail
