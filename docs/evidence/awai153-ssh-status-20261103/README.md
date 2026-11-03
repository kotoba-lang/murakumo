# SSH completion requires a status marker

The fleet's wrapped SSH command emits its own remote-status marker. A local
observation in [Amu PR #1801](https://github.com/kotoba-lang/amu/pull/1801)
showed that Tailscale SSH can report exit 0 after a remote process dies by
SIGPIPE. Previously `pick-exit` returned SSH's zero when the marker was absent,
and accepted a remote zero even when SSH itself reported a transport error.
Both conditions can make an uncertain operation look successful.

The Kotoba decision now preserves nonzero SSH status, accepts a valid 0–255
marker only when SSH returns zero, and maps a missing or invalid marker with
SSH zero to 255. Existing callers use 255 for failure; `ssh-exit` remains
available to distinguish a zero-status missing-marker case. This is not a
proof that the marker is authentic: the older line-based protocol is not a
secure framing or distributed receipt, and callers must not use it as one.
The legacy CLJK host documents this behavior and its tests are updated, but
that host is migration inventory, not the intended native operating path.

A local ARM64 native fixture ran the decision with the record descriptor
spelled explicitly for the current bootstrap compiler. It covered clean
success, remote failure, missing marker, transport failure and invalid marker;
the fixture passed and an old-logic negative control failed. The compiler's
`amu check` refused the full existing `tunnel_core.kotoba` because its old
2-argument `record-get` syntax is unsupported (E2103). The entire component,
legacy CLJK tests, real SSH adapter, signal/crash cases and fleet execution
have not been qualified by this fixture. Full migration and a trustworthy
framing/receipt boundary are still required before native deployment.

Kotoba source SHA-256 `a755e67e1da2901f1bc5ff4b0b6b2e8fcac78b4640751d72b20261e0e1de764c`;
fixture `9f73d16eaa25955d791456fa777af7fed3178eec9cbf7b0a55e537e680e00daa`;
signed fixture `31e28e75d35f8e3efee3385e92d15f8989f506cc706a4c01ecb6cab394d9befe`;
signed old-logic control `460619a48e3a38c247a422d2e4e3f55d79c02a779c4866a7821950e57671d38c`.
