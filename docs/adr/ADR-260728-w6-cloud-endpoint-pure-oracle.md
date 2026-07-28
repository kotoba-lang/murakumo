# ADR-260728-w6-cloud-endpoint-pure-oracle

- Status: Accepted
- Date: 2026-07-28

## Context

`murakumo.cloud.plan/direct-endpoint` already dual-sourced quic/webrtc/relay
URL fragments via `cloud_plan_core`. Residual host-only strings remained for
`:webtransport` and the generic transport fallback.

## Decision

Port pure string builders into `kotoba/cloud_plan_core.kotoba`:

- `webtransport-endpoint` — `https://{host}:{http-port}/.well-known/murakumo/webtransport`
- `transport-endpoint` — `{scheme}://{host}`

Wire host via try-oracle + mirrors. Record assembly / choose-relay sort stay host.

## Evidence

- `kotoba/cloud_plan_core.kotoba` + regenerated KIR
- parity + authority call-match tests

## Related

- ADR-260728-w6-overlay-cloud-provision-oracle-authority
- ADR-260728-w6-cljs-clj-residual-dual
