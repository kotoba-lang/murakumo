# GitHub checkpoint path isolation observation

The archive input gate now rejects lexical overlap between work and the source
store, and the restore gate rejects overlap between work and destination. The
common absolute path predicate also rejects trailing slash, duplicate slash,
`/./` and terminal `/.`; this affects every checkpoint caller using it. The
host must still prevent symlink, mount and hard-link aliases and concurrent
filesystem changes. The check is not an authority boundary by itself.

On one local ARM64 Mac, the fixture built from the actual path predicate and
archive/restore input conditions returned 0. It checks equal, ancestor,
descendant, noncanonical aliases and separate paths with common prefixes.
`mutant.kotoba` replaces overlap with equality and returned 1;
`alias-mutant.kotoba` removes the canonical spelling checks and returned 1.
The fixture stubs the remaining input checks and performs no Git, filesystem,
GitHub, secret, lease or bot operations. The two mutants are negative controls,
not production artifacts. Reproduction requires adapting the temporary paths
in `generate.py`; it copies the exact condition source before stubbing.

Source SHA-256: `git_checkpoint.kotoba`
`ee42374bb0426c44a9210fc2e26b8bc80a3d31f3946a394ba2cb083ec332ebae`;
`github_checkpoint_archive.kotoba`
`174365a4663146de34b34f93625632b1d1a814009b30d6dca34efe15cac6c034`;
`fleet_node_enrollment.kotoba`
`514652719718a77b078230efe8ca74e1473905dc8a4cde4656c790f1662af7e1`.
The local Mac bootstrap compiler hash was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and diagnostic FS2 packager hash was
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
Signed fixture, overlap mutant and alias mutant hashes were respectively
`67bf25da83a4ea717af97d52076814911e81dacb9fc9eb6d889fe80f85abaa36`,
`86810a2b7988a100a83b0e3d6854bce14dddeade360209637425ec2f67521171`,
`71923569e096d20cb2a891044c1b0066225bc36f50798b8aaf01cbf381d66f98`.
This isolated test does not qualify the full archive component or Mac runtime.
