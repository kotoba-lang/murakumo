# Archived two-phase lease-client fixture

These are the exact fixture source and operator script used for the combined
Xavier observation described in [the shared-deadline evidence](../awai150-flint-shared-deadline-20261104.md#combined-two-phase-and-native-lease-client-seam-on-xavier).
The fixture is an assembled diagnostic snapshot with historical dependencies
and test stubs; it is not application source or a production binary. Do not
assume it tracks subsequent changes to the resident or lease client.

* `fixture.kotoba`: SHA-256 `5b44f7efa1f60552e1716020eec38c2de1644af2c1f09b773d4ad223a6a4760a`.
* `operator.py`: SHA-256 `9de01f28fbfb3dadb4c91e7dfc06a7d8ec177b93520677d9fda27f7dbc94b570`.

The unmodified operator script is retained to document the observed run, not
as an unattended test runner. It refers to absolute temporary paths and a
specific Tailscale host and systemd user unit. It needs the separately obtained,
SHA-256-pinned source-derived F3 compiler recorded in the evidence, SSH access,
and a compatible Linux host. It creates a transient unit and loopback listeners;
read and adapt it to the host and confirm its lifecycle and resource limits
before any rerun. It does not download the compiler. Its resource sampling is
not a hard RSS limit, and the model and GitHub publication are not exercised.
