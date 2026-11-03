# Mac private-host GitHub authentication observation

The [fixture](fixture.kotoba) asks the diagnostic Mac private host to run
`/opt/homebrew/bin/gh auth status` in a task-owned directory and observes only
the child exit status. It does not request, print or store a credential. The
[receipt](receipt.json) pins the host fixture, candidate runtime source and
GitHub CLI executable. The operator's separate CLI status reported an active
account; the private host's child reported no GitHub login and exited 1.
The host has a minimal child environment rather than automatically inheriting
the operator's session.

This is one CLI invocation on one Mac, not proof that every credential source
is absent. It does not establish a repository-scoped issuer, credential delivery,
GitHub transport, process-tree containment, independent fleet operation or bot
qualification. No host login or credential state was modified. The private
host is privileged and the test fixture is not an agent-facing broker.
