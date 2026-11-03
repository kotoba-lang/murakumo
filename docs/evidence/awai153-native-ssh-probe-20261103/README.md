# Native SSH result probe against one mini

On 2026-11-03, a source-unity Mach-O combined the actual current native tunnel
core with a test-only adapter for the privileged diagnostic `amu-cli-host`
process API. It launched pinned `/usr/bin/ssh` from the local Mac to Benjamin
over a Tailscale address, with a known host key, an explicitly selected existing
SSH identity, `BatchMode`, `IdentitiesOnly` and bounded connection settings.
The host's five-second child timer bounded each SSH call. The private key was
used by SSH and its contents were not read or copied. Three fixed, task-only
commands were run; no model, bot state, production configuration or existing
service was modified.

- `printf native-ok`: native parser returned 0 and the exact payload.
- `exit 7`: native parser returned 7 and empty payload.
- A task-only remote shell killed itself before its wrapper could emit the
  marker: capture was empty and native parser returned 255 and empty payload.

The final fixture was rerun for both normal cases after adding the interrupted
case. This demonstrates one real SSH integration of the extracted native
parser with fixed commands and a private diagnostic host; it is not the
production tunnel adapter, a full native SSH provider or an authenticated
remote completion receipt. In particular, a remote process can forge the
fixed marker, the privileged host is not a guest capability boundary, and
resource/process-tree control and other fleet hosts remain unqualified.

Fixture source SHA-256 `f3cea6a21ea75327940be7bccb0829313d3ad7dc0d94bc95da3a357d79266b53`;
signed fixture `e80a5c10d0748a54c59f9ad6855be7756bf13517e6a36052db8e965469e4b243`;
diagnostic host packager `cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
The fixture is task-local because it embeds host-specific paths; these hashes
pin the actual experiment. Captures contain only the fixed non-secret output.
