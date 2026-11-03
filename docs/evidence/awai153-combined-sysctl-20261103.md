# Combined memory and swap read

The observer now asks for `hw.memsize` and `vm.swapusage` in one fixed
`sysctl -n` SSH command, then reads pressure and disk as before. This reduces
four separate SSH connections to three. It returns the same named output
sections and refuses an incomplete or malformed two-line sysctl response
before running later probes. This does not authenticate the remote metrics,
reserve memory or make an admission decision.

On 2026-11-03 around 06:05 UTC, a source-composed Mac diagnostic host used
existing SSH identity and pinned host keys to read Benjamin and Simeon through
Tailscale. Both returned all four sections. Each host reported 16 GiB physical
RAM, 0 MiB swap used and 91% system-wide memory free; the available filesystem
space was 60,988,812 KiB on Benjamin and 127,449,220 KiB on Simeon. Exactly
three task-only capture files were created per host. These are point-in-time,
untrusted host observations, not allocatable capacity; no bot or model was
started. An earlier Simeon connection timed out, so these two samples do not
prove reliability or measure an improvement in availability.

The source-composed native unit test returned zero and covers incomplete,
extra-line, nonnumeric and zero memory values as well as the existing host-key
and refusal cases. The real-host fixture used a task-only 15-second child
bound and 10-second SSH connect timeout. Its privileged diagnostic adapter,
remote status marker, process lifecycle, output authorization and full
resource governor remain unqualified. No production service was changed.

| Artifact | SHA-256 |
| --- | --- |
| observer source | `83433b674af6de31db63162ca051cb69755b4182b9c812cb7f37b7aff3c79433` |
| unit source | `c090c2ddcdf08f12556fcf32aec43e287e91126e55854f2e52569e2249bce49e` |
| signed unit fixture | `60e277ab1160cdc353ef488fe79f59b64f2e5f6fea6d2dcc3d3e14f736df42f3` |
| real-host fixture source | `65a6c90ccc57b3918da342c3fdf118a2d971bcd893661ab3684706b43f2b02fe` |
| signed real-host fixture | `40a05ca786b29305a5a183c516781474535cd545b118e400cc441ca78c7f2cfe` |
