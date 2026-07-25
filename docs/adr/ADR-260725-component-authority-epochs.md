# ADR-260725: Murakumo owns Component placement epochs

Status: accepted

Murakumo owns placement and revocation; Aiueos owns policy decisions and lease
contents; Kototama enforces those leases at every provider call. Placement
telemetry is not authority.

Every Component CID has a monotonically increasing epoch. Murakumo emits an
exact, versioned `placed` or `revoked` event for each control-plane transition.
A revocation advances the epoch even if no placement is currently visible, so
a delayed or partitioned host cannot retain authority.

Kototama admits a lease at the current epoch and re-reads its live epoch source
at each named WIT provider invocation. A different, missing, invalid, or
unavailable epoch rejects the invocation. Runtime hosts never receive ambient
WASI authority.

Event delivery is an injected boundary. Production transports must preserve
per-Component order, reject malformed events, authenticate their Murakumo
issuer, and durably retry failed publication. The pure state machine remains
independent of QUIC, Datom, or local delivery.
