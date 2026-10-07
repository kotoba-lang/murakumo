// `murakumo node report` against the packaged CLI. The fake console re-implements the SERVER's
// check independently of the CLI (kotoba-lang/cloud-murakumo devices-http/verify-heartbeat): the
// signed message is domain + did + request origin + SHA-256 of the body as received, verified
// with the public key inside the did:key. So the CLI's output is checked against the wire rule,
// not against itself.
import {spawn,execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const cli=path.join(here,'..','release','node.mjs');
const home=fs.mkdtempSync(path.join(os.tmpdir(),'murakumo-report-'));
const env={...process.env,MURAKUMO_NODE_HOME:home};
execFileSync('node',[cli,'node','init'],{env,stdio:'ignore'});
const did=JSON.parse(fs.readFileSync(path.join(home,'identity.json'),'utf8')).did;

const B58='123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function didKeyPublic(d){let n=0n;for(const c of d.replace('did:key:z',''))n=n*58n+BigInt(B58.indexOf(c));
  let h=n.toString(16);if(h.length%2)h='0'+h;const raw=Buffer.from(h,'hex');assert.deepEqual([...raw.subarray(0,2)],[0xed,0x01]);
  return crypto.createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),raw.subarray(2)]),format:'der',type:'spki'});}
const pub=didKeyPublic(did);

let mode='ok',lastObserved=0;const seen=[];
const server=http.createServer(async(req,res)=>{
  let raw='';for await(const c of req)raw+=c;
  const origin=`http://${req.headers.host}`;
  const sha=crypto.createHash('sha256').update(raw,'utf8').digest('hex');
  const message=`aiueos-device-heartbeat-v1\n${did}\n${origin}\n${sha}\n`;
  const sig=req.headers['x-aiueos-signature'];
  let ok=false;try{ok=crypto.verify(null,Buffer.from(message),pub,Buffer.from(sig,'base64url'));}catch{}
  seen.push({url:req.url,method:req.method,ok,raw});
  res.setHeader('content-type','application/json');
  const reply=(status,o)=>{res.statusCode=status;res.end(JSON.stringify(o));};
  if(req.url!==`/api/devices/${encodeURIComponent(did)}/heartbeat`||req.method!=='POST')return reply(404,{});
  if(!ok)return reply(401,{accepted:false,reason:'bad-signature'});
  if(mode==='unclaimed')return reply(409,{accepted:false,reason:'not-claimed'});
  const body=JSON.parse(raw);
  if(mode==='future')return reply(409,{accepted:false,reason:'future-dated'});
  if(!(body['observed-at-ms']>lastObserved))return reply(409,{accepted:false,reason:'stale'});
  lastObserved=body['observed-at-ms'];
  return reply(202,{accepted:true,did,observedAtMs:lastObserved});
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const port=server.address().port,site=`http://127.0.0.1:${port}`;
const run=(args)=>new Promise(resolve=>{const c=spawn('node',[cli,'node','report',...args],{env,stdio:['ignore','pipe','pipe']});
  let out='',err='';c.stdout.on('data',d=>out+=d);c.stderr.on('data',d=>err+=d);c.on('exit',code=>resolve({code,out,err}));});
try{
  // accepted: 202, exit 0, and the server verified the signature over the body it received
  let r=await run(['--site',site,'--once']);
  assert.equal(r.code,0,r.out+r.err);assert.match(r.out,/Heartbeat: accepted/);
  assert.equal(seen.at(-1).ok,true,'the server-side check of the signature passed');
  // what was sent: a fixed shape and nothing that identifies the machine
  const sent=JSON.parse(seen.at(-1).raw);
  assert.deepEqual(Object.keys(sent),['observed-at-ms','metrics']);
  assert.deepEqual(Object.keys(sent.metrics).sort(),['arch','load1','mem-free-ratio','node','platform','uptime-s']);
  assert.ok(Math.abs(sent['observed-at-ms']-Date.now())<30000);
  const text=seen.at(-1).raw;
  for(const secret of [os.hostname(),home,...Object.values(os.networkInterfaces()).flat().map(i=>i.address)]) assert.ok(!text.includes(secret),'no host name, path or address in the heartbeat');
  assert.ok(!r.out.includes('PRIVATE')&&!r.err.includes('PRIVATE'),'the key is never printed');
  // two reports are not the same observation: the clock moves forward between runs
  await new Promise(x=>setTimeout(x,5));
  r=await run(['--site',site,'--once']);assert.equal(r.code,0);
  // not claimed yet: exit 4, expected and quiet
  mode='unclaimed';r=await run(['--site',site,'--once']);
  assert.equal(r.code,4);assert.match(r.out,/not-claimed/);
  // a clock the server thinks is ahead: exit 5
  mode='future';r=await run(['--site',site,'--once']);assert.equal(r.code,5);
  // a replayed observation is not a fault
  mode='ok';lastObserved=Date.now()+60000;r=await run(['--site',site,'--once']);assert.equal(r.code,0);assert.match(r.out,/stale/);
  // the server does not know our key: exit 1 (the device's own key handling is wrong, retrying will not help)
  const otherHome=fs.mkdtempSync(path.join(os.tmpdir(),'murakumo-report-other-'));
  execFileSync('node',[cli,'node','init'],{env:{...process.env,MURAKUMO_NODE_HOME:otherHome},stdio:'ignore'});
  // (a device whose DID the console resolves to a DIFFERENT key: sign with the other identity at our DID's path)
  fs.copyFileSync(path.join(otherHome,'identity.json'),path.join(otherHome,'id2.json'));
  const forged=JSON.parse(fs.readFileSync(path.join(home,'identity.json'),'utf8'));
  const other=JSON.parse(fs.readFileSync(path.join(otherHome,'identity.json'),'utf8'));
  fs.writeFileSync(path.join(otherHome,'identity.json'),JSON.stringify({did:forged.did,privateKey:other.privateKey}),{mode:0o600});
  r=await new Promise(resolve=>{const c=spawn('node',[cli,'node','report','--site',site,'--once'],{env:{...process.env,MURAKUMO_NODE_HOME:otherHome},stdio:['ignore','pipe','pipe']});
    let out='',err='';c.stdout.on('data',d=>out+=d);c.stderr.on('data',d=>err+=d);c.on('exit',code=>resolve({code,out,err}));});
  assert.ok(r.code!==0,'a DID whose key did not sign is refused or the identity is rejected');
  // unreachable: exit 3
  await new Promise(x=>server.close(x));
  r=await run(['--site',site,'--once']);assert.equal(r.code,3);assert.match(r.out,/unreachable/);
  // the console must be murakumo.cloud or loopback
  r=await run(['--site','https://evil.example','--once']);assert.notEqual(r.code,0);
  r=await run(['--once','--interval','30']);assert.notEqual(r.code,0,'--once and --interval are exclusive');
  console.log('device report ok');
}finally{server.close();fs.rmSync(home,{recursive:true,force:true});}
