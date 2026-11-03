# MTP model path and existing server sample

At approximately 06:10 UTC on 2026-11-03, a bounded read-only SSH sample used
pinned host keys to inspect the exact historical experiment path
`~/.murakumo/awai147-mtplx-bonsai/` on ten Mac minis. All ten returned the
config file's `stat`. Dan, Issachar and Levi also returned file sizes of
8,595,477,990 bytes for `model.safetensors` and 238,934,226 bytes for
`mtp.safetensors`. On Asher, Benjamin, Joseph, Judah, Naphtali, Simeon and
Zebulun, `stat` reported `No such file or directory` for both weight paths.
No weight file was opened or hashed. Identical sizes are not integrity or
version verification. An absent path does not establish that no copy exists
elsewhere, and this does not reconstruct the nine-host experiment's staging.

In this test the remote `stat` exit status was zero even when its stderr
reported missing files. The observer classified the two named errors and
checked the expected output count rather than treating exit zero as presence.
This is an observed behavior of this invocation, not a universal macOS rule.

A separate process sample found existing GGUF `llama-server` processes on
Joseph and Zebulun; both loopback health and model-list endpoints answered.
No process whose first four argument tokens contained `mtplx` or
`mlx_lm.server` was observed on this pass. That narrow name-based scan does
not exclude another implementation, differently invoked process, or later
startup. The GGUF servers and the MTP safetensors are separate artifacts;
neither this sample nor health proves MTP execution or bot integration.

These observations do not authorize copying large weights, starting a server
or performing inference. The resource governor, authenticated transport,
model identity, process lifetime and actual execution remain unqualified.
No model, service or user data was changed.
