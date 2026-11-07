# Native synthetic Flint OCI descriptor case

On 2026-11-05 JST, the selected Flint descriptor case ran as a signed native
Darwin ARM64 diagnostic on one local Mac. It builds the compact synthetic
manifest and checks its independently expected digest, canonical JSON, layer
order, distinct Flint media types, sizes, and changed-input negative cases.
No real model hashes or weights were used. A separately built mutant replacing
the Flint experts media type with the Bonsai MTP type exited with the expected
test failure.

`scripts/verify-flint-oci-descriptor-native.py` compares five selected
function bodies in the archived `fixture.kotoba` with the current checkout
before compiling. Other fixture dependencies come from a historical assembled
source and are not source-locked to the current tree. The script requires
explicit SHA-256 pins for the separately obtained compiler and diagnostic
packager, signs and verifies temporary images, caps compiler and fixture CPU
at three seconds and removes its temporary directory. It has no hard memory
limit. The observed compiler and packager hashes were
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` and
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.

The fixture, script, current seal source and case source SHA-256 values were
`5edb37c73d35fd5bb2f00bfeff90b76896cf9529f6e39d1fe42344cef3b5d99d`,
`71ae379417961bfb95d19c25d59af6916ca85c81afc7f05c64e449610c681fe4`,
`7c716b316ab9c77a796bfb7417ec974a3b693a2093aed292441bb3c83a0485c8`, and
`6f66331f488e1de12aa5e92494615b37ab5ae7eed8790da6d34e9f7fa8a8be1a`.
The passing signed image and mutant image hashes were
`0e6ccc63a31d56e5e03d099f3178367adf679888ac11f885df79b738825c48c9` and
`76b37c6d5366a2643a2c7475f8eb2f3d6d05a3a4ba3cbcc74acf928d682c14f4`.

This exercises descriptor construction and parsing only. It does not run the
seal command, filesystem provider, real blob hashing, staging, OCI registry,
model inference, source-locked selfhost product, or resource governor.
