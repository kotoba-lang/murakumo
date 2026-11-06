# Flint generation identity and server rotation

A Flint count request identity included `serverInstance`, but the generation
request identity previously included only socket path, authorization path and
body. If the trusted host rotated the instance and reconciled a count, the
same request could still name an earlier generation unit and response. The
host contract already required reconciliation after a restart; the earlier
identity did not itself enforce this separation.

The Flint generation identity now uses `local-flint/2` and includes the
host-declared server instance, socket, authorization path and body. A retained
turn marker for a different identity refuses automatic reuse. The MTP and
nonlocal identity formats remain unchanged. Older Flint turn markers and units
require explicit reconciliation; they are not silently migrated. This does
not authenticate the instance, prove old work stopped or provide distributed
fencing.

On one diagnostic Mac, selected actual source and historical dependencies
passed Amu source `check`. A signed native fixture with actual JSON reader,
SHA-256 and identity helpers checked stable inputs, changed instance, changed
body and preserved MTP/nonlocal identities. A mutant replacing the instance
with a fixed value failed the fixture as expected. The selected native fixture
source SHA-256 was
`d26046189412e6b04747fd24f8083961fa671cb14261b92a3839c63115e216d4`;
the signed positive fixture SHA-256 was
`be50a0d92f5391a845c27585c17048741d3ee348d4823c92ca5805efe287fd88`.
The diagnostic used the pinned old compiler and packager, not a qualified
source-lock build; no server, actual restart, unit reuse, filesystem recovery,
GitHub state or resource governor was exercised.

## Marker and FS2 diagnostic

A second local Mac native fixture combined the current `pr-generation-id`,
`pr-flint-turn-id`, `pr-flint-turn-reserve`, bounded reread and checkpoint
file helpers with the staged diagnostic FS2 provider. In a fresh private FD3
scope, separate invocations created a marker and accepted an identical request;
a different synthetic server instance was refused and left the original
marker unchanged. Replacing the marker with malformed bytes also caused
refusal without rewriting it. A negative-control fixture with the instance
fixed inside the generation identity accepted the changed-instance request.
The fixture source SHA-256 was
`8aaa2db59f2e47b0fa28455378826067532e2893d9b11f54af8c955f3b5a2caa`
and signed positive image SHA-256 was
`331bc4a3d0a876281d1bf0bd114a3ef458f953eeb69ed2893b8e7ef7d12453ce`.

The test used real local FS2 operations, synthetic runtime descriptions and
an exclusively owned directory. It did not start or restart a server, test
concurrent writers or power loss, verify a production provider, perform a
GitHub transaction, or qualify process identity, resource governance or
selfhost. A surviving marker remains an operator reconciliation obligation.
