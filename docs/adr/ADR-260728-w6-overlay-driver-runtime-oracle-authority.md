# ADR-260728: W6 product-shell oracle authority — overlay driver + runtime

Status: accepted after overlay/cloud/provision (#111)

## Decision

Host-wire residual catalog-only pure cores:

| catalog | host | pure delegates |
|---|---|---|
| `:overlay-driver` | `murakumo.overlay.driver` | endpoint-kind, option-name, blank?, dial-ok-reason, command-is-dial? |
| `:overlay-runtime` | `murakumo.overlay.runtime` | default ports, known-adapter?, adapter-kind, scheme-prefix-host |

### Still host

- parse-argv loops, session/bootstrap map assembly
- adapter registry opens/status placeholders
- full URL regex (port/path); host string from oracle
- cljs host-mirrors

## Evidence

- authority + overlay driver/runtime parity + unit tests

## Related

- murakumo#99 bulk catalog; #111 overlay-keyring/peer/stream + cloud + provision
- Completes dual-source host wiring for **all** catalog pure cores
