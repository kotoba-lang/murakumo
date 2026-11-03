# GitHub GraphQL expected-head observation

On 2026-11-03 the operator's existing `gh` login accessed the private
`cloud-itonami/itonami-bot-state` repository. A unique task-only verification
ref was created from its existing main commit. Two concurrent GraphQL
`createCommitOnBranch` mutations used the same `expectedHeadOid` and different
synthetic JSON. One succeeded and the other returned an expected-head mismatch.
An independent API read matched the winning commit and synthetic bytes; a
further stale expected-head mutation was refused without changing the ref. The
unique verification ref was deleted after checking that its tip matched the
winning commit, and its absence was verified. Main and production refs were not
changed. The observer does not read or print credentials; its script SHA-256 is
`6e403d67ecd40b5001c9a7e15cd6bd00f73842ad5d6e3128e07a64376fcd08e0`.

The first attempted GraphQL input used `refName` as suggested by one prose
passage in the documentation, but the live schema rejected both mutations and
specified `repositoryNameWithOwner` with `branchName`. That first temporary
ref remained at its original commit and was removed after checking the exact
value. A read-only schema query confirmed `branchName` is unqualified. The
[GitHub GraphQL reference](https://docs.github.com/en/graphql/reference/commits)
documents `expectedHeadOid` and the commit-on-branch mutation.

The repository rulesets API returned HTTP 403 with GitHub's upgrade/private
repository message to this account; the repository remains private. The result
is specific to this API/account and does not prove every branch protection
mechanism unavailable. The GraphQL expected-head comparison is atomic at the
mutation boundary, but it is not a lease: a privileged actor can restore an
old OID (ABA) if ref rewinds/deletions are not prevented, and an expired lease
holder can still win before another valid update. Bot-scoped credentials,
rollback prevention, sink-side authorization and exact recovery after an
unknown response remain required. This operator-side synthetic API test does
not run the native candidate, a bot, inference, or a production head.
