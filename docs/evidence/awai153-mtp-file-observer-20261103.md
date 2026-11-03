# MTP file metadata observer

The optional `fleet-model-observe-main` entry performs a single fixed,
read-only SSH `stat` of the known experiment paths for `model.safetensors`
and `mtp.safetensors`. It accepts only two ordered `Regular File` lines,
positive decimal sizes and exact node-specific paths. Missing or partial
output, symlinks, wrong paths and extra lines are refused even if the transport
returns zero. The node name and SSH key paths use the existing restricted
input and pinned-host-key rules.

An Amu-compiled Mac native unit fixture passed valid and malformed inputs,
including a single line, symlink, zero size and extra line. With a task-only
diagnostic process host and pinned host keys, Dan returned both file lines and
Benjamin was refused. The observer reports only remote metadata; it does not
read the weights, verify hashes, follow alternate model locations, establish
ownership, or grant placement. The diagnostic host, stderr handling, remote
response trust, resource admission, actual model execution and fleet runtime
remain unqualified. A subsequent separate SHA-256 and artifact-provenance
check is needed before using the files.

| Artifact | SHA-256 |
| --- | --- |
| observer source | `24ca924b56696c7fa2055f488442b89b9b74b0433ccd487d10784cde696fcdb0` |
| unit source | `a5ebdf3544cb584a911f466dc341706e66ccac8a5740c5e52689868e3e3eec4e` |
| signed unit fixture | `5f354f5e93a78490ece1632fb7d695432ce13524783f4e1e9443f5a0ee9a2567` |
| real-host fixture source | `3602de7ff0d1baefa1f8d8b61b5438975b130287706ffb52c42ed3830769eb4a` |
| signed real-host fixture | `688536fe59d5a2a98a0c2ee745be8ccc5f9a4cf7e61ba36c2aab8f0edeb21b28` |
