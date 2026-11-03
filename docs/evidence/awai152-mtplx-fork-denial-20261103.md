# MTPLX process-policy smoke observation

On 2026-11-03, a read-only, model-free diagnostic on the issachar Mac mini
(macOS 26.7.2) used its existing MTPLX 2.12.2 virtual environment. The
interpreter's CPU limit was 10 seconds and bytecode writes were disabled. A
`sandbox-exec` profile allowed default operations but denied network; the
comparison profile also denied `process-fork`.

Both profiles returned exit 0 for the CLI `--help` and for importing
`mtplx.commands.public.cmd_serve_public`. The CLI source notes that this
help path deliberately avoids importing the server. In a separate check, both
profiles returned exit 2 for `serve --dry-run --json`: this installed CLI does
not recognize those options. Inspection of this version's serve handler shows
that its final path can use `os.execvpe`, while other branches invoke a child
watchdog; this observation does not determine which branch a real deployment
would take, or prove that model/server startup works under fork denial.

The installed `cli.py` SHA-256 was
`12094e79a63e95ee05039b12c1d4c5118fcfc357ab12d51a8026a4c715341d33`;
`commands/public.py` was
`c7aa59a9e17e65d9d384016015e75fd77af737bb05b59a3ec97198caa8efb48d`.
No model was loaded and no service was started or stopped. These are tests of
an existing Python runtime for investigation, not a new production path or a
qualification of the Kotoba/Amu selfhost requirement. The permissive and
platform-deprecated sandbox profile is not a complete isolation boundary.
Process creation through other mechanisms, long-lived execution, resource
limits, process death, child/watchdog branches and other minis remain untested.
