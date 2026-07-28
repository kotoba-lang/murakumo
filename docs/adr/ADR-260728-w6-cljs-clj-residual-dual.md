# ADR-260728: cljs dual-source for residual JVM-only pure shells

Status: accepted after portable `.cljc` dual-source trail (#122–#133)

## Decision

Convert the last JVM-only (`.clj`) product-shell hosts that already had kotoba
oracle authority into dual-source `.cljc` so pure helpers work under cljs/nbb
with host mirrors as fallback:

| host | oracle id | dual-source pure surface |
|---|---|---|
| `murakumo.report` | `:report-core` | headers/rows, progress lines, help, reconcile builders |
| `murakumo.provision.plan` | `:provision-plan` | constants, seed gate, p2p port, multiaddr, mesh cmds |
| `murakumo.cloud.plan` | `:cloud-plan` | defaults, region/score, id preimages, quic/webrtc/relay endpoints |
| `murakumo.overlay.crypto` | `:overlay-crypto` | packaging constants + sealed-map gates |

### Still host

| concern | why host |
|---|---|
| AES-GCM seal/open (`overlay.crypto`) | JVM Cipher / SecureRandom / Base64 |
| cloud CLI format lines / argv / policy folds | presentation + collection reduce |
| provision plist/rsync/launchd folds | host shell command assembly |
| report CSV joins + reconcile mapcat | list folds outside guest ABI |

### Pattern

Same as #122–#133: `oracle-ready?` + `try-oracle` + `oracle-*-const` + private
`mirror-*` helpers. Pre-oracle host implementations restored as mirrors.

### Related prior

- #133 residual infer dual-source (last portable `.cljc` pure host)
- #111 overlay + cloud + provision oracle authority (JVM-only at the time)
- #89 report oracle authority (JVM-only at the time)
- #99 bulk catalog + crypto packaging authority

## Evidence

- JVM: report / provision / cloud / crypto unit + kotoba parity + authority suites green
- nbb smoke: all four oracles `ready?`; multiaddr / nodes-header / region / strip-b64-pad match

## Consequences

- No remaining oracle-wired pure product-shell under `src/` that is `.clj`-only
- AES seal/open remains `#?(:clj …)` inside `overlay.crypto`
- Delivery 5–8 shells / residual PVA / network·secret caps stay follow-up
