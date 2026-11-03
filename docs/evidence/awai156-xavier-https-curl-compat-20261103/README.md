# Xavier curl compatibility for HTTPS dispatch

On 2026-11-03, Xavier's `/usr/bin/curl --version` identified curl 7.68.0.
`curl --fail-with-body --version` returned 2, reporting that the option was
unknown; `curl --fail --version` returned 0. Therefore the previous HTTPS
branch of `pr-submit`, which passed `--fail-with-body`, could not execute on
this controller candidate. The source now uses `--fail`, so HTTP errors fail
without retaining their bodies for model processing.

A native Mac emitter used the current `pr-local-mtp?` and `pr-submit` source,
with configuration lookup and spawn stubbed, to emit the HTTPS argument
sequence. A test observer adapted only the endpoint, trust and proxy settings
to reach a task-owned localhost TLS peer on Xavier: the fixed public URL was
replaced with localhost and test-only `--cacert` / `--noproxy` arguments were
added. It used ephemeral self-signed P-256 certificate and key, a synthetic
request, and distinct transient user units with the emitted limits. The 200
case reached the peer and returned the expected JSON with unit `success` /
exit 0. The 500 case reached the peer, returned `exit-code` / 22 and produced
no response file. The exact units were stopped/reset and reported `not-found`
/ `inactive`; peer threads ended and the unique test directory was removed.
SSH used strict host-key checking against the keys in Xavier's live Tailscale
peer record, with only a temporary known-hosts file.

This verifies argument compatibility and synthetic HTTPS error handling, not
the real public endpoint, authentication, proxy and CA environment, native
spawn integration, MTP, GitHub, or a qualified governor. The Xavier systemd
cgroup limits were previously observed not to be effective; this test does not
change that finding. No inference workload or existing service was started or
stopped. The observer is a test-only script and contains local paths requiring
adaptation; it is not an operational dependency.

- Recovery source SHA-256: `a7187c449dc938cc338686bc05cf9e27df2f1d4c41bc4acc55e3ba861f109fa2`.
- Fixture source: `13deca12dae685a0c4213ab04d9c99d2cf2fa53f29abec113b4bf11b2bc565a7`; signed native fixture: `409bd51986600ddb682fb389051cf2a965c7e957d7db325ab0523ca7275aa3ff`.
- Observer: `dfd783d83288dde45497086328876859ce61e63d45a40e31f007d689ed1811fb`.
