# Current checkpoint: local native Git diagnostic

On 2026-11-04, `scripts/native/check-checkpoint-git-fixture.py` compiled and
ran the current checkpoint source on jacob, macOS 26.3.1 (a), build
25D771280a. The source component SHA-256 was
`ee6996b5919b1e266dc19edbe5193fa1510bfdb2360a30e3d8ea4117cd909478`;
the pinned prior fixture template was
`a76f3ae0bf449b21c81630496a0e7edc62d14422c45cc2710d2fa1e4e09a0f8a`.
The generated fixture SHA-256 for the recorded run was
`f997dc14994c95cfa6a8625f8f2335815c2c37aa79166743aaaa9e8e472301f1`;
it includes the randomly selected temporary directory and changes on rerun.
All 67 component functions were composed, with `cp-spawn` replaced by the
prior privileged, test-only Git adapter. The generator checks that every
other function body matches the component source.

The native diagnostic captured and verified a synthetic local Git checkpoint,
restored it into a new directory, and independently matched HEAD, index,
working tree, untracked file and mode. Wrong policy, existing destination and
a corrupted block were refused; the restored block then verified and closure
completed. Both original and restored snapshots remained unchanged. The
privately owned temporary directory was removed by the harness.

The strictly signature-verified diagnostic compiler SHA-256 was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
the diagnostic FS2 packager SHA-256 was
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
These are the diagnostic inputs recorded in the prior local checkpoint
fixture; they are not a qualified release closure. Generator SHA-256:
`1a4da20436563d6e712a13886ba8c52a9ae780eb2412a6df23a590d6b5d8c92a`;
harness SHA-256:
`2415c641bb05e4d7c6066d617fb379b59d028d0a0357225fa3d147336a60d968`.

This test invokes local Git using a privileged adapter and a public synthetic
seed. It is not a production isolation boundary and must not be used with
untrusted inputs. It does not exercise GitHub, bot inference, a governor,
concurrent hostile changes, faults, power loss, a production provider, a
release compiler, or CI; it does not qualify deployment.
