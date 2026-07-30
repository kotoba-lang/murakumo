# ADR-260731: valid-env-var-name? / valid-path-ref-unix? are profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#212 (blank?/ws? bool), compiler#451

## Decision

After #212 converted blank?/ws? to :bool, finish the secret path/env gates:

1. `valid-env-var-name?` returns `:bool` (was 0/1 `:i64`).
2. `valid-path-ref-unix?` returns `:bool`.
3. Host uses `oracle/bool->host`.

## Evidence

- Live KIR regenerated for `secret_core.kir.edn`
- secret parity + authority green

## Follow-up

- `known-secret-name?` and other name-match codes may stay `:i64`
- Ranking helpers remain `:i64`
