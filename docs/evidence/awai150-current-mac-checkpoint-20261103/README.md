# Current checkpoint source: local Mac Git round trip

The fixture composes `git_checkpoint.kotoba` from Murakumo `f4d382e` with the
same native SHA, signature, CID, JSON and host helpers as the prior
[Mac checkpoint fixture](../awai154-mac-checkpoint-end-to-end-20261103/).
Only `cp-spawn` is substituted with that fixture's privileged, test-only
adapter mapping `/fixture4/` to a disposable directory. It is not a general
path mapping, guest sandbox or production spawn capability. The synthetic
signing seed is all zeros and is not a real key.

The pinned Linux ARM64 bootstrap compiled the fixture for `aarch64-macos` in a
new 1-CPU, 2-GiB VM, and the diagnostic Darwin packager from Amu #1796
`b490eaaf` emitted the Mac image. The VM was removed after compilation. On
one Mac with local Git, [results.json](results.json) records real capture,
signature/closure verification and restore. Independent Git and filesystem
inspection matched HEAD, status, staged and unstaged contents, the explicitly
listed untracked file and its mode. Wrong policy, existing destination,
corrupted block, unknown command and missing recovery were refused. A separate
[permission test](observe-denied.py) restored to a path under an inaccessible
owned directory: OS stat confirmed EACCES and the checkpoint returned
`checkpoint/destination-exists` without creating a destination.

The first full test completed its checks but its receipt writer referenced a
wrong local path. The disposable fixture was recreated, the writer corrected,
and the complete test was rerun to produce the included receipt. `setup.py`
and observers only modify task-owned synthetic paths; their absolute paths must
be adapted for a rerun. The observer bounds each guest to five CPU seconds.

Source SHA-256 `e8e524fc95731cc57b29ae7175c02be40b676103b2b8768ab5b08fa7141f08c3`;
assembled fixture `099977c3c2d6088910beaca9abd0032daf89386e37ca95f7082cb2be7172fb49`;
raw generated main `6a86d84610d9a6edc120180e23f27dd74fd485fbe4bd861580e2bee77a1bc5ff`;
signed Mach-O `b4c2b7eb213f500251cac910117b28023f0aa8f2f7dc49bf675aa349ea7a5929`;
Linux bootstrap `914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`;
diagnostic Darwin packager `4114499dc10bd321070a221f88847b8708565f7d80e87c4ed84b28e599e3c41e`.

This is not a native autonomous GitHub archive, real bot turn, MTP test,
production credential or process-tree governor. It does not test concurrent
hostile path mutation, power loss, other Mac minis or a release runtime. The
permission check covers one failure mode and does not certify every caller.
