# ADR-260728: W6 product-shell oracle authority — tunnel + config pure paths

Status: accepted after bulk product-shell catalog (#99)

## Decision

Wire pure string/path helpers of two catalog-only cores to kotoba SSoT:

| catalog id | host | SSoT / KIR |
|---|---|---|
| `:tunnel` | `murakumo.tunnel` | `kotoba/tunnel_core.kotoba` → `tunnel_core.kir.edn` |
| `:config` | `murakumo.config` | `kotoba/config_core.kotoba` → `config_core.kir.edn` |

### tunnel (JVM)

Delegates: defaults (`default-connect-timeout-s`, `default-control-persist-s`,
`rc-marker`), `conn-opts` / `ssh-opts` fragments (`batch-mode-opt`,
`connect-timeout-opt`, `strict-host-key-opt`, `control-*`), `wrap-cmd`,
`parse-rc` marker/digits (`marker-prefix?`, `strip-marker-digits`,
`parse-digits`), `scp-dest`, `close-master-control-opt`,
`ensure-forward-command` / `replace-forward-command` / `remote-curl-command`.

### config (JVM)

Delegates: default path constants, `default-kotoba-dir` / `kotoba-dir-from`,
pinned/release/wit path builders, `kotoba-bin` / `resolve-local-bin` /
`resolve-wit-dir`, `peers-path` / `launchd-template-path` / `build-manifest-path`.

### Still host

- tunnel: line-split of stdout, argv vector concat, SSH/SCP subprocess
- config: EDN parse/IO, env map folds, filesystem existence probes, operator-seed
- cljs host-mirrors for both

## Evidence

- authority tests for tunnel + config (API + live-compile parity + KIR drift)
- existing `tunnel_kotoba_parity_test` / `config_kotoba_parity_test` / unit tests

## Related

- inventory Next: incremental host wiring after #99 bulk catalog
- murakumo#86–#99 product-shell pattern
