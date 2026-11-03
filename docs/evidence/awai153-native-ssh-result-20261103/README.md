# Native SSH result framing candidate

`tunnel_core.kotoba` now exposes native `remote-exit` and `remote-output`
for a trusted, wrapped command's captured output. It requires one final
`__murakumo_rc=` marker followed by a canonical 0–255 decimal status and LF;
missing, malformed, duplicated, oversized or trailing data is unknown (255)
and yields no output. A nonzero SSH transport status stays nonzero. Valid
output preserves the bytes before the marker, including newlines. The earlier
legacy host still has its own parser; no native transport or caller was wired
by this change.

On one ARM64 Mac, the full tunnel source plus a test entry point compiled via
the native bootstrap and diagnostic packager. The fixture passed normal,
UTF-8, nonzero remote/transport, missing, truncated, duplicated, out-of-range,
leading-zero, whitespace and trailing-data cases. A test-only mutant removing
the whitespace guard failed. This is a bounded native framing test, not an SSH
integration, complete adversarial parser audit or production qualification.
The fixed in-band marker is not authenticated: a remote process able to emit
arbitrary output can forge it before disappearing. Do not treat it as a
trusted receipt or distributed fence; a nonce alone would not authenticate a
compromised peer. The host must pin command/peer authority and separately
handle uncertain execution and partial transport output.

Source SHA-256 `2d6eca0e7e1c146bdb9c3f20a8bd1eaa941dd4839135cd7e119980a3035b7f35`;
fixture `9818eb1b7948781bb36443205f2fe4b60876bd8cc012be8df61a19e8cfbcce3d`;
signed fixture `ba5927aff58d769b32be5b29b991b3c2b4a0fb6de8c3413595375d3907953ae1`;
signed mutant `079f41c68719f28401574554a092ddb01d7a2e14dfb06c224e2678a80c8df20c`.
Compiler and diagnostic host match the preceding
[tunnel native observation](../awai153-tunnel-native-20261103/).
