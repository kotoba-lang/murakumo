# Itonami cell placement integration

Tracked in [JUN-288](https://linear.app/kotobalabs/issue/JUN-288), with
[JUN-287](https://linear.app/kotobalabs/issue/JUN-287) and
[JUN-315](https://linear.app/kotobalabs/issue/JUN-315).

The adapter composes tsukasa's declaration and lifecycle contract with
Murakumo placement. No service name grants authority. The node must be
active and declare the requested runtime class; memory, labels, pressure,
freshness and cordon rules still use the existing placement function.
The selected node must separately enforce artifact and capability admission.

## Reproduce the source-unity native gate

Run from this repository. Use clean pinned Git source, rather than a shared
tsukasa checkout's uncommitted files. Choose a new task-owned workspace for
`G`; the example uses the workspace's approved scratch root. Podman must
already have the local `alpine:3` image; `--pull never` refuses a download.

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
TS=/Users/junkawasaki/github/kotoba-lang/tsukasa
G=/Users/junkawasaki/github/workspaces/codex/awai-cell-gate-reproduction
mkdir -p "$G"
git -C "$AMU" show e06dbba75ce7b559c23e3199189f476599973df0:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: d17590b4352b4ecae97fb472355b05cda54cbfc01d3a29ad284531e2342e13d2
shasum -a 256 "$G/root.elf"
{
  echo '(ns itonami-cell-integration-gate (:export [main]))'
  git -C "$TS" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/ledger_core.kotoba |
    sed '1,/test-runtime-classes]))/d'
  git -C "$TS" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/node_state.kotoba |
    sed '1,/test-target test-steps test-legal test-paths]))/d'
  sed '1,/test-fresh test-labels test-eligible test-placement test-scenarios]))/d' kotoba/fleet_placement_core.kotoba
  sed '1,/cell-eligible-reason test-cell-eligibility test-cell-state-path]))/d' kotoba/fleet_cell_placement.kotoba
  cat <<'EOF'
(defn main [] :i64
 (+ (if (and (test-registration) (test-duplicates) (test-transitions) (test-retired-terminal) (test-ledger) (test-audit) (test-runtime-classes)) 1 0)
 (+ (if (and (test-target) (test-steps) (test-legal) (test-paths)) 2 0)
 (+ (if (and (test-fresh) (test-labels) (test-eligible) (test-placement) (test-scenarios)) 4 0)
 (+ (if (test-cell-eligibility) 8 0) (if (test-cell-state-path) 16 0))))))
EOF
} > "$G/gate.kotoba"
sed 's/(schedulable? state)/true/' "$G/gate.kotoba" > "$G/state-bypass.kotoba"
sed 's/(has-runtime-class? runtime-classes runtime-class)/true/' "$G/gate.kotoba" > "$G/class-bypass.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate \
  docker.io/library/alpine:3 sh -c '
    for case_name in gate state-bypass class-bypass; do
      ./root.elf compile /gate/$case_name.kotoba --target aarch64-linux --output /gate/$case_name.elf --format static || exit 1
      chmod +x /gate/$case_name.elf
      KEXE_STRUCTURED_REPORT=1 /gate/$case_name.elf || exit 1
    done'
```

Check the **reported result**, not just exit zero. The result is a bitmask
of five test groups: ledger 1, node state 2, placement 4, cell eligibility 8,
and the composed declaration/state/placement path 16.

Measured on 2026-10-23:

| Source | Required reported result | Meaning |
|---|---|---|
| unchanged implementation | 31 | all five groups pass |
| force active to true | 7 | both integration groups reject the bypass |
| force class-held to true | 23 | runtime-class cases reject the bypass |

All 18 upstream/integration test functions run in the compiled image.
The two mutations must produce the exact lower values; they are not passes
for the implementation. The fixtures cover both classes, absent/unknown
masks, `microvm` and service names, legal admission, stale drain, declaration
change, invalid declaration, recovery and retired-terminal behavior.

Hashes and result record: [receipt](evidence/cell-placement-integration-20261023.json).
No Node/NBB/JVM, hosted compiler, C compiler or C loader runs in this gate.
The shell concatenates source bodies without changing their definitions;
the pinned native root compiles and executes them. This does not prove
project-module linkage, signed input verification, native peer transport,
OCI launch, model inference, Yataverse persistence, settlement or deployment.
