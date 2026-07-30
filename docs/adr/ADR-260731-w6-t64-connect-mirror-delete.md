# ADR-260731: T6.4 remainder — connect deletes cljs host mirrors

- Status: accepted
- Date: 2026-07-31
- WBS: T6.4 (mirror deletion after crypto/stream #238)
- Depends: #238 crypto+stream, #237 identity, preload contract (#233)

## Decision

1. **`murakumo.connect` drops all `mirror-*` / `try-oracle` / `oracle-str-const`
   dual-source pure reimplementations.** Class/plane tokens and
   default-class/node-class/serves-plane helpers call the shipped `:connect` KIR
   on **every** platform via `oracle/require-ready!`.
2. **Host-only remains:** `load-connect`, `class-transports` map lookup, set
   intersection projection for live-plane common transport.
3. **Preload guarantee** same as prior T6.4 mirror-delete hosts.

## Non-claims

- `serves-plane?` still takes Product Value ABI `[:option :i64]` presence flags
  for http?/common? (0/1 projection at host) — residual param bool cutover is
  separate from mirror deletion
- Other dual-source hosts still keep cljs mirrors
- T8.3 production AOT; W4 recursive values

## Evidence

- connect-kotoba-parity + reconcile serve/eligible suites green
- No dual-source mirror bodies remain in `src/murakumo/connect.cljc`
