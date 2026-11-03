# Native macOS host-protocol observation

The earlier cross-host observer fixture replaced the guest's wire 20 call with
a diagnostic adapter. The native macOS CLI host instead uses the AMUH framed
process protocol and captures output to a host-scoped file. The new Kotoba
component composes the existing Amu `macos-cli-api.kotoba` host protocol with
the observer: its remote entry invokes the three fixed OS commands; its
receiver invokes a fixed SSH command and validates the returned snapshot.
Each side requires a fresh private work directory. Existing files, malformed
input, nonzero process exit, missing output and invalid sections refuse the
observation. Captures are retained for host reconciliation, not auto-deleted.

On a local Mac, the native remote entry compiled with the AMUH diagnostic
host and executed the actual `sysctl`, `memory_pressure` and `df` commands.
A second invocation reusing the same work directory, an extra argument and a
relative path were refused. A native unit fixture also checked argv framing
and invalid node and path inputs.

A separate native receiver and remote sender were then run over Tailscale SSH
against Benjamin. Both used the same AMUH host-protocol source directly;
there was no test adapter replacing the guest's process call. The receiver's
fixed production binary path was substituted with a newly created temporary
path for this test. The remote binary hash and mode were checked, and the
receiver returned all four validated sections. After a nonempty process-list
check and digest/capture checks, the task files were removed and the remote
directory's absence was confirmed with an explicit completion marker.

The host packager was still a task-only diagnostic build with a 15-second
timeout, and the SSH marker and observed values remain untrusted. This test
does not qualify the public CLI host, process-tree containment, installed
paths, other minis, reservations, a resource governor or an inference turn.
No persistent service was installed or changed.

Separately, a marker-framed recheck confirmed that the earlier Asher test
directory `/private/tmp/codex-awai153-fleet-9986bac48199` was absent. This
resolves the cleanup uncertainty noted in the preceding diagnostic.

| Artifact | SHA-256 |
| --- | --- |
| fleet observer source | `b3340945f0752e1db7673b7c10c9480dd6147d62b54787d5b26611ac128d8e34` |
| macOS bridge source | `4224de376ed39a44904f3757b4b0f1e46798879ee914f33106de89bdbb3f0ab6` |
| bridge test source | `254ef74e7ede2f069ff46c19a3ee5afe6730151e6bd643d8c3ade32d64cb28f6` |
| Amu host-protocol source | `37de7bb3ae5473b25ede8f4010431eceb98393dcbc592d2c49e44f6beb067107` |
| signed native remote sender | `d2a19313cfb40f642550624289feccd44daa4df2974ff908142ff31617789d9e` |
| signed native receiver | `88e629b2aa4357c09f91fc693b3b5aaafc2bdb8415bc4c75eb829cb802bdc4ba` |
| signed native unit fixture | `ed40dc98180bc86544b5a34e0494f01f3960e61293588292aad2ce2a88696d9b` |
