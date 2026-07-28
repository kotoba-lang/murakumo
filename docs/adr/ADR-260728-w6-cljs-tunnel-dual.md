# ADR-260728: cljs dual-source for tunnel pure helpers

Status: accepted after token/kekkai cljs dual-source (#124)

## Decision

Dual-source `murakumo.tunnel` pure string/i64 helpers when
`oracle/ready? :tunnel` (JVM classpath or cljs/nbb resource load):

| surface | kotoba export(s) |
|---|---|
| defaults | `default-connect-timeout-s`, `default-control-persist-s`, `rc-marker` |
| conn-opts fragments | `batch-mode-opt`, `connect-timeout-opt`, `strict-host-key-opt`, `control-*` |
| wrap-cmd | `wrap-cmd` |
| parse-rc classify | `marker-prefix?`, `strip-marker-digits`, `parse-digits` |
| argv helpers | `scp-dest`, `close-master-control-opt` |
| forward/curl shells | `ensure-forward-command`, `replace-forward-command`, `remote-curl-command` |

Host remains: line-split of stdout, argv vector concat, SSH/SCP subprocess.

### cljs KIR fallback

Exports that build strings from i64 (`connect-timeout-opt`, `control-persist-opt`,
forward cmds) or substring indices (`marker-prefix?`, `strip-marker-digits`,
`parse-digits`) may throw on some cljs kir builds (BigInt substring bounds).
Host uses `try-oracle` → mirror (same pattern as token #124 / fleet health-url).

### Related prior

- #122 cljs oracle load + task.failed? + fleet.inventory
- #123 dash.state pure helpers dual-source
- #124 token pure + kekkai.gate dual-source

### Still incremental

- config / report / persist / remaining cljc pure hosts on cljs
- full ops.cljs shell rewrite

## Evidence

- tunnel unit + kotoba parity green on JVM
- nbb smoke: `ready? :tunnel` + wrap-cmd / parse-rc / conn-opts / scp-argv

## Related

- inventory Next: incremental cljs host rewire
