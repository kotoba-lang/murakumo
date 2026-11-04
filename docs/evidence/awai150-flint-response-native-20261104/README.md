# Native Flint response and integration cases

On one local Apple Silicon Mac, a source-unity fixture combined the current
`profile_recovery.kotoba` and both Flint test files with previously assembled
historical JSON, checkpoint and resident dependencies. A diagnostic Amu Mac
compiler built an ad-hoc signed native image. The image ran the actual
`test-flint-response` and `test-flint-profile-integration` functions and
returned zero. A separately built mutant replacing the top-level error-member
check with `false` returned 1. This tests the combined fixture, not the full
source-locked application build.

The selected source SHA-256 values were: recovery
`8a15d377928a32c4b0951958d6ea81ef5de6541a41c2b0270764b686bd4169b5`,
response tests `d8554bc1cfd87fc12f2a35256dbc7195f8526bbac028320d251a57d2343798cb`,
integration tests `8cd27ce508384df91ac152b95969065e7c055d311d94a1f698acd2c9b3a112a5`.
Fixture source SHA-256 was
`c9767e6726fae1b15c507c2787d2dcce4ab7b509f5cbab1054c553056518ebd6` and
signed image SHA-256 was
`0266f768035f32f6a534d3c6cf3f894eceea442750677841a7f4dd75a21fc530`.
Compiler SHA-256 was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`,
and diagnostic packager SHA-256 was
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.

A second signed native fixture used diagnostic FS2 reads of two previously
saved Flint coding responses, 1180 and 4628 bytes. Both returned model name
`flint-ud-iq2_xxs`, one choice, assistant role and `stop`. Each was accepted
when that exact model name was expected, compacted successfully, and the
compact digest matched the corresponding raw bytes. Expecting `flint` for the
first response returned `flint/model-mismatch`. This sample does not prove
what name any future endpoint will return; the trusted host must bind the
actual configured model identity. The raw responses were not copied into this
repository. The second fixture source SHA-256 was
`f7ff9aa08e3fa262e2fba6c4e8c54e052dd92ae6709f499d505d52eb4e822e3c`,
and its signed image SHA-256 was
`989997aea6e43061775d2bdc05c7f86ac2c26534e85b5fa11b2ed72cb712d679`.

These fixtures used a pinned diagnostic compiler and provider, with historical
dependencies and test-only host assembly, not a qualified selfhost/import
closure or a production provider. They do not run token counting and dispatch,
validate a live server, authenticate model weights, prove the saved response
origin, exercise GitHub state, qualify the governor, or start an agent or bot.

## Refusal-bearing responses

The response validator now rejects an assistant message with a non-null
`refusal` member, even when it also contains answer text and `finish_reason`
is `stop`. An absent or null member remains acceptable. A signed local native
source-unity fixture passed absent, null, string and object refusal cases;
removing the guard made its negative control fail. Source/image SHA-256:
`e4b0f9c3188e542f74f210033109cd2568377899fbd1794e22339f995b3e87d6` /
`c3c5bfa5e3924d4a8cf095294d710b47927dcca7d6248bba066334b33118125e`.
The two previously saved responses above remained acceptable in a second
native fixture, source/image SHA-256:
`8e5c4a60ce37cefef423adcf05ad29e50bc6fef58a9ba11110760f71fc3d4e33` /
`123e786487551df1dfad8ac997969373c062a99fb0bd864a6cd1c7c9a3435a94`.
Each native process had a three-second CPU cap. The compiler and provider pins
and the scope limitations are unchanged; no live inference was run.

## Choice index validation

The candidate accepts an absent choice index for compatibility with existing
compact records, but if present requires an integer zero. The selected current
response validator and cases passed a locally compiled Darwin ARM64 diagnostic
using the previously pinned compiler/FS2 packager; index zero was accepted,
and one or string zero were refused. Removing the index guard caused the same
case suite to fail. The assembled selected-source fixture SHA-256 was
`ceae17d63ca8eb4d458255f9de3295ab9c815e6e7ba5891eea6ad47fdb9e7ea0`,
and its signed image SHA-256 was
`a8e040d5f5dc66fff7ef43a2ccaec30cd7bac7309c62e959e7617b9b5374e705`.
Other assembled dependencies are historical. This does not exercise a live
server, authenticate its model, validate a bot turn, or qualify the governor.
