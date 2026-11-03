# Journaled local model publication candidate

`model-artifact-publish-main` takes the same pinned OCI inputs as the stage
entry, plus fresh sibling stage and destination paths. It validates the OCI
descriptors and assets, writes and syncs a sibling intent binding manifest,
stage and destination, then stages the model. It writes and syncs a receipt
inside the stage with manifest, base, MTP and asset pins and sizes. After the
stage directory sync it uses the provider's `T` atomic no-replace rename to
publish, syncs the parent, moves the intent to a no-replace `.complete` marker
and syncs the parent again. Any uncertain step returns
`model-artifact/publication-unknown`; no automatic replay or cleanup occurs.
Existing targets, stages, intent/complete markers, overlaps and malformed
inputs are refused. The older staging entry retains its interface.

On one Darwin ARM64 Mac, a native fixture with the extended-range diagnostic
FS2 provider published an ORAS-created synthetic OCI model. All staged weights,
eight assets, modes and receipt pins matched, the stage name disappeared and
the `.complete` marker appeared. Existing target and stale intent were refused;
a validly pinned malformed asset tar left intent and an incomplete stage. A
separate provider probe confirmed `T` did not replace an existing nonempty
directory and did rename a directory to a fresh name. In a test-only fault
fixture, the real directory rename completed but its reply was discarded:
the entry returned unknown, the target and intent remained, and no `.complete`
was created. Discarding the reply after the intent-to-complete rename also
returned unknown while target and `.complete` remained. The standalone staging
regression passed with the refactored source.

This is local diagnostic publication, not deployment or authorization to run
a model. The host must own and isolate the directory, prevent consumers from
using a target while its intent is unresolved, and reconcile all unknown
states. The receipt is not a signature and does not prove publisher identity,
an immutable post-publication model or an authenticated loader. No durable
recovery implementation, power-loss qualification, hostile concurrent writer
test, production FS2 provider, real model, GPU inference or governor is
established here. Model weights were small synthetic fixtures; full staging is
high load and requires the qualified governor and disk admission.

| Artifact | SHA-256 |
| --- | --- |
| publication source | `5877619693e0821fddac7110c18292fc74910f339ce0c68124dd2bbb2099aea4` |
| model reader source | `a061473887da6ef4d41c0466d4d49230d0681a98bd8663dd497a451ad2a9f938` |
| fixture source | `ed69a13d81acbd124002da027229e10bbae222f9958db0c8881c4cb713914647` |
| lost-reply fixture | `b26a10f5825f36cc5a176e1d7413b472e19942d55743b97f856b247714011667` |
| signed fixture | `d5a852ecc05069d9fc93e044714a369c0a665d9b2c6db17c0a665b97155120c1` |
| signed lost-reply fixture | `1d842880867ec9c7aed22db977afeeb527544134b63947f4878db4917a6eed9a` |
