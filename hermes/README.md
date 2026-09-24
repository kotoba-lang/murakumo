# hermes/ — the resident bots that act for this repository

This directory is the **source of truth** for the Hermes profiles listed below
(ADR-2609241200). The host's `~/.hermes/profiles/<profile>` is materialized
from `hermes/profiles/<profile>/` and checked against it:

```
kbb --backend sci scripts/hermes-profile-repo.cljk materialize <profile>   # repo -> host
kbb --backend sci scripts/hermes-profile-repo.cljk check <profile>         # 0 agree / 1 drift / 2 could not compare
kbb --backend sci scripts/hermes-profile-repo.cljk export <profile>        # host -> repo, then commit
```

(run from the com-junkawasaki/root superproject; registry
`manifest/hermes-profile-repos.edn`.)

Each profile directory holds SOUL.md, profile.yaml, config.yaml (host-local
blocks removed), cron/jobs.json (definitions only), scripts/ and the skills the
profile owns. **Never here:** `.env` or any secret value, workspace/ledgers,
sessions, memories, logs, caches, run state.

## Profiles

| profile | description |
|---|---|
| `glm53-cyber` | GLM-5.3-Flash-CYBERSECURITY-W4A16 (dealignai CRACK, W4A16, Modal 2x H200 |
| `mishima-fast` | Low-overhead Mishima profile for short interactive primary-only turns; |
| `murakumo` | murakumo 担当。GPU クラウド(Hyperstack/Modal/runpod 等)、推論サーバー(vLLM 等)、murakumo |
| `murakumo-infer-maturity` | Raises murakumo''s distributed-inference maturity: turns contract-only |
| `murakumo-tok` |  |
| `oriru` | OpenRouter provider-application lifecycle manager for awai network LLC: |
| `qwen38-cyber` | Qwen3.8-Flash-Next-CYBERSECURITY-NVFP4 (dealignai, NVFP4, Modal 1x B200 + GPU |
