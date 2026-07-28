# ADR: T5.2 first slice — oracle `call-record` structural host map bridge

- Status: Accepted
- Date: 2026-07-28
- WBS: T5.2

## Context

T5.1 forbids new public base-N packs and prefers record/map-shaped APIs.
Product hosts still call `oracle/call` with **positional** vectors. We need a
host bridge that accepts a structural map without inventing guest packs.

## Decision

### API (`murakumo.kotoba.oracle`)

| fn | role |
|---|---|
| `project-field` | `:string` / `:i64` / `:option-*` / `:raw` |
| `map->args` | host map + field-specs → ordered guest args |
| `call-record` | `map->args` then `call` |

### Field specs

```clojure
[[:override :string] [:home :string]]
;; or string keys for env maps:
[["MURAKUMO_KOTOBA_DIR" :string] ["HOME" :string]]
```

### Pilot wire

`murakumo.config/kotoba-dir` uses `call-record` for `kotoba-dir-from`.

### Non-claims

- Does **not** pass a native guest `[:record …]` value on the KIR wire yet  
  (positional projection only; full record ABI is a follow-up slice)
- Does not rewrite schedule `eligible?` packs (T5.3)

## Evidence

- `test/murakumo/oracle_call_record_test.clj`
- config/kotoba-dir dual-source still green via existing config tests

## Related

- kotoba-lang ADR-reliability-t51-structural-args
- WBS T5.1–T5.3
