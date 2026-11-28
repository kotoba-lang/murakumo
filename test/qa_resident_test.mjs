import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { chmod, mkdir, mkdtemp, readdir, realpath, rename, rm, symlink, unlink, writeFile } from 'node:fs/promises';
import { createServer, request } from 'node:http';
import { homedir, tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const source = process.env.MURAKUMO_TEST_SOURCE === '1';
const host = '127.0.0.1';
const path = '/v1/chat/completions';
const model = 'synthetic-model';
const marker = 'synthetic-qa-request-private-marker';
const chat = { model, messages: [{ role: 'user', content: marker }], max_tokens: 16 };
const origin = port => `http://${host}:${port}`;
const bearer = () => randomBytes(32).toString('hex');
const verifier = token => ({
  'token-sha256': createHash('sha256').update(token, 'ascii').digest('hex'),
  'expires-at-ms': Date.now() + 300_000,
});

async function listen(handler) {
  const server = createServer(handler).listen(0, host);
  await once(server, 'listening');
  return server;
}

async function close(server) {
  await new Promise(resolve => {
    server.close(resolve);
    server.closeAllConnections();
  });
}

async function freePort() {
  const server = await listen();
  const { port } = server.address();
  await close(server);
  return port;
}

async function fixture(t, parent = tmpdir()) {
  const dir = await mkdtemp(join(parent, 'murakumo-qa-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

async function launch(t, dir, command, config, { clock = false, socketOnly } = {}) {
  const file = join(dir, `${command}.json`);
  await writeFile(file, JSON.stringify(config), { mode: 0o600 });
  const args = [...(source
    ? ['--backend', 'sci', '--classpath', join(root, 'src'), join(root, 'scripts/node-cli.cljc'), 'resident']
    : [join(root, 'release/node.mjs'), 'resident']), command, '--config', file];
  const env = { ...process.env };
  if (source) env.NODE_PATH = [join(root, 'release/node_modules'), env.NODE_PATH].filter(Boolean).join(delimiter);
  if (clock) {
    const mock = join(dir, 'fixture-clock.mjs');
    await writeFile(mock, `
      const wall = Date.now.bind(Date);
      let offset = 0;
      Date.now = () => wall() + offset;
      process.on('message', ms => { offset = ms; process.send('clock-set'); });
    `);
    env.NODE_OPTIONS = `--import="${mock}"`;
  }
  if (socketOnly) {
    const probe = join(dir, 'fixture-socket-probe.mjs');
    await writeFile(probe, `
      import { Socket } from 'node:net';
      const connect = Socket.prototype.connect;
      Socket.prototype.connect = function (...args) {
        const options = Array.isArray(args[0]) ? args[0][0] : args[0];
        if (options?.path !== ${JSON.stringify(socketOnly)}) {
          process.stderr.write('fixture refused non-pinned outbound dial\\n');
          throw new Error('fixture refused non-pinned outbound dial');
        }
        return connect.apply(this, args);
      };
    `);
    env.NODE_OPTIONS = `--import="${probe}"`;
  }
  const child = spawn(source ? 'kbb' : process.execPath, args, {
    cwd: dir, env, stdio: ['ignore', 'pipe', 'pipe', ...(clock ? ['ipc'] : [])],
  });
  let stdout = '', stderr = '';
  child.stdout.on('data', data => { stdout += data; });
  child.stderr.on('data', data => { stderr += data; });
  const done = once(child, 'close');
  const stop = () => { child.kill('SIGTERM'); return done; };
  t.after(stop);
  return { child, done, stop, stdout: () => stdout, output: () => stdout + stderr };
}

async function waitFor(check, running) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await check()) return;
    assert.equal(running.child.exitCode, null, 'fixture resident exited before readiness');
    await delay(50);
  }
  assert.fail('fixture resident readiness timed out');
}

async function startQa(t, dir, config, options) {
  const running = await launch(t, dir, 'serve-qa', config, options);
  await waitFor(() => running.stdout().includes('"mode":"serve-qa"'), running);
  assert.deepEqual(JSON.parse(running.stdout()), {
    mode: 'serve-qa', listeners: [{ bind: host, port: config.port }],
  });
  return running;
}

function send(port, token, body = chat, url = path) {
  return fetch(origin(port) + url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }) },
    body: JSON.stringify(body), signal: AbortSignal.timeout(5000),
  });
}

