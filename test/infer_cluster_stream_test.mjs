import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";

const listen = async (server) => {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return server.address().port;
};

test("stream heartbeat bridges a long prefill while ordinary admission still refuses", async (t) => {
  const head = createServer((req, res) => {
    if (req.url === "/health") return void res.end("ok");
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      const body = JSON.parse(Buffer.concat(chunks).toString());
      setTimeout(() => {
        if (body.messages[0].content === "error") {
          res.writeHead(503, { "content-type": "application/json" });
          res.end('{"error":"head unavailable"}');
        } else {
          res.writeHead(200, { "content-type": "text/event-stream" });
          res.end('data: {"choices":[{"delta":{"content":"OK"}}]}\n\ndata: [DONE]\n\n');
        }
      }, body.messages[0].content === "hold" ? 2000 : 800);
    });
  });
  const headPort = await listen(head);
  t.after(() => head.close());
  // Release the ephemeral port before the router binds it.
  // Node's close callback confirms there is no competing listener.
  const probe = createServer();
  const probePort = await listen(probe);
  await new Promise((resolve) => probe.close(resolve));
  const host = `127.0.0.1:${headPort}`;
  const router = spawn("kbb", ["--backend", "sci", "scripts/infer-cluster.cljk",
    "--bind", "127.0.0.1", "--port", String(probePort), "--heads", host,
    "--long-heads", host, "--ultra-heads", host,
    "--ttft-budget-ms", "100", "--heartbeat-ms", "100", "--prior-decode-ms", "50",
    "--prefill-tok-s", `${host}=100`], { stdio: ["ignore", "pipe", "pipe"] });
  let stderr = "";
  router.stderr.on("data", (chunk) => { stderr += chunk; });
  t.after(() => router.kill());
  const url = `http://127.0.0.1:${probePort}/v1/chat/completions`;
  for (let i = 0; i < 80; i++) {
    try {
      const status = await fetch(`http://127.0.0.1:${probePort}/cluster/status`);
      if (status.ok) break;
    } catch { /* router is starting */ }
    if (i === 79) assert.fail(`router did not start: ${stderr}`);
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  const ask = (content, extended) => fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", "x-murakumo-input-tokens": "100",
      ...(extended ? { "x-murakumo-caller-stream": "1", "x-murakumo-ttft-budget-ms": "3000" } : {}) },
    body: JSON.stringify({ model: "mishima", stream: true, max_tokens: 8,
      messages: [{ role: "user", content }] }),
  });
  const refused = await ask("ok", false);
  assert.equal(refused.status, 429);
  assert.equal((await refused.json()).error.code, "mishima_too_long");

  const ordinaryError = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", "x-murakumo-input-tokens": "100",
      "x-murakumo-ttft-budget-ms": "3000" },
    body: JSON.stringify({ model: "mishima", stream: true, max_tokens: 8,
      messages: [{ role: "user", content: "error" }] }),
  });
  assert.equal(ordinaryError.status, 503);

  const started = Date.now();
  const accepted = await ask("ok", true);
  assert.equal(accepted.status, 200);
  assert.ok(Date.now() - started < 700, "SSE headers arrive before the head finishes prefill");
  const answer = await accepted.text();
  assert.match(answer, /^: murakumo waiting\n\n/);
  assert.match(answer, /"content":"OK"/);
  assert.match(answer, /data: \[DONE\]/);

  const failed = await ask("error", true);
  assert.equal(failed.status, 200);
  const failure = await failed.text();
  assert.match(failure, /"code":"mishima_upstream_error"/);
  assert.match(failure, /data: \[DONE\]/);

  // The first request holds the only head beyond the queue estimate. A
  // second request is admitted, then must expire instead of waiting forever.
  const holding = await ask("hold", true);
  const queued = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", "x-murakumo-input-tokens": "1",
      "x-murakumo-caller-stream": "1", "x-murakumo-ttft-budget-ms": "1200" },
    body: JSON.stringify({ model: "mishima", stream: true, max_tokens: 8,
      messages: [{ role: "user", content: "queued" }] }),
  });
  assert.equal(queued.status, 200);
  const expired = await queued.text();
  assert.match(expired, /: murakumo waiting/);
  assert.match(expired, /"code":"mishima_queue_timeout"/);
  assert.doesNotMatch(expired, /"content":"OK"/);
  await holding.text();
});
