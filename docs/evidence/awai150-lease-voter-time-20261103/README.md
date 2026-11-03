# Voter expiry validation on loopback

In a task-owned ARM64 Linux VM (1 CPU, 1 GiB), a native static image ran three
separate voter processes and a proposer on loopback with a signed synthetic
membership topology and a synthetic HMAC token. Each case used freshly
initialized voter registers. The topology enabled `b70` and was signed with the
public RFC 8032 test key, not any fleet credential. The voters verified the
topology before accepting connections.

| Source | Proposed expiry | Observed proposer result |
| --- | --- | --- |
| Current lease core | -1 | `no-majority accept 0` |
| Previous lease core | -1 | `chosen` |
| Current lease core | 0, already expired | `chosen` |
| Current lease core | valid future expiry | `chosen` |

The negative-expiry control demonstrates a behavioral change at the actual
membership-aware voter boundary. Accepting expiry 0 preserves the ability to
adopt an expired nonnegative Paxos value; it does not authorize executing it.
The test does not show that an i64-minimum timestamp can pass the JSON parser,
or does it exercise the resident, wall-clock skew, power loss, independent
machines, Tailscale security, actual bot workloads or production credentials.
The voter state, token and topology lived only in the disposable VM, which was
stopped and deleted after the run. The observer terminates only its own voter
processes; it and the generator are test-only and use fixed scratch paths.

`generate.py` composes the existing dependency fixture with the current lease
core and voter definitions; the old-core control uses core at `9a1a5ba` with
the same current voter. Dependency source history remains that of the prior
fixture; this does not certify every dependency against current main. Compiler
SHA-256 `914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`;
signed topology `94bc664bc7b72bd1e3d4578a7fd44c2098051572906ce4d7623c519997ab8b6d`.
Current source `2b32672ae4795c3bb9110cdabb11c51442457b1c534c8ad4eca7007f611c43fa`,
old-core source `720b459972374b2ec246c5c1c8e4ba78d9c92019962984cc2ad3009039aaff78`;
current ELF `21d78434fd11a806a4a168378b3877befd30e13c30db6a0bb7510eab080edd03`,
old-core ELF `1f96eb8c4437cc2d69ba6d26d3b5088e881d0f6acb25d7ec76868d97f74c8cd5`.
