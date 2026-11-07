# Diagnostic host environment and private GitHub authentication

On 2026-11-04 on jacob, the existing diagnostic Mac host packager source
`/private/tmp/awai154-host-git-env/candidate.kotoba` was inspected. Its child
environment literals contain `PATH=/usr/bin:/bin`, `LANG=C`, and
`GIT_CONFIG_NOSYSTEM=1`; no `HOME` or `GH_TOKEN` literal is present. This is
a diagnostic image, not a qualified production host.

A read-only `gh api` request for the existing private
`cloud-itonami/itonami-bot-state` metadata was invoked with `env -i` and those
same three variables. It exited 4 and requested GitHub CLI authentication. A
read-only HTTPS `git ls-remote` for the repository's main ref with the same
three variables and an additional `GIT_TERMINAL_PROMPT=0` exited 128 with an
authentication error and produced no stdout. The extra variable suppresses
interactive prompting, so the Git test is not an exact reproduction of the
host environment. The ordinary interactive `gh` session can access the private
repository, but its broad account credential was not passed to the diagnostic
child and no credential value was read or recorded.

These shell-level checks show that this minimal environment alone does not
supply authentication for those two sampled commands. They do not execute the
native archive candidate, test a scoped credential channel, establish all
possible Git/GH configurations, or qualify GitHub transport, process-tree
control, the resource governor or bot operation. The host needs an explicit,
restricted credential and noninteractive delivery design; unavailable,
expired, or ambiguous authentication and publication outcomes remain unknown.
