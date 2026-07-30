# ADR-260731: T6.4 remainder — fleetwide try-oracle JVM requires shipped KIR

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4
- Depends: #223 kekkai, #225 crypto/stream/token

## Decision

Apply the same **JVM require / cljs mirror-fallback** contract to the remaining
dual-source product-shell hosts that shared the stock `try-oracle` /
`oracle-str-const` / `oracle-i64-const` helpers:

- all `murakumo.infer.*` shells still on dual-source
- fleet.inventory, connect, secret, report, provision, deploy, cloud, config,
  dash.state, identity, component-authority, task.plan, reconcile.plan
- overlay.driver / runtime / peer / keyring
- tunnel, persist

On **:clj**, `try-oracle` throws if the oracle is not ready (T6.2 shipped KIR
is on the prod classpath). On **:cljs**, mirrors remain fail-closed fallback.

Hosts already converted with bespoke `o` helpers (kekkai, crypto, stream, token)
are unchanged by this mechanical pass.

## Non-claims

- Load-time `def` constants ad-hoc try/if: **closed** by
  `ADR-260731-w6-t64-oracle-required-loadtime` (follow-up).
- Wholesale deletion of cljs `mirror-*` bodies is still open.

## Evidence

Focused multi-ns parity suite green after the helper rewrite.
