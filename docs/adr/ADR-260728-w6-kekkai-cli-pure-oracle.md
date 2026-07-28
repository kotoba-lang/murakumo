# ADR-260728-w6-kekkai-cli-pure-oracle

- Status: Accepted
- Date: 2026-07-28

## Context

`murakumo.kekkai.gate/cli-argv` hard-coded host strings for the kekkai.cli
subprocess. Pure string fragments belong in the oracle.

## Decision

Export `cli-bin`, `cli-alias-flag`, `cli-main-flag`, `cli-main-ns` from
`kekkai_gate_core.kotoba`. Host assembles the argv vector with ledger path and
node name (still host-provided).

## Evidence

- dual-source `cli-argv` + parity

## Related

- ADR-260728-w6-product-shell-oracle-authority
