# ADR-260728: W6 bare-git audit closed

- Status: Accepted
- Date: 2026-07-28

## Context

After murakumo#55 (absolute git for pin), gap Next still listed optional
bare-`git` audit outside deploy pin.

## Audit result (`origin/main` 2026-07-28)

| site | form | status |
|---|---|---|
| `core.clj` cmd-pin | `resolve-git-bin` + `git-short-sha-argv` | absolute only |
| `deploy.plan/git-short-sha-argv` | required absolute `git-bin` | **hardened** (this ADR) |
| other `src/**` | no bare `"git"` spawns | clean |

## Decision

`git-short-sha-argv` **throws** unless `git-bin` is absolute. Bare `"git"`
is never emitted. Fixture tests pass `/usr/bin/git` explicitly.

## Consequences

- W6 bare-git audit action closed.
- Ops pin already fail-closed without absolute git (#55).
