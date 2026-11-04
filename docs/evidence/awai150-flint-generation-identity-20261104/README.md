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
