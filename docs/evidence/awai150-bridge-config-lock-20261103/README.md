# Bridge lock and configuration snapshot

The standalone bridge now passes the same config bytes used to choose the
resident lock into its command, instead of rereading the config after locking.
Otherwise an out-of-band replacement could select one head's lock and then
execute with a different head in the reread configuration. Host protection of
configuration, paths and the lock inode remains mandatory; this is not a
multi-host fence.

Current versions of the checkpoint, recovery, resident, bridge, archive and
controller definitions were substituted into the historical unity dependency
fixture, yielding 154 current functions. The macOS Amu CLI `check` returned
`ok`; this is a source check, not a native product build or execution. The
historical dependency closure is not requalified. A separate small native
fixture extracted the updated bridge entry point with stub config and command;
against a real local flock it refused while held and ran when available. A
mutant omitting lock acquisition ran the command while held. This test did not
perform a concurrent config replacement or exercise a real resident turn.

Bridge source SHA-256 `14f6fb3d2be84cede4de1cc19d03c69acf4f38d795dc9c282b45dba269a042f3`;
unity source `907b52346ec9a934ad8965775556c90e7f77fd8f65ae8d149cdfbd860eb955b2`;
fixture source `e55d36dc8e7194d06c695c13ec271264d1a05ffb6535cad5f6c5bd8d3c13a920`;
signed fixture `4582d5a6c5188d11b7aa925ddbaffc008859f93f4a4cdeae2b6c8a9c831c1324`.