async function expectError(response, status, error) {
  assert.equal(response.status, status);
  assert.deepEqual(await response.json(), { error });
}

function duplicateAuth(port, token, interveningHeaders = []) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(chat);
    const req = request(origin(port) + path, {
      method: 'POST', signal: AbortSignal.timeout(5000),
      headers: ['Host', `${host}:${port}`, 'Content-Type', 'application/json', 'Content-Length', Buffer.byteLength(body),
        'Authorization', `Bearer ${token}`, ...interveningHeaders, 'authorization', `Bearer ${token}`],
    }, res => { res.resume(); resolve(res.statusCode); });
    req.on('error', reject);
    req.end(body);
  });
}

test('dedicated resident isolates auth and state, forwards tools, schema and live SSE, and fails closed',
  { timeout: 30_000 }, async t => {
    const dir = await fixture(t);
    const qaDir = join(dir, 'qa');
    const fleetDir = join(dir, 'fleet');
    await Promise.all([mkdir(qaDir), mkdir(fleetDir)]);
    const token = bearer(), fleetToken = bearer(), nextToken = bearer();
    const seen = [];
    let redirected = 0, finishSse;
    const trap = await listen((_req, res) => { redirected++; res.end('{}'); });
    t.after(() => close(trap));
    const toolCall = {
      id: 'call_fixture', type: 'function', function: { name: 'lookup', arguments: '{"key":"demo"}' },
    };
    const firstChunk = 'data: {"choices":[{"delta":{"content":"hello"}}]}\n\n';
    const lastChunks = 'data: {"choices":[],"usage":{"total_tokens":5}}\n\ndata: [DONE]\n\n';
    const backend = await listen(async (req, res) => {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks));
      seen.push({ method: req.method, url: req.url, authPresent: 'authorization' in req.headers, body });
      if (body.messages[0].content === 'redirect') {
        res.writeHead(307, { location: origin(trap.address().port) + path });
        return res.end();
      }
      if (body.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write(firstChunk);
        finishSse = () => res.end(lastChunks);
        return;
      }
      const message = body.tools && body.messages.at(-1).role !== 'tool'
        ? { role: 'assistant', content: null, tool_calls: [toolCall] }
        : { role: 'assistant', content: '{"answer":"ok"}' };
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ choices: [{ message }], usage: { total_tokens: 5 } }));
    });
    t.after(() => close(backend));
    const port = await freePort();
    const config = { bind: host, port, model, backend: origin(backend.address().port), auth: verifier(token) };
    const qa = await startQa(t, qaDir, config);
    assert.equal(seen.length, 0, 'QA startup did not contact the backend');
    assert.deepEqual(await readdir(qaDir), ['serve-qa.json']);

    await expectError(await send(port), 401, 'unauthorized');
    await expectError(await send(port, 'malformed'), 401, 'unauthorized');
    await expectError(await send(port, bearer()), 401, 'unauthorized');
    await expectError(await send(port, fleetToken), 401, 'unauthorized');
    assert.equal(await duplicateAuth(port, token), 401);
    assert.equal(await duplicateAuth(port, token, Array(2200).fill(['x', 'a']).flat()), 401);
    await expectError(await send(port, token, chat, '/execute/text'), 404, 'not_found');
    await expectError(await fetch(origin(port) + '/health'), 404, 'not_found');
    await expectError(await send(port, token, { ...chat, model: 'other-model' }), 422, 'qa_model_mismatch');
    assert.equal(seen.length, 0, 'negative ingress requests did not dispatch');

    const invalidMessages = [
      ['non-object', [false]],
      ['empty array', []],
      ['unknown role', [{ role: 'function', content: 'legacy' }]],
      ['user without text', [{ role: 'user' }]],
      ['null developer text', [{ role: 'developer', content: null }]],
      ['nontext part', [{ role: 'user', content: [{ type: 'image_url', image_url: {} }] }]],
      ['malformed text part', [{ role: 'system', content: [{ type: 'text', text: 17 }] }]],
      ['empty parts', [{ role: 'user', content: [] }]],
      ['invalid name', [{ role: 'user', content: 'hello', name: 17 }]],
      ['tool calls on user', [{ role: 'user', content: 'hello', tool_calls: [toolCall] }]],
      ['assistant without content or calls', [{ role: 'assistant' }]],
      ['assistant null content without calls', [{ role: 'assistant', content: null }]],
      ['assistant empty text without calls', [{ role: 'assistant', content: '' }]],
      ['assistant blank parts without calls', [{ role: 'assistant', content: [{ type: 'text', text: ' ' }] }]],
      ['assistant empty calls', [{ role: 'assistant', content: null, tool_calls: [] }]],
      ['assistant call without id', [{ role: 'assistant', tool_calls: [{ ...toolCall, id: '' }] }]],
      ['assistant call with nonstring arguments', [{ role: 'assistant', content: 'hello',
        tool_calls: [{ ...toolCall, function: { name: 'lookup', arguments: {} } }] }]],
      ['tool result without a call id', [{ role: 'tool', content: 'result' }]],
      ['tool result without text', [{ role: 'tool', tool_call_id: toolCall.id, content: null }]],
    ];
    for (const [label, messages] of invalidMessages) {
      await expectError(await send(port, token, { ...chat, messages }), 400, 'qa_text_messages_required');
      assert.equal(seen.length, 0, `${label} must not dispatch`);
    }

    const tools = [{ type: 'function', function: { name: 'lookup', parameters: { type: 'object' } } }];
    const toolRequest = { ...chat, tools, messages: [
      { role: 'system', content: 'Synthetic instructions' },
      { role: 'developer', content: [{ type: 'text', text: 'Use the fixture tool' }] },
      ...chat.messages,
    ] };
    const first = (await (await send(port, token, toolRequest)).json()).choices[0].message;
    assert.deepEqual(first.tool_calls, [toolCall]);
    const response_format = { type: 'json_schema', json_schema: {
      name: 'fixture', schema: { type: 'object', properties: { answer: { type: 'string' } } },
    } };
    const toolResult = { ...toolRequest, response_format,
      messages: [...toolRequest.messages, first, { role: 'tool', tool_call_id: first.tool_calls[0].id, content: 'ok' }] };
    const result = await (await send(port, token, toolResult)).json();
    assert.deepEqual(JSON.parse(result.choices[0].message.content), { answer: 'ok' });
    assert.equal(result.usage.total_tokens, 5);
    assert.deepEqual(seen.map(call => call.body), [toolRequest, toolResult]);

    const streaming = { ...chat, stream: true, stream_options: { include_usage: true } };
    const sse = await send(port, token, streaming);
    assert.equal(sse.headers.get('content-type'), 'text/event-stream');
    const reader = sse.body.getReader();
    const decoder = new TextDecoder();
    let body = '';
    while (body.length < firstChunk.length) {
      const chunk = await reader.read();
      assert.equal(chunk.done, false);
      body += decoder.decode(chunk.value, { stream: true });
    }
    assert.equal(body, firstChunk, 'the first SSE frame arrived while the backend stream was open');
    await expectError(await send(port, token), 429, 'qa_busy');
    finishSse();
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      body += decoder.decode(chunk.value, { stream: true });
    }
    assert.equal(body, firstChunk + lastChunks);
    assert.deepEqual(seen[2].body, streaming);

    const redirect = await send(port, token, { ...chat, messages: [{ role: 'user', content: 'redirect' }] });
    await expectError(redirect, 502, 'qa_backend_response');
    assert.equal(redirect.headers.has('location'), false);
    assert.equal(redirected, 0);
    assert.equal(seen.length, 4);
    assert.ok(seen.every(call => call.method === 'POST' && call.url === path && !call.authPresent),
      'only pinned chat requests were sent and caller authorization was stripped');

    // Run an independent fleet fixture while QA remains live.
    const fleetBackend = await listen((req, res) => {
      res.setHeader('content-type', 'application/json');
      req.resume();
      res.end(req.url === '/slots' ? '[]' : '{"choices":[{"message":{"content":"5"}}]}');
    });
    t.after(() => close(fleetBackend));
    const fleetPort = await freePort();
    const tokenFile = join(fleetDir, 'synthetic-fleet-token');
    await writeFile(tokenFile, fleetToken, { mode: 0o600 });
    const fleet = await launch(t, fleetDir, 'serve', {
      node: 'synthetic-fleet', bind: host, port: fleetPort, 'token-file': tokenFile,
      'state-file': join(fleetDir, 'receipts.json'), lanes: { text: {
        kind: 'text', model, group: 'fixture', context: 1024,
        backend: origin(fleetBackend.address().port), path,
      } },
    });
    const health = credential => fetch(origin(fleetPort) + '/health', { headers: {
      authorization: `Bearer ${credential}`,
    }, signal: AbortSignal.timeout(1000) });
    await waitFor(async () => (await health(fleetToken).catch(() => null))?.status === 200, fleet);
    assert.equal((await send(fleetPort, token, chat, '/execute/text')).status, 401);
    assert.equal((await health(fleetToken)).status, 200);

    // Replace the digest and restart: neither the old QA bearer nor the fleet bearer is valid.
    await qa.stop();
    const rotated = await startQa(t, qaDir, { ...config, auth: verifier(nextToken) });
    await expectError(await send(port, token), 401, 'unauthorized');
    await expectError(await send(port, fleetToken), 401, 'unauthorized');
    assert.equal(seen.length, 4);
    assert.equal((await send(port, nextToken)).status, 200);
    assert.equal(seen.length, 5);

    await close(backend);
    await expectError(await send(port, nextToken), 502, 'qa_backend_unavailable');
    assert.equal(redirected, 0);
    assert.equal((await health(fleetToken)).status, 200);
    assert.deepEqual(await readdir(qaDir), ['serve-qa.json']);
    await rotated.stop();
    for (const value of [marker, token, nextToken, fleetToken, config.auth['token-sha256']]) {
      assert.ok(!(qa.output() + rotated.output()).includes(value), 'QA logs exposed private fixture data');
    }
  });

