# Cursor pending-state check

The controller previously planned and could return idle even when the cursor's
pending replacement existed. It now refuses before planning, archiving or
dispatching unless FS2 reports that path absent. It does not remove the file
or determine whether a prior write succeeded.

On a local ARM64 Mac, the attached fixture copied the current controller
functions and used the diagnostic native FS2 provider on a private temporary
directory. Other dependencies, including planning, archive, cell execution and
cursor advance, were stubs. With no pending file, idle returned
`cron-resident/idle`. With a pending file, both idle and due cases returned
`cron-resident/cursor-unknown`, leaving cursor and pending bytes unchanged. A
control with the new check removed returned idle despite the pending file.

This is not a concurrency, crash/power-loss, complete scheduler, GitHub or
model-inference test. FS2's diagnostic Darwin existence operation can report
absence on permission errors, so host isolation and provider qualification are
still necessary; an explicit nonzero or unknown result is refused. External
mutation after the check is also outside this test and must be excluded by the
host's serialization and filesystem authority.

Controller source SHA-256:
`f6389f7cd0609c491c7b81ece86df38048c250e9a2fc3ba735d122ef0f5f964e`.
Fixture and old-control signed image hashes:
`401695b6c9a98562925f8c86178362cca3e0def3741a52f3c36c36dcd4b6f0f2`,
`018df3227eb3031ba173eab6dda1167878da7c1753564979c4a1c330acd4df69`.
