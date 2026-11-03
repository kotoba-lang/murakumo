# Mac checkpoint through a private GitHub fixture ref

The operator used the authenticated GitHub connector to publish the eight
verified synthetic checkpoint blocks and canonical manifest to a fresh ref in
the private `cloud-itonami/itonami-bot-state` repository. No existing ref or
bot data was changed. The fixture contains only synthetic test data and a
public test signing seed; credentials were not stored in the objects. The ref,
commit, tree, checkpoint CID and byte counts are pinned in [receipt.json](receipt.json).

The connector independently fetched each file from that ref. Blob identifiers
and exact bytes were checked against the staged closure. The ref and tree were
rechecked after download. The Mac-native checkpoint fixture from
[the local Git test](../awai154-mac-checkpoint-end-to-end-20261103/fixture.kotoba)
then verified the signed closure and restored a separate Git checkout from the
downloaded blocks. Independent Git inspection matched HEAD, index, worktree,
status and the specified untracked file and mode. An existing destination and
a locally corrupted downloaded block were refused; the corrupted test copy was
restored and reverified.

This is operator-side GitHub transport, not native autonomous archive/fetch.
The fixture's privileged, test-only spawn/path adapter is not suitable for bot
code. The ref uses `itonami/fixture-mac/`, not the native archive's
`itonami/archive/` namespace. Network failure after publication, scoped native
credentials, independent Mac recovery, process-tree control, resource governor,
actual bot data and power-loss durability remain unqualified. No production bot
or unrelated repository state was modified.
