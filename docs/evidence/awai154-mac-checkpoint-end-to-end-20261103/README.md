# Local Mac Git checkpoint diagnostic

The [fixture](fixture.kotoba) composes the current checkpoint component with
its normal SHA, signature, CID and JSON dependencies and the candidate Darwin
FS2 runtime. All 56 other checkpoint functions are unchanged; `cp-spawn` alone
is replaced by a **test-only, privileged** adapter. That adapter runs local
Git through the private host API and maps `/fixture3/` paths to a disposable
directory. It is not a production implementation, guest security boundary or
general path-mapping policy. The test used a public synthetic all-zero seed.

On one Mac, the [receipt](receipt.json) records actual local Git capture,
signed checkpoint verification and restore into a separate directory. An
independent host Git inspection matched HEAD, index, working-tree content,
status, and the listed untracked file and its mode. Wrong policy, an existing
destination and a corrupted block were refused; the test restored the modified
fixture block and reverified it. The [recovery test](recovery.json) created and
verified a local signed successor and refused a wrong epoch.

The old implementation's [control](previous-exit-control.json) returned exit
0 for the wrong policy, existing destination and corrupted block. The current
fixture returns exit 1 for those refusals and zero for completed capture,
verification, restore, closure and recovery. Git stderr in the fixture includes
expected untracked-file checks and local bundle fetch output; names and result
text, not exit code alone, were inspected.

No GitHub transaction, production credential, independent node, bot, MTP
inference, host process-tree governor, hostile concurrent mutation or
power-loss durability was tested. The private host service is privileged;
never expose this fixture adapter to untrusted code. The compiler and runtime
are diagnostic artifacts, not a qualified release closure.
