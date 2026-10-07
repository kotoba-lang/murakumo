// ADR-2610071730 P5a: residents follow the signed intent's release in waves.
// n0 (wave 0) fetches the pinned bytes from a peer, verifies sha256, drains,
// replaces its release file and exits; n1 (wave 1) waits until n0 runs the
// target, ready and settled; the test restarts n0 (the supervisor's job) and
// then n1 updates too.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm,copyFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {generateKeyPairSync,randomBytes,randomUUID,createHash} from 'node:crypto';
const root=resolve('.'), cli=join(root,'release/node.mjs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const sha=b=>createHash('sha256').update(b).digest('hex');
async function wait(fn,what,n=400){for(let i=0;i<n;i++){try{if(await fn())return;}catch{}await sleep(100);}throw Error(what+' timed out');}
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}

test('residents self-update in waves behind a health gate',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-rollout-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const {privateKey,publicKey}=generateKeyPairSync('ed25519');
 const keyFile=join(dir,'operator.pem'),publicFile=join(dir,'operator.pub'),tokenFile=join(dir,'peer-token');
 await writeFile(keyFile,privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
 await writeFile(publicFile,publicKey.export({type:'spki',format:'pem'}));
 const token=randomBytes(32).toString('hex');await writeFile(tokenFile,token,{mode:0o600});
 const ids=['n0','n1'],ports=await Promise.all(ids.map(port)),gw=await port();
 const urls=ports.map(p=>'http://127.0.0.1:'+p),owners=ids.map(()=>randomUUID());
 // each node runs its own copy of the release; the target differs by one comment
 const original=await readFile(cli), target=Buffer.concat([original,Buffer.from('\n// rollout test target\n')]);
 const rel=ids.map(id=>join(dir,id+'-node.mjs'));
 for(const f of rel) await copyFile(cli,f);
 const targetFile=join(dir,'target.mjs');await writeFile(targetFile,target);
 const backend=createServer((req,res)=>{if(req.url==='/slots')return res.end('[{"is_processing":false}]');let b='';req.on('data',x=>b+=x);req.on('end',()=>res.end(JSON.stringify({choices:[{message:{content:'5'}}]})));});
 backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const payload={schema:1,revision:1,'spec-version':2,
  nodes:ids.map((node,i)=>({node,url:urls[i],'owner-id':owners[i],enabled:true,roles:[{kind:'text',model:'mishima'}]})),
  gateways:[{node:'g0',url:'http://127.0.0.1:'+gw,enabled:true}],policies:{'minimum-replicas':{mishima:1}},
  agents:{resident:{sha256:sha(target),waves:[['n0'],['n1']],'settle-ms':500}}};
 const input=join(dir,'input.json'),signed=join(dir,'signed.json');await writeFile(input,JSON.stringify(payload));
 execFileSync(process.execPath,[cli,'topology','sign','--input',input,'--output',signed,'--private-key-file',keyFile,'--allow-loopback','true'],{stdio:'pipe'});
 const procs={};t.after(()=>{for(const p of Object.values(procs))p.kill('SIGTERM');});
 const cfgPath=i=>join(dir,ids[i]+'.json');
 for(let i=0;i<2;i++){
  const tf=join(dir,ids[i]+'-topology.json');await writeFile(tf,await readFile(signed),{mode:0o600});
  const state=join(dir,ids[i]+'-receipts.json');await writeFile(state+'.owner-id',owners[i],{mode:0o600});
  await writeFile(cfgPath(i),JSON.stringify({node:ids[i],port:ports[i],bind:'127.0.0.1','token-file':tokenFile,'topology-file':tf,
   'topology-public-key-file':publicFile,'topology-poll-ms':100,'allow-loopback-topology?':true,'probe-interval-ms':100,
   'observation-poll-ms':100,'observation-interval-ms':200,'converge-first-delay-ms':100000,'rollout-first-delay-ms':300,'rollout-interval-ms':300,
   'release-file':rel[i],'state-file':state,'legacy-empty-ledger-owner-id':owners[i],
   lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:'http://127.0.0.1:'+backend.address().port,path:'/v1/chat/completions'}}}));
 }
 const start=i=>{const p=spawn(process.execPath,[cli,'resident','serve','--config',cfgPath(i)],{stdio:['ignore','pipe','pipe']});procs[ids[i]]=p;return p;};
 // seed the target into n1's cache only: n0 must get it peer to peer
 const n1=start(1);
 await wait(async()=>(await fetch(urls[1]+'/observation')).ok,'n1 up');
 const push=await fetch(urls[1]+'/release',{method:'POST',body:target,headers:{authorization:'Bearer '+token}});
 assert.equal((await push.json()).sha256,sha(target));
 assert.equal((await fetch(urls[1]+'/release/'+sha(target))).status,200,'the cached release is served to peers');
 const n0=start(0);
 const [code]=await once(n0,'exit');
 assert.equal(code,0,'wave 0 hands over to its supervisor');
 assert.equal(sha(await readFile(rel[0])),sha(target),'wave 0 replaced its release with the verified target');
 assert.ok(existsSync(join(dir,'n0-receipts.json.self-update.json')),'update marker left for the new release');
 const obs=async u=>(await(await fetch(u+'/observations')).json());
 await wait(async()=>(await obs(urls[1])).n1?.payload.rollout?.state==='waiting','n1 waits for wave 0');
 assert.equal(sha(await readFile(rel[1])),sha(original),'wave 1 untouched while wave 0 is down');
 // the supervisor restarts n0 on its new release
 start(0);
 await wait(async()=>{const d=(await obs(urls[0])).n0;return d?.payload.release===sha(target).slice(0,16)&&d.payload.rollout?.updated?.to===sha(target);},'n0 reports the update');
 assert.ok(!existsSync(join(dir,'n0-receipts.json.draining')),'the new release removed its handed-over drain');
 const [code1]=await once(n1,'exit');
 assert.equal(code1,0,'wave 1 updates after the gate opens');
 assert.equal(sha(await readFile(rel[1])),sha(target));
});
