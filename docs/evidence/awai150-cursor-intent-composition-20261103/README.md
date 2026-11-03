# Cursor intent in the current source composition

The previous diagnostic's normal due case trapped while its test-only FS2
stub attempted a string-form `B` write. The candidate Darwin provider returns
that operation's frame as bytes. The fixture's separate `C` string write also
trapped when the first problem was removed. Replacing those fixture writes with
byte-form requests and checking their echoed bytes allowed the test to proceed.
This diagnosis concerns the fixture and provider combination, not a finding
that the production checkpoint writer traps.

In an isolated local Darwin ARM64 fixture, the corrected native candidate
returned `cron-resident/completed 60000` for the normal due control, and
`cron-resident/completion-unknown` for a stubbed quorum failure. With an
intent present, both due and idle returned `cron-resident/cursor-unknown`.
Planning, archive, inference, quorum and cursor advancement were stubs; the
success case does not demonstrate any real model turn or GitHub write.

Separately, the test-only assembler refreshes the previous partial unity with
current lease, checkpoint, resident, archive, bridge, controller and cursor
functions. It also refreshes the schedule range validator and its helper from
the merged itonami-agent source; the older unity contained an earlier version.
The selected cursor, bridge, controller and scheduler functions were compared
byte-for-byte against their source, and the bootstrap Mac native compiler's
`check` accepted the resulting unity under a five-second CPU limit. The
historical remainder is not fully provenance-locked. This was a source check,
not a build or execution of the full controller, production host or governor.
The assembler's local paths and historical temporary input are diagnostic
dependencies, not an operational packaging mechanism.

| Artifact | SHA-256 |
| --- | --- |
| composed source | `6be468d6d520b6abbe0f4d00e4699e5b984528575debe241a75755fcc2797816` |
| test assembler | `8275d3b293c71953f589c2e241b6e37debc3a9ac21985b8266b00c3bbd7cd424` |
| controller fixture | `98d9949a17f167e8ed1a11e568ac4eca8d0699916709fafed3bd96cd0ef0038b` |
| signed fixture | `dab97ee37a10b672243198b2c3a43f777766139e3a899e8c8a885588492a5809` |
