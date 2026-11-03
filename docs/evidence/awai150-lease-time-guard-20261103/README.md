# Lease clock and epoch boundary

The lease core now rejects negative or non-safe-integer timestamps and bounds
`held-now?` before subtracting skew. The old exact function, supplied the
minimum signed i64 expiry directly, treated the lease as held because the
subtraction wrapped. The new function refused it on a local native Mac test.
This is a function-level observation; it does not establish that the JSON
parser or an authenticated voter accepts that out-of-range input. Previously
accepted expired but nonnegative leases remain valid Paxos values, so adopting
an old value is not prohibited. Invalid epoch increments return `-1`, and
invalid `decide` times return `lease/invalid-time` rather than authorizing a
takeover.

The resident also validates a learned lease's task, slot, epoch and bounded
expiry before using it to choose renewal or takeover. It does not start a quorum
round when the local epoch or clock cannot produce a safe expiry. A source
fixture with substituted quorum and I/O returned no lease for an invalid
learned value, but still proceeded for an expired valid lease; removing the
pre-validation made that test fail. An independent extracted-source test used
the actual JSON parser and recovery admission plus the updated lease core; it
also refused empty and malformed lease input before canonicalization.
Proposal tests used a stubbed quorum. These are not live voter, clock-skew,
crash recovery, security, bot or fleet integration tests.

The signed diagnostic Mach-O fixture hashes were: core
`7dd2ac86613e82f8b8856df23c44bf64ff2afbfbc010b41dbdc6cf6234384e9f`;
old underflow `9b284c380049d3ea491c8e33b4cccf6a5a61381a631227634a19e2b912c212a8`;
new underflow `8a35399197d9465ae68c97b8d02d7dc939508333779599f9de7265c5ba7024e0`;
recovery `11cf140417a513716d1a8e05c3e3cd0f327264b2f4f58845cabd982aa87eb64a`;
resident `9bd90ca327715f81d5c872d6a1987406fec7827e7fcbaf07210976dd51554fc2`;
resident mutant `58c599772a9344b513f5d8dd5dc92e0e390b5176b0d6d7b4dce9d6db3dc079ae`;
proposal `7410e87013f24d43594aa800f776f13fde67fc1a15d01f9fa318503ac7e92c37`;
clock `3b6e98a754cb7b196d34565f7e7a27a4c0ecd6f09687cf99180b21f4e018e3c9`.
Source hashes: core `76bba32a0e8c5057069b302139f5b55e1fd396ed9fc749e7a882d7806d491279`,
resident `e166a951b592bb7a135394e09b63a984a758ac7118494f5da77be1443ba1afed`.
Bootstrap compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`,
diagnostic FS2 packager `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
The generator scripts are test-only, depend on a prior source-unity fixture,
and use fixed scratch paths. Adapt them before reuse.
