// `murakumo node console --once` against the packaged CLI: what a person at the box reads.
import {spawn,execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const cli=path.join(here,'..','release','node.mjs');
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'murakumo-console-'));
const home=path.join(tmp,'home'),state=path.join(tmp,'state');
fs.mkdirSync(path.join(state,'onboard'),{recursive:true});
const env={...process.env,MURAKUMO_NODE_HOME:home};
execFileSync('node',[cli,'node','init'],{env,stdio:'ignore'});
const did=JSON.parse(fs.readFileSync(path.join(home,'identity.json'),'utf8')).did;
const LS='AAECAwQFBgcICQoLDA0ODw',TOKEN='TOKEN-abc-123';
fs.writeFileSync(path.join(state,'label'),`aiueos:1;did=${did};model=k16;endpoint=https://murakumo.cloud;token=${TOKEN};ls=${LS}`,{mode:0o600});
fs.writeFileSync(path.join(state,'onboard','label-secret'),LS,{mode:0o600});
const run=(extra=[])=>new Promise(resolve=>{
  // a port nothing listens on: the console must say "unreachable", not hang
  const c=spawn('node',[cli,'node','console','--once','--state-dir',state,'--site','http://127.0.0.1:9',...extra],{env,stdio:['ignore','pipe','pipe']});
  let out='',err='';c.stdout.on('data',d=>out+=d);c.stderr.on('data',d=>err+=d);c.on('exit',code=>resolve({code,out,err}));});
// Rebuild the module matrix from the half-block text and check it is shaped like a QR code. The console
// is dark-on-black by default, so LIGHT modules are drawn as blocks and dark ones as the terminal's own
// background: a block is light, a space is dark, a half block is one of each.
function matrix(lines){
  const rows=[];
  for(const l of lines){const cells=[...l.slice(3)];
    rows.push(cells.map(ch=>ch===' '||ch==='▄'),cells.map(ch=>ch===' '||ch==='▀'));}   // true = dark
  return rows;
}
function finderAt(m,r,c){ // 7x7: dark ring, light ring, 3x3 dark core
  for(let y=0;y<7;y++)for(let x=0;x<7;x++){
    const ring=Math.max(Math.abs(y-3),Math.abs(x-3)); const dark=ring===3||ring<=1;
    if(!!m[r+y]?.[c+x]!==dark)return false;}
  return true;}
try{
  let r=await run();
  assert.equal(r.code,0,r.err+r.out);
  const lines=r.out.split('\n');
  assert.ok(!r.out.includes('\u001b'),'--once is plain text: no cursor or colour codes');
  assert.match(r.out,/MURAKUMO NODE/);
  assert.ok(r.out.includes(`(id `),'the fingerprint is shown');
  assert.match(r.out,/Claim\s+not known yet \(no report has been sent\)/);
  assert.match(r.out,/Console\s+http:\/\/127\.0\.0\.1:9\s+unreachable/);
  assert.match(r.out,/Wi-Fi\s+ready: can take Wi-Fi by sound/);
  assert.match(r.out,/scan with your phone/);
  assert.ok(!r.out.includes(LS),'the onboarding secret is never on the screen');
  assert.ok(!r.out.includes(TOKEN),'nor the claim token, as text (it is inside the QR)');
  // the QR: the block characters, shaped like a QR (finder patterns at three corners)
  const qrLines=lines.filter(l=>/^   [ █▀▄]{20,}$/.test(l));
  assert.ok(qrLines.length>=10,'a QR is drawn: '+qrLines.length+' rows');
  const m=matrix(qrLines),margin=2,size=m[0].length-2*margin;
  assert.equal((size-17)%4,0,'its side is 4v+17 modules');
  assert.ok(finderAt(m,margin,margin)&&finderAt(m,margin,margin+size-7)&&finderAt(m,margin+size-7,margin),'three finder patterns');
  // the claim link can use an address a phone can reach, different from the one the box talks to
  r=await run(['--public-url','https://192.168.1.4:8443']);
  assert.match(r.out,/Console\s+http:\/\/127\.0\.0\.1:9/);assert.match(r.out,/open https:\/\/192\.168\.1\.4:8443\/#claim/);
  r=await run(['--public-url','https://x.example/path']);assert.notEqual(r.code,0,'a public URL with a path is refused');
  // a heartbeat that was accepted: claimed, and nothing to scan
  fs.writeFileSync(path.join(state,'report-status.json'),JSON.stringify({outcome:'accepted','at-ms':Date.now()-5000}));
  r=await run();assert.equal(r.code,0);
  assert.match(r.out,/Claim\s+claimed/);
  assert.match(r.out,/Heartbeat\s+accepted · \d+ s ago/);
  assert.ok(!/scan with your phone/.test(r.out)&&!r.out.includes('█'),'a claimed box does not ask to be scanned');
  // not claimed yet
  fs.writeFileSync(path.join(state,'report-status.json'),JSON.stringify({outcome:'not-claimed','at-ms':Date.now()-90000}));
  r=await run();assert.match(r.out,/Claim\s+waiting to be claimed/);assert.match(r.out,/Heartbeat\s+not-claimed · \d+ min ago/);
  // a malformed status file is ignored, not shown
  fs.writeFileSync(path.join(state,'report-status.json'),'{not json');
  r=await run();assert.match(r.out,/Heartbeat\s+none yet/);
  // retired after the claim: the label secret is gone and the marker says so
  fs.unlinkSync(path.join(state,'onboard','label-secret'));fs.writeFileSync(path.join(state,'onboard','closed'),'');
  r=await run();assert.match(r.out,/retired \(the claim went through\)/);
  // no label: says there is nothing to scan
  fs.unlinkSync(path.join(state,'label'));
  r=await run();assert.match(r.out,/No label on this device/);
  // the console must be murakumo.cloud-shaped: a bad --interval is refused
  r=await new Promise(resolve=>{const c=spawn('node',[cli,'node','console','--interval','0','--state-dir',state],{env,stdio:['ignore','pipe','pipe']});let o='';c.stdout.on('data',d=>o+=d);c.on('exit',code=>resolve({code,o}));});
  assert.notEqual(r.code,0);
  console.log('node console ok');
}finally{fs.rmSync(tmp,{recursive:true,force:true});}