test('dedicated credential expires on wall time and still expires after clock rollback',
  { timeout: 20_000 }, async t => {
    const dir = await fixture(t);
    const token = bearer();
    let calls = 0;
    const backend = await listen((req, res) => {
      calls++;
      req.resume();
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{}');
    });
    t.after(() => close(backend));
    const port = await freePort();
    const deadline = Date.now() + 4000;
    const qa = await startQa(t, dir, { bind: host, port, model, backend: origin(backend.address().port),
      auth: { ...verifier(token), 'expires-at-ms': deadline } }, { clock: true });
    const setClock = async offset => {
      const confirmation = once(qa.child, 'message');
      qa.child.send(offset);
      const [message] = await confirmation;
      assert.equal(message, 'clock-set');
    };
    assert.equal((await send(port, token)).status, 200);
    await setClock(60_000);
    await expectError(await send(port, token), 401, 'unauthorized');
    await setClock(-60_000);
    assert.equal((await send(port, token)).status, 200);
    await delay(Math.max(0, deadline - Date.now()) + 30);
    await expectError(await send(port, token), 401, 'unauthorized');
    assert.equal(calls, 2, 'expired credentials did not dispatch');
  });

test('invalid QA configuration exits with a diagnostic before listening', { timeout: 30_000 }, async t => {
  const dir = await fixture(t);
  const port = await freePort();
  const config = { bind: host, port, model, backend: origin(await freePort()), auth: verifier(bearer()) };
  for (const [patch, diagnostic] of [
    [{ bind: '0.0.0.0' }, /bind must be explicitly/],
    [{ backend: 'http://192.0.2.1:18096' }, /backend must be/],
    [{ backend: 'http://localhost:18096' }, /backend must be/],
    [{ backend: origin(port) }, /ports must differ/],
    [{ backend: undefined }, /backend must be/],
    [{ port: undefined }, /port must be/],
    [{ model: undefined }, /model must be/],
    [{ auth: undefined }, /auth must contain/],
    [{ auth: { ...config.auth, 'token-sha256': 'fixture-invalid-digest' } }, /auth.token-sha256 must be/],
    [{ auth: { ...config.auth, 'expires-at-ms': Date.now() - 1000 } }, /auth.expires-at-ms must be/],
    [{ auth: { ...config.auth, 'expires-at-ms': Date.now() + 3_660_000 } }, /auth.expires-at-ms must be/],
    [{ 'token-file': 'fixture-fleet-path' }, /unexpected field/],
  ]) {
    const running = await launch(t, dir, 'serve-qa', { ...config, ...patch });
    const [code] = await running.done;
    assert.equal(code, 1);
    assert.ok(diagnostic.test(running.output()), `expected diagnostic ${diagnostic}`);
    assert.ok(!running.output().includes('"listeners"'), 'invalid fixture reported a listener');
    assert.ok(!running.output().includes('fixture-invalid-digest'), 'startup logged verifier input');
    assert.ok(!running.output().includes('fixture-fleet-path'), 'startup logged unexpected config input');
  }
});

