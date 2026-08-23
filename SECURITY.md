# Security Policy

## Reporting a vulnerability

Use **GitHub private vulnerability reporting** on this repository — the
*Security* tab, *Report a vulnerability*. It is enabled, so the button is
really there; if it ever is not, that is itself worth reporting.

Do not open a public issue for a suspected vulnerability, credential leak or
privacy incident.

Include the affected revision, reproduction steps, and observed impact. **Do
not include real credentials, tokens, keys or personal data** in a report — a
path and a description are enough, and a report is not a safe place to put the
thing you are reporting about.

## What in this repository is security-relevant

murakumo is the **control plane** for the kotoba WASM lattice across the
Mac-mini fleet: it decides placement, admission and residency. The
highest-severity class is anything that lets a workload run somewhere it was
not admitted to, or that widens what a node may reach.

One invariant is worth naming because it is easy to erode: **nodes that execute
code sent from a repository must not hold publishing credentials.** A change
that moves a key onto an execution node is a security defect here even if
nothing observable breaks.

## What is not claimed

This repository carries **no third-party security certification**. There is no
SOC 2 report, no ISO/IEC 27001 certificate and no ISMAP registration covering
it, and none is implied by whatever checks run here.

The workspace-level assurance position — which controls have design evidence,
which have implementation evidence, and which have no operating evidence at all
— is recorded in [`kotoba-lang/security`](https://github.com/kotoba-lang/security).
Read the current figures there with

```sh
nbb --classpath src scripts/check-crosswalk.cljs
```

rather than quoting a number from this file, which would be stale the moment it
was written.
