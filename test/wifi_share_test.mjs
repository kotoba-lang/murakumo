// `murakumo node wifi-share` against the packaged CLI: the local server, the seal route, and an
// INDEPENDENT open of what it returns (node:crypto only, not murakumo.onboard), so the helper's
// output is checked against the ADR-0243 rules rather than against itself.
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const cli=path.join(here,'..','release','node.mjs');
const port=18700+Math.floor(Math.random()*200);
const did='did:key:z6MkTestDevice';
const fpOf=d=>crypto.createHash('sha256').update(d).digest('hex').slice(0,8);
const labelSecret='AAECAwQFBgcICQoLDA0ODw';
// golden-vector device key (TEST ONLY; same as grant test/vectors/acoustic-onboard.edn)
const deviceSeed='00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff';
const devicePublic='1ekGUFKTlUnQVazwPzSCAFeOPtK2XrapZWRHF2G6T1Q';
const PKCS8=Buffer.from('302e020100300506032b656e04220420','hex'),SPKI=Buffer.from('302a300506032b656e032100','hex');
const devicePrivate=crypto.createPrivateKey({key:Buffer.concat([PKCS8,Buffer.from(deviceSeed,'hex')]),format:'der',type:'pkcs8'});
const passphrase='demo-pass-1234';
function open(message,{code,fp,ls}){
  const peer=crypto.createPublicKey({key:Buffer.concat([SPKI,message.subarray(1,33)]),format:'der',type:'spki'});
  const shared=crypto.diffieHellman({privateKey:devicePrivate,publicKey:peer});
  const aad=Buffer.from(`aiueos-wifi-profile-v1-acoustic\n${code}\n${fp}`);
  const okm=Buffer.from(crypto.hkdfSync('sha256',shared,Buffer.from(ls),aad,44));
  const ct=message.subarray(33),d=crypto.createDecipheriv('aes-256-gcm',okm.subarray(0,32),okm.subarray(32));
  d.setAAD(aad);d.setAuthTag(ct.subarray(-16));
  return Buffer.concat([d.update(ct.subarray(0,-16)),d.final()]);
}
const child=spawn('node',[cli,'node','wifi-share','--ssid','DemoNet','--label',`https://murakumo.cloud/#claim?q=${encodeURIComponent(`aiueos:1;did=${did};model=x;ls=${labelSecret}`)}`,
  '--passphrase-stdin','--no-open','--port',String(port)],{stdio:['pipe','pipe','pipe']});
let out='',err='';child.stdout.on('data',d=>out+=d);child.stderr.on('data',d=>err+=d);
child.stdin.end(passphrase+'\n');
try{
  const url=await new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(new Error('no url: '+err+out)),20000);
    const i=setInterval(()=>{const m=/Open \(this PC only\): (\S+)/.exec(out);if(m){clearInterval(i);clearTimeout(t);resolve(m[1]);}},100);});
  const token=new URL(url).searchParams.get('t');
  const base=`http://localhost:${port}`;
  assert.equal((await fetch(base+'/')).status,403,'the page needs the run token');
  assert.equal((await fetch(base+'/seal',{method:'POST',body:'{}'})).status,403,'sealing needs the run token');
  const page=await (await fetch(`${base}/?t=${token}`)).text();
  assert.ok(page.includes('DemoNet'));
  assert.ok(!page.includes(passphrase),'the page never carries the passphrase');
  assert.equal((await fetch(base+'/ggwave.js')).status,200,'the decoder is served from the installed package');
  const post=(body,t=token)=>fetch(`${base}/seal?t=${t}`,{method:'POST',body:JSON.stringify(body)});
  const code='K7QX3M',fp=fpOf(did);
  const ok=await post({code,fp,key:devicePublic});
  assert.equal(ok.status,200);
  const raw=await ok.text();
  assert.ok(!raw.includes(passphrase)&&!raw.includes(Buffer.from(passphrase).toString('base64')),'the response is sealed');
  const message=Buffer.from(JSON.parse(raw).payload,'base64');
  assert.equal(message[0],0x57);assert.ok(message.length<=140);
  const pt=open(message,{code,fp,ls:labelSecret});
  assert.equal(pt[0],1,'wpa2-personal');
  assert.equal(pt.subarray(1).toString(),`DemoNet\u0000${passphrase}`);
  assert.equal((await post({code,fp:'00000000',key:devicePublic})).status,409,'a box the label does not name is refused');
  assert.equal((await post({code:'bad',fp,key:devicePublic})).status,400);
  assert.equal((await post({code,fp,key:'short'})).status,400);
  assert.throws(()=>open(message,{code,fp,ls:'AAAAAAAAAAAAAAAAAAAAAA'}),'a different label secret does not open it');
  for(const c of ['AAAAA2','BBBBB2','CCCCC2','DDDDD2']) assert.equal((await post({code:c,fp,key:devicePublic})).status,200);
  assert.equal((await post({code:'EEEEE2',fp,key:devicePublic})).status,429,'at most five distinct codes per run');
  assert.ok(!out.includes(passphrase)&&!err.includes(passphrase),'the passphrase is never printed');
  console.log('wifi-share ok');
}finally{child.kill();}
