# Current archive candidate on two Mac minis

On 2026-11-03, the same pinned diagnostic Mach-O and synthetic input were
copied over Tailscale SSH to fresh private temporary directories on `levi` and
`benjamin`. Both reported Darwin 25.6.0 ARM64 and Apple Git 2.50.1. Each host
independently created a local bare Git remote, captured and signed a synthetic
checkpoint, archived it, independently checked the remote tree, fetched and
restored it, and compared HEAD, staged/unstaged changes and an explicitly
listed untracked file and mode. Each refused a synthetic non-private response,
a wrong policy and a repo-local synthetic credential helper; a clean Git
configuration was accepted. The saved per-host outputs contain only synthetic
checkpoint information.

The source is the same current checkpoint/archive code as the prior
[local Mac fixture](../awai150-mac-local-archive-20261103/), with only its
fixed test-owned physical directory changed. The privacy result and Git URL
are test substitutions; `cp-spawn` is a privileged fixture adapter. The remote
is `file://` on each mini, not GitHub or a cross-host store. No production
credential, model, bot, GitHub transfer or existing service was used. Tailscale
carried the test binaries, scripts and synthetic results.
Each guest process was capped at five CPU seconds by the observer. The remote
fixtures were removed after collecting outputs, and absence was checked on
both machines. Levi's SCP transfer was slow but completed; it was not restarted.

The Mac binaries were generated once from a 1-CPU / 2-GiB disposable Linux
ARM64 compilation VM and the diagnostic Amu #1796 `ed73d0fd` packager. The VM
was removed. Each mini's copied binaries matched these SHA-256 hashes:
`fixture.macho` `9013c2ab31c6a5cd472973738b4bc71bb42faa1a91a4353ec7c6f9622027759f`;
`config-fixture.macho` `cb12f9eaf46e3992f70d670d8872f511cbd5f839271504aa3c8affe32ce1a404`.
The fixture sources were
`99e46624bb87456eed2f573c1de90ec6a4a4107f45f63bb116cd501f885de8c5`
and `3450abc60dc41f029a294deb603e0546d0925ecd35589305266b2fbc5c44cbab`.
The Linux compiler and Mac packager hashes were respectively
`914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`
and `cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
The included scripts and fixture use a specific temporary path and must be
adapted for another run.

These are two local diagnostic tests, not a distributed archive, native GitHub
authentication, production host qualification, autonomous turn, power-loss
test, resource governor or ten-node inference test. The privileged adapter is
not an isolation boundary for untrusted guests.