test('a protected Unix socket forwards chat and fails closed when unavailable', {
  timeout: 30_000, skip: !process.getuid?.(),
}, async t => {
  // The system temp directory can have a symlink or a writable ancestor.
  const dir = await fixture(t, await realpath(homedir()));
  const socket = join(dir, 'backend.sock');
  const token = bearer();
  let calls = 0;
  const backend = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    assert.equal(req.method, 'POST');
    assert.equal(req.url, path);
    assert.ok(!('authorization' in req.headers), 'Unix backend received caller authorization');
    assert.deepEqual(JSON.parse(Buffer.concat(chunks)), chat);
    calls++;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"choices":[{"message":{"content":"ok"}}]}');
  }).listen(socket);
  await once(backend, 'listening');
  t.after(() => close(backend));
  const port = await freePort();
  const config = { bind: host, port, model, 'backend-socket': socket, auth: verifier(token) };
  const qa = await startQa(t, dir, config, { socketOnly: socket });
  assert.equal(calls, 0, 'Unix transport did not probe the backend');
  await expectError(await send(port), 401, 'unauthorized');
  assert.equal(calls, 0, 'Unix transport did not dispatch an auth negative');
  const response = await send(port, token);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).choices[0].message.content, 'ok');
  assert.equal(calls, 1);
  // Keep the closed socket's directory entry to exercise a failed connect.
  const stale = join(dir, 'stale.sock');
  await rename(socket, stale);
  await close(backend);
  await rename(stale, socket);
  await expectError(await send(port, token), 502, 'qa_backend_unavailable');
  await unlink(socket);
  await expectError(await send(port, token), 502, 'qa_backend_unavailable');
  assert.equal(calls, 1);
  await qa.stop();
  assert.ok(!qa.output().includes('fixture refused non-pinned outbound dial'), 'QA dialed outside the pinned socket');

  const unsafe = join(dir, 'unsafe');
  const nested = join(unsafe, 'child');
  await mkdir(unsafe);
  await chmod(unsafe, 0o770);
  await mkdir(nested, { mode: 0o700 });
  const alias = join(dir, 'alias');
  await symlink(unsafe, alias);
  const ordinaryFile = join(dir, 'regular.sock');
  await writeFile(ordinaryFile, 'fixture');
  for (const [patch, diagnostic] of [
    [{ backend: origin(await freePort()) }, /configure exactly one/],
    [{ 'backend-socket': 'relative.sock' }, /normalized absolute/],
    [{ 'backend-socket': `${dir}/../not-normalized.sock` }, /normalized absolute/],
    [{ 'backend-socket': `${dir}/${'x'.repeat(104)}.sock` }, /at most 103 UTF-8 bytes/],
    [{ 'backend-socket': join(unsafe, 'backend.sock') }, /parent.*mode 0700/],
    [{ 'backend-socket': join(nested, 'backend.sock') }, /ancestors must be trusted/],
    [{ 'backend-socket': join(alias, 'child', 'backend.sock') }, /ancestors must be trusted/],
    [{ 'backend-socket': ordinaryFile }, /must already be a Unix socket/],
    [{}, /must already be a Unix socket/],
  ]) {
    const running = await launch(t, dir, 'serve-qa', { ...config, ...patch }, { socketOnly: socket });
    const [code] = await running.done;
    assert.equal(code, 1);
    assert.ok(diagnostic.test(running.output()), `expected diagnostic ${diagnostic}`);
    assert.ok(!running.output().includes('"listeners"'), 'invalid socket configuration opened a listener');
    assert.ok(!running.output().includes('fixture refused non-pinned outbound dial'), 'rejected config dialed an upstream');
  }
});
