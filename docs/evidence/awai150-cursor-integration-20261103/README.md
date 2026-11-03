# Cron controller and updated cursor boundary

The cursor source formerly named in the composition treated any FS2 D result
other than `1` as absence while advancing. The updated cursor requires `0`.
The controller also checks the pending path before planning; the separate
advance check matters if its state becomes unknown later. Host serialization
and protected paths remain mandatory.

The attached test-only source composes the actual `nc-advance` and updated
`cc-advance` functions with a diagnostic Darwin FS2 D provider. It substitutes
the head, plan, archive, completion, time, CID, cursor bytes, writes, rename
and fsync. On one local ARM64 Mac, a present pending file, an unreadable pending
file, and an injected unavailable response returned
`cron-resident/cursor-unknown`. A control using the prior existence check
reported `cron-resident/completed 60000` for the unreadable file. The fixture
does not test the full cycle or a real concurrent change, actual persistence,
process lifecycle, GitHub, leases, model inference or a production runtime.
The diagnostic provider itself is not production-qualified.

Cursor source SHA-256:
`365edbdc592c640e0469668f581a67d41031e5020b4b6dd7b906cd447409adc2`.
Controller source SHA-256:
`f6389f7cd0609c491c7b81ece86df38048c250e9a2fc3ba735d122ef0f5f964e`.
Signed candidate and old-control fixture SHA-256:
`a896b238b32fd5045c57b12cb922319dbc9bbbf3ed52ce786d7665484f29e0a3`,
`1bb3d637d7d46f2a9beacd30bdcedcd3698009265381c75a261c8e3a650263db`.
