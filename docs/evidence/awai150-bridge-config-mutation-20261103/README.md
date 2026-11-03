# Bridge config mutation after lock

A small native source-unity fixture used the current actual `br-command` and
standalone bridge entry point. After the real local flock was acquired, a
test-only injected FS2 write changed a private config file from `original` to
`replacement` before the command ran. The filesystem read/write and lock were
real; config decoding, schedule, admission and other domain functions were
stubs. The current bridge passed the original bytes to the command and printed
`fixture/original`. A mutant which reread config after locking printed
`fixture/replacement`. Both returned the expected test refusal exit 1, and the
observer independently confirmed the file contained `replacement`.

This was a deterministic interleaving in one local process, not an independent
concurrent writer or atomic rename; it demonstrates the identified mismatch
for this fixture, not all config races. No production config, bot, checkpoint,
GitHub or model was touched. Lock inode and directory protection, runtime
qualification and multi-host fencing remain host responsibilities.

Fixture SHA-256 `421d3a7aeb847a99d0390290746ed980f32786c93ce5cd20b72571080a1f8b54`;
mutant `882c651d9ff333360094656e7884a64281e0dafc476a87e072761d601ff02c4c`;
signed fixture `3a38f057cc3f743393cfdfb2511fba87839e395c084e376bd6817e0976ba130e`;
signed mutant `b0db60e31320d01c1b124f7228ec555c9f7769326e0375ca968ea8abb4dc4ce3`.
The compiler and diagnostic host are those of the earlier
[bridge lock observation](../awai150-standalone-bridge-lock-20261103/).
