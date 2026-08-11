# ADR-260811: Version GitHub merge governance

- Status: accepted
- Date: 2026-08-11

## Decision

Record the expected GitHub Actions and `main` branch-protection state in the
adjacent EDN file. `scripts/check-github-governance.cljs` reads the live GitHub
API and requires an exact match after normalization. The required `test` check
is bound to the GitHub Actions application, strict status checks and
administrator enforcement are enabled, resolved conversations are required,
and force pushes and deletions are disabled.

The EDN is desired state, not evidence that GitHub currently enforces it. A
successful authenticated readback is the evidence. The verifier is an operator
audit rather than a pull-request job because the workflow token is deliberately
not granted repository-administration access.

## Operation

An intentional policy change updates GitHub and the EDN in the same change,
then runs:

```bash
npx nbb scripts/check-github-governance.cljs
```

The command requires `gh` authenticated with read access to repository
administration settings. API errors, absent fields, and any mismatch fail
closed. Required checks must not be removed or detached from their application
to unblock a change; repair the check or supersede this ADR with explicit
evidence.

## Evidence

Protection was exercised with PR #287: an empty commit carrying `[skip ci]`
produced no checks and GitHub reported the pull request blocked. This verifies
that an administrator cannot merge merely because no check run exists. The
live verifier makes later configuration drift observable and repeatable.
