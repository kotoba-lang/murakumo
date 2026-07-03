# tools/qa — browser QA react loop

Headless-browser (Playwright) verification of the live sites: loads each page,
collects JS/console/network errors, checks required elements + link health,
exercises the join-browser worker flow, and hits the live APIs. Emits a
structured JSON report the fix→redeploy→re-verify loop consumes.

```bash
npm i -g playwright-core   # or use an existing ms-playwright chromium shell
PW_SHELL=<path-to-chrome-headless-shell> node tools/qa/check.mjs
```

Covers: itonami.cloud (cockpit + #compute), app.itonami.cloud/itonami (Compute
console), /join/browser (WebGPU worker — clicks 参加する and confirms it enrolls,
dials wss://relay.gftd.ai, pulls work, earns credits), and the full murakumo/itonami/relay API surface (12 endpoints) with SEMANTIC
asserts — e.g. pay/quote must return chain=base + the Safe address + net=950,
models must list ≥7, funnel must carry granted — so a 200 with the wrong body
(config drift, empty catalog) is caught, not just HTTP failures. This loop caught the join-browser async SyntaxError (fixed 074e56f).
