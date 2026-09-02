#!/usr/bin/env node
// Load emitted web guests and call their exports.
// Does not reimplement admission predicates. There is no nbb host copy.
//
// kotoba run <entry>.kotoba on the Release CLI rejects this language's
// typed forms (string=?, record-get, …) with kotoba/runtime-rejected.
// Guest execution is instantiateKotoba on the kotoba compile --target web
// artifact. kotoba run <artifact> returns host-action adapter-required
// (planned), not a result — that is not treated as a guest run.

import { pathToFileURL } from "node:url";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

const entries = [
  { name: "desired", file: "target/kotoba/desired.mjs", runArg: "publish", runExpect: 11n },
  { name: "factory", file: "target/kotoba/factory.mjs", runArg: "issue", runExpect: 11n },
  { name: "quic_driver", file: "target/kotoba/quic_driver.mjs", runArg: "serve", runExpect: 90n },
  { name: "quic_cert", file: "target/kotoba/quic_cert.mjs", runArg: "ensure", runExpect: 90n },
];

let failed = 0;
for (const e of entries) {
  const abs = resolve(e.file);
  if (!existsSync(abs)) {
    console.error(`missing emitted guest ${e.file} — run scripts/kotoba-compile.sh first`);
    failed += 1;
    continue;
  }
  const mod = await import(pathToFileURL(abs).href);
  if (typeof mod.instantiateKotoba !== "function") {
    console.error(`${e.name}: no instantiateKotoba export`);
    failed += 1;
    continue;
  }
  const guest = mod.instantiateKotoba({});
  const main = guest.main();
  if (main === 0n || main === 0) {
    console.error(`${e.name}: guest main stayed 0`);
    failed += 1;
    continue;
  }
  const run = guest.run(e.runArg);
  if (run !== e.runExpect) {
    console.error(`${e.name}: guest run(${e.runArg}) => ${run}, expected ${e.runExpect}`);
    failed += 1;
    continue;
  }
  console.log(`${e.name}: instantiateKotoba main=${main} run(${e.runArg})=${run}`);
}

if (failed !== 0) {
  process.exit(1);
}
