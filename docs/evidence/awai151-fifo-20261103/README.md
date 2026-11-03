# FIFO component observation

For the source-unity test, replace the module ns form with
`(ns fifo-gate (:export [main]))`, append
`(defn main [] :i64 (resource-fifo-main))`, and compile with the pinned Amu
root in `receipt.json` for `aarch64-linux --format static`. In an isolated VM
place the artifact as `fifo.elf` and this observer at
`/private/tmp/awai151-fifo-gate/`; the observer refuses to reuse its synthetic
state directory. It invokes the real artifact and filesystem capability with
nonsecret test tickets. The tested source and artifact hashes are pinned in the
receipt. A separate mutant replaces the pending/intent preflight condition in
`rq-apply` with `false`; the observer fails on the named pending refusal.

This is not a crash test or a complete governor qualification. Read
[the component's constraints](../../native-resource-fifo.md) before use.
