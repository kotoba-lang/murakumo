# ADR-260728: W6 git tooling cutover (deploy pin sha)

- Status: Accepted
- Date: 2026-07-28

## Context

`murakumo pin` ran `git -C <src> rev-parse` via bare `git` on PATH.
provider.git (id 22) + dual-runtime os-run require absolute git binaries.

## Decision

- `deploy.plan/resolve-git-bin` — absolute candidates only (`/usr/bin/git`, …)
  or `MURAKUMO_GIT_BIN` override when absolute.
- `git-short-sha-argv` accepts optional absolute `git-bin`.
- `cmd-pin` fails closed when no absolute git is found (no PATH fallback).

## Consequences

- Deploy pin no longer depends on ambient PATH for git.
- nbb/cljs hosts can mirror the same absolute-bin policy via provider.git.
