# ADR-260731: T5.2 expand oracle call-record on product map boundaries

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#155 (call-record pilot), T5.3 record schemas
- WBS: T5.2 remainder (positional projection expansion; not full guest record ABI rewrite)

## Decision

1. **`project-field` gains `:bool`** for profile-5 guest bool params in
   structural maps.
2. **Product hosts that naturally take maps / composite option pairs use
   `oracle/call-record` instead of hand-built arity vectors:**
   - `infer.plan/usable-bytes` — node resource map
   - `config/resolve-local-bin`, `kotoba-bin`, `resolve-wit-dir` — path + pin flags
   - `fleet.inventory/node-port` — node/fleet optional ports
   - `provision.plan/node-p2p-port` — node/fleet optional p2p ports
3. **`config/kotoba-dir`** remains the original env-map pilot.

## Non-claims

- Does **not** replace every positional `oracle/call` — only map-shaped or
  composite option boundaries where the host already has structured data.
- Does **not** change guest export signatures (still scalar params; projection
  only).
- Native guest `oracle/record` wire (schedule/task eligibility, etc.) stays the
  T5.3 path; this ADR expands the positional `call-record` bridge.

## Evidence

- `oracle-call-record-test` expanded; config/plan/inventory/provision suites green
