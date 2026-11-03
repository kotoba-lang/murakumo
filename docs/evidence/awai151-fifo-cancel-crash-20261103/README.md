# Cancellation crash-boundary observations

On a local ARM64 Mac and the benjamin and levi Mac minis, the same signed
unmodified FIFO candidate and five test-instrumented variants were run with
synthetic queues in unique private task directories. Each instrumented process
was stopped at one write boundary and SIGKILLed by its own test observer.
Before the queue rename, the old queue remained and the ordinary artifact
refused the unresolved intent or pending file. After rename or directory sync,
the ordinary artifact recovered exactly the cancellation; after the intent
was archived there was no intent to recover. In every committed case, the
remaining tickets were subsequently dequeued in their original order. Both
mini task directories were removed and the SSH observations returned cleanly.

On the local Mac, the prior contract, 64-ticket capacity, enqueue crash-boundary
and journal tests also passed against this new source. The old expectation that
non-head removal journals be rejected was updated for the new cancellation
semantics; replacement, duplicate append and two removals remain rejected.
The `stage.py`, `build.py`, `crash-observer.py` and `remote-run.py` scripts are
one-off test fixtures, not production tooling. Adapt their fixed paths before
rerunning. The observer intentionally kills only its own instrumented child.

These observations do not establish power-loss durability, a trusted cancellation
authority, end-to-end process lifetime control, resource limits, cross-host
coordination, or a production governor. No model or existing service was run,
modified or stopped. Compiler and diagnostic packager hashes are in the
[earlier observation](../awai151-fifo-recovery-20261103/README.md).

Source SHA-256 `ce6feba9274c30331a962c0a97ef2ed9e32d4eade0527de9e63a09e752e6a598`;
unmodified signed FIFO `86e1b42c4e49304a0abefb07f9ad1acde096ffd6607749b92a842b26f708c8ba`.
Signed instrumented artifacts: intent
`9f615c6dfdc14aa999fdc789b29f0ad50b3b421638784b800180eb8f97690f1f`;
pending `740615722921152a0bc46a628ae945baef323874cb403a1547d27915173c219e`;
rename `631c9181fc247d7d311fd8038db83b70a079d65a15f6bb1c1f535ed65a374973`;
sync `afa6605b367628385184dbc8e08c45906c639ca211fe7779084b83a57edb417b`;
archive `06601709c9dc4f6c1607c576088f047c54c971cb37423c6ca2794eb7bf46909d`.
