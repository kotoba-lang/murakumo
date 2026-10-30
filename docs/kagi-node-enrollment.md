# Native kagi to Murakumo Node enrollment

JUN-299 / JUN-287. `kotoba/fleet_kagi_node.kotoba` consumes public keyset
proofs and delegated signatures from the native kagi local host. The node
adapter receives **no author, delegate or guardian seed**. This is local
operator enrollment, not distributed peer admission or an active Cell host.

```text
declaration AUTHOR_PUBLIC NODE FAILURE_DOMAIN AVAILABILITY CLASS
register-local EXPORT GENESIS_PIN HEAD_PIN ENVELOPE NODE FAILURE_DOMAIN AVAILABILITY CLASS ADMITTED_DIGEST PRIVATE_LEDGER
status-local EXPORT GENESIS_PIN HEAD_PIN PRIVATE_LEDGER
watch-local EXPORT GENESIS_PIN HEAD_PIN PRIVATE_LEDGER PUBLIC_DIRECTORY SAMPLES_1_TO_720
```

`declaration` validates fields and class mask 1..3, derives strict did:key
from the public author, and emits the exact existing canonical declaration
bytes. The kagi host hashes those bytes, verifies the delegate, signs the
permit domain, commits its replay journal and returns the public envelope.
This envelope has 15 ASCII fields: permit's 13, delegate signature, author
signature, then LF. It is not a raw declaration signature and is never
passed to the old raw-JSON signature verifier as if the domains matched.

Registration independently verifies genesis/head pins, the complete keyset
proof, current policy, role `node`, scope `node:NODE`, audience
`tsukasa:local`, purpose `node-declaration`, sequence, fence, parent and TTL.
It verifies both signatures and compares the permit body digest with the
declaration it constructs itself. The operator's admitted digest is a
separate trusted CLI input; a declaration cannot admit itself.

Only after verification and a final clock/deadline check does an exclusive
private create/fsync write the enrollment. Existing enrollment is never
overwritten. The native tsukasa transitions reach **admitted (1)**; the
adapter intentionally supplies no fresh-heartbeat fact, so it does not
mark the node active/schedulable. A bad proof or mismatch has no ledger
effect. Repeated enrollment refuses and preserves the original record.

Status reopens the protected ledger and revalidates the stored signature
at its recorded acceptance time. An old signature can prove a historical
registration; it cannot authorize a new write after expiry. Current clock
rollback refuses. Watch atomically publishes this result in
`node-status.json`, with an observation timestamp, then sleeps five seconds.
It has a bounded sample budget and stops on an invalid proof or write.

## Native build

`kotoba/assemble_kagi_node.kotoba` is the bounded native source assembler.
It digest-checks the canonical SHA-256/Ed25519, tsukasa, kagi core/proof-reader
and enrollment owners before removing namespace headers. Header scanning
handles quoted strings, comments and UTF-8. It refuses changed owner bytes
and an existing output; there is no host reader/project-link fallback.
The adapter itself is supplied from this repository, and build evidence
records its digest. Assemble and compile with a verified Amu selfhost root
that provides KPV1. A bounded component assembly is not a general native
project linker or installer/CDCI controller.

## Observed boundary

The Mac local-software candidate passed native registration, eight rejected
changes, duplicate refusal and a separate-process proof/ledger restoration.
The adapter's wire-35 roots include public inputs and its own private ledger;
they exclude the signer's seed directory. Read-only watcher containers mount
no seeds and publish only public status files. The static UI reads files the
user selects and does not treat their contents as browser-verified authority.

Remaining integration: signed heartbeat and peer exchange through this kagi
envelope, current operator intents, fence/head governance, the complete
native hata conductor/mon/action codecs, app/service dispatch and Yataverse
restoration. An OCI process, signed permit or node admission is not a Cell.
Independent production key custody remains separately unqualified.


The exact compiler/owner/source/artifact pins are in
`native-kagi-node.lock.json`. Since JUN-591 the compiler is Amu main's
committed `seed/bootstrap/aarch64-linux-static-root.elf`, which is not an
adopted release root. The 13-case receipt and a trace of every process the gate
starts are in `docs/evidence/kagi-node-20261030/`; the 2026-10-24 receipt is the
previous candidate's.

Build, on arm64 Linux (Podman `--platform linux/arm64 --network none`), with
`PATH=/nonexistent KEXE_STACK_BYTES=8388608` and wire 35 scoped to the inputs.
`ROOT` is the pinned compiler; `RUNNER` is Amu's `seed/x86_64-kx64-runner.kotoba`
(`packRunner` in the lock). Each source compiles to an aarch64 payload, which is
packed into an aarch64-linux static image:

```text
ROOT compile SRC --target aarch64-macos --output X.kseed
ROOT compile RUNNER --target aarch64-linux --output X --format packed --payload X.kseed
```

1. Build `kotoba/assemble_kagi_node.kotoba` this way (`nativeAssemblerSha256`).
2. Put the seven owner sources (`ownerSources`, named `<name>.kotoba`) in OWNERS
   and this repository's `kotoba/fleet_kagi_node.kotoba` under REPO, then run
   `KEXE_ALLOW=3,35,37,38,39 assemble OWNERS REPO unity.kotoba`. It prints
   `murakumo.kagi.node.assembly.v1 <sha256>`, which must equal
   `assembledSourceSha256`.
3. Build `unity.kotoba` this way into the node adapter (`artifactSha256`).
   Two builds from the same inputs give the same bytes.

The kagi host is built the same way from kagi's assembled unity source; the
gate receipt records its digest. To repeat with freshly
created local-only keys, run the diagnostic orchestration on arm64 Linux:

```text
python3 scripts/check-native-kagi-node.py --signer /verified/kagi-host \
  --node /verified/node-adapter --output /new/private-fixture-directory
```

The output directory contains new private fixture seeds. Keep it outside
Git, release archives and static hosting. The receipt contains only public
results. Python does not generate keys, sign, validate protocol proofs or
perform enrollment; those operations execute in the native artifacts.
