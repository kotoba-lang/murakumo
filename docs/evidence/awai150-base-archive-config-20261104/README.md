# Base checkpoint archive configuration diagnostic

On 2026-11-04, the #586 base checkpoint and archive sources were composed and
run natively on jacob, macOS 26.3.1 (a), build 25D771280a. The base checkpoint
source SHA-256 was
`21ecaa771fb17580c39b62cf640c4c7d5aa1ac866d85de25194f937c5da418e3`;
the archive source with the config guard fix was
`a5e8808cd21e2d68f3b79e9ed74f7ec1919805532db61f8ecd9403ab2d74846e`.
The generated fixture SHA-256 for this run, including its unique temporary
path, was `d63f3d7a90551416e040c223038d4881b162d2c7d5fb1c00408e820e1c55ed06`.

The native test accepted clean and benign Git configuration, refused ten
synthetic dangerous configuration entries and a missing repository, captured
and archived a signed synthetic checkpoint to a local bare Git remote, and
independently restored and compared the local repository state. Wrong policy,
existing destination and corrupted block controls were refused. The native
compiler and host packager were the previously pinned diagnostics
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` and
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.

The Git adapter is privileged and test-only; repository privacy is stubbed and
the remote is local. This does not test real GitHub, credential delivery,
full provider failure behavior, power loss, host containment or a governor.
The base component still differs from #594's bounded-write implementation.
