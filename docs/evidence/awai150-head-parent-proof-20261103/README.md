# Exact parent validation for checkpoint head readback

The previous readback checked `FETCH_HEAD^`, which resolves only the first
parent. A merge commit could therefore pass the parent comparison even though
the candidate's contract requires exactly one parent. The non-GraphQL staging
and readback paths and the GraphQL readback path now compare the full
`git rev-list --parents -n 1` result with the fetched tip and expected parent.

On a local Mac, an Amu-compiled native fixture containing the real head and
GraphQL sources and a test-only privileged process adapter used a disposable
local Git repository. The new check accepted an ordinary one-parent commit and
refused a two-parent merge, a wrong parent, a wrong tip and a root commit. The
old `rev-parse merge^` independently returned the expected first parent, so
the merge was a negative control for the previous check. `probe.py` records
the task-local test driver; it requires the separately assembled fixture at the
paths shown in the script and is not operational tooling.

In a second test, the same compiled native source staged and pushed two
concurrent successors of one expected head to a disposable local bare remote.
Exactly one succeeded, and independent readback matched the winner and its
parent. No network, GitHub credentials or production state were involved.

Fixture source SHA-256:
`a25ebfdd76bc635c9822c70574ad801df403ff5b79f17b3d5206dc94bd921a04`.
Signed fixture SHA-256:
`208bfbbc9f467705af774ae0f6f87897404e678ef3fd7776d5689107ee792df1`.
This proves neither a lease nor freshness, ABA protection, production process
isolation or the real GitHub GraphQL candidate's full network path.
