# ADR-260728: W6 kekkai denial/status tokens pure oracle expansion

Status: accepted after secret reply tokens pure (#182)

## Decision

Expand `kotoba/kekkai_gate_core.kotoba` with residual **status / denial-line /
dir / CLI tokens**, dual-sourced on `murakumo.kekkai.gate`:

| export | role |
|---|---|
| `status-authorized` / `status-unknown` | ledger status tokens |
| `denial-prefix` / `denial-mid` / `denial-suffix` | denial-line fragments |
| `kekkai-dir-suffix` | default checkout path under HOME |
| `cli-bin` / `cli-alias-flag` / `cli-main-flag` / `cli-main-ns` | host dual-source (was o-call only) |

`authorized?` / `parse-status-out` / `denial-line-of` / `default-kekkai-dir-under`
recompose from those tokens. partition-nodes reduce stays host.

## Evidence

- regenerated `kekkai_gate_core.kir.edn`
- kekkai parity + authority green

## Related

- ADR-260728-w6-kekkai-gate-kotoba-oracle
- murakumo#182 secret reply tokens pure
