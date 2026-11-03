# Bound resident response reads

The response peer controls the output file size. `pr-read-response` checks the
FS2 metadata for a regular file between 1 and 262,144 bytes, requests exactly
that many bytes via FS2 E, and checks the returned length and size again. The
wait, final processing and pending-publication recovery paths use this helper
instead of FS2 A. An invalid or changed size produces an empty value and the
existing validation refuses the response. This intentionally rejects larger
legitimate responses; compatibility with actual MTP workloads is not yet
established.

On one ARM64 Mac, a native fixture copied the actual helper and the checkpoint
FS2 metadata/range helpers from this revision. It used a diagnostic scoped
Darwin FS2 provider and task-owned sparse regular files: 1,024 and 262,144 bytes
were returned at full length; zero, 262,145, 8,388,608, absent and directory
cases returned zero bytes. A control with the previous whole-file FS2 A read
returned 262,145 bytes for the over-limit file. The assembled source slice for
`pr-step-range` and its selected historical dependency closure passed the Amu
source check; it was not an end-to-end resident execution. The fixture and
control are diagnostic, not production dependencies.

The cache must be private and exclusively owned: separate metadata and range
operations do not prove stable file identity or exclude same-size replacement.
This change bounds a single response read, not curl's disk writes, total
storage, process-tree resources, concurrent jobs, or other inputs read using
FS2 A. FS2 behavior and current closure on the production controller, real TLS,
MTP and GitHub paths, and the complete governor remain unqualified. The Mac
test used synthetic files and did not run inference.

- Recovery source SHA-256: `77faa01d2142c2b071382ca2728a6bbf831ea6b827b72d63dd5cf102c5b88e6f`
- Fixture source: `afbe8d86ffa96ec104ac63b610373f5913bf72d0f6a1a31d2917664e03bafcdd`; signed binary: `5990aab117d2223aacdd75939389804f83f3fcaf31e39ec81057749cf11b1bb1`
- Control source: `d8bb1a3bdeba27c742b74dc83b40f679030c4f7c79f72792501f459406078559`; signed binary: `8cc0e6ecccb69540cb63f11dd2200e91f6d41e10ae6604c935edd9aa13c2e1a9`
- Diagnostic FS2 packager: `f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`; bootstrap compiler: `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`.

## Linux ARM64 diagnostic on Xavier

A subsequent isolated test on Xavier (Linux 5.10.120-tegra, ARM64) compiled
this same fixture to a native static ELF using a fixed diagnostic compiler,
then ran it with an explicit FS2 directory grant and task-owned sparse files.
Empty, 262,145-byte, 8,388,608-byte, absent and directory cases returned zero;
1,024 and 262,144-byte files returned their full lengths. All fixture cases
exited zero. A separate read-only syscall probe on this host reported
`openat2` available. Each compiler and fixture invocation had a 5-second CPU
limit, a wall timeout, `RLIMIT_CORE=0` and a 16 MiB file-size limit. The test
used a temporary directory and confirmed it was removed. SSH verified the
host key advertised by the live Tailscale peer record via a temporary
known-hosts file.

Compiler SHA-256 `914b828a24ced086e09929894bb64806dea103bf79766ef144001a076717b1c4`;
generated ELF `7697723601d2d1b27c91dc06320a9d9b00efcb61304527b337e6e1e3d4492581`;
observer `dc2c491cfc1946b434265dae4c1f12971e52d183b8a39f1e4f4238ed7e53dc9a`.
This was the diagnostic compiler/runtime, not proof of the production
resident artifact or its full dependency closure. It did not run inference,
exercise curl's output writing, test concurrent mutation, or qualify resource
governance and deployment. The test-only observer includes local fixture and
compiler paths and requires adaptation elsewhere.
