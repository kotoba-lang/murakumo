# Recovery wait failure

The standalone recovery watch and predecessor-wait loops previously ignored
`pr-pause` failure. If the sleep capability was missing or failed, the code
could repeat peer or quorum checks immediately until its iteration limit.
Both loops now return `recovery/wait-unavailable` when the pause fails. A
successful pause retains the existing bounded retry behavior. This does not
make peer failure an authenticated lease or authorize a retry after unknown
external effects.

On one local Darwin ARM64 diagnostic compiler/packager, a source-unity fixture
used the current `rf-watch` and `rf-wait-previous` with a failed pause and
stubbed peer/previous-lease calls. Both returned the explicit failure. Two
separately compiled mutations, each bypassing one pause check, failed the
fixture. The earlier zero-clock negative control also failed as expected.
Source/image SHA-256:
`7b5387bcddc361d0e86d1104eac601a352991121f64e858807e7c7f67b537428` /
`bcf89dd7fb9f7aa56c2bf0c6fb22d9fd214630fab40ac5fe8b51f22b3a10ea7f`.
The fixture is not a live peer, real quorum, full source-locked build, or
qualification of a native host supervisor or governor.
