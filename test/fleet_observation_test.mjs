// ADR-2610071730 P2: each resident signs its own observation (L2) and the
// fleet's observations converge by gossip; GET /observations needs no token.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {generateKeyPairSync,randomBytes,randomUUID,verify,createPublicKey} from 'node:crypto';
const root=resolve('.'), cli=join(root,'release/node.mjs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function wait(fn,what){for(let i=0;i<200;i++){try{if(await fn())return;}catch{}await sleep(50);}throw Error(what+' timed out');}
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}
const B58='123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function didKey(did){let n=0n;for(const c of did.slice('did:key:z'.length))n=n*58n+BigInt(B58.indexOf(c));let h=n.toString(16);if(h.length%2)h='0'+h;const b=Buffer.from(h,'hex');return createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),b.subarray(2)]),format:'der',type:'spki'});}
function canonical(x){if(Array.isArray(x))return x.map(canonical);if(x&&typeof x==='object')return Object.fromEntries(Object.keys(x).sort().map(k=>[k,canonical(x[k])]));return x;}

test('residents sign their own observation and converge the fleet view by gossip',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-observation-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const {privateKey,publicKey}=generateKeyPairSync('ed25519');
 const keyFile=join(dir,'operator.pem'),publicFile=join(dir,'operator.pub'),tokenFile=join(dir,'peer-token');
 await writeFile(keyFile,privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
 await writeFile(publicFile,publicKey.export({type:'spki',format:'pem'}));
 await writeFile(tokenFile,randomBytes(32).toString('hex'),{mode:0o600});
 const ids=['n0','n1'],ports=await Promise.all(ids.map(port)),gw=await port();
 const urls=ports.map(p=>'http://127.0.0.1:'+p),owners=ids.map(()=>randomUUID());
 const payload={schema:1,revision:1,'spec-version':2,models:{mishima:{kind:'text',file:'mishima/model/m.gguf'}},
  classes:{'mishima-text':{template:'llama-server',model:'mishima',replicas:2,eligible:{'max-pressure':4}}},
  nodes:ids.map((node,i)=>({node,url:urls[i],'owner-id':owners[i],enabled:true,roles:[{kind:'text',model:'mishima'}],pins:['mishima-text']})),
  gateways:[{node:'g0',url:'http://127.0.0.1:'+gw,enabled:true}],policies:{'minimum-replicas':{mishima:1},'job-voters':ids.map((node,i)=>({node,url:urls[i]}))}};
 const input=join(dir,'input.json'),signed=join(dir,'signed.json');await writeFile(input,JSON.stringify(payload));
 execFileSync(process.execPath,[cli,'topology','sign','--input',input,'--output',signed,'--private-key-file',keyFile,'--allow-loopback','true'],{stdio:'pipe'});
 const backend=createServer((req,res)=>{if(req.url==='/slots')return res.end('[{"is_processing":false}]');let b='';req.on('data',x=>b+=x);req.on('end',()=>res.end(JSON.stringify({choices:[{message:{content:'5'}}]})));});
 backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const processes=[];t.after(()=>{for(const p of processes)p.kill('SIGTERM');});
 for(let i=0;i<2;i++){
  const tf=join(dir,ids[i]+'-topology.json');await writeFile(tf,await readFile(signed),{mode:0o600});
  const state=join(dir,ids[i]+'-receipts.json');await writeFile(state+'.owner-id',owners[i],{mode:0o600});
  const cfg={node:ids[i],port:ports[i],bind:'127.0.0.1','token-file':tokenFile,'topology-file':tf,'topology-public-key-file':publicFile,
   'topology-poll-ms':50,'allow-loopback-topology?':true,'probe-interval-ms':100,'observation-poll-ms':100,'observation-interval-ms':200,'model-root':dir,'converge-first-delay-ms':200,'converge-interval-ms':200,
   'state-file':state,'legacy-empty-ledger-owner-id':owners[i],
   lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:'http://127.0.0.1:'+backend.address().port,path:'/v1/chat/completions'}}};
  const cp=join(dir,ids[i]+'.json');await writeFile(cp,JSON.stringify(cfg));
  processes.push(spawn(process.execPath,[cli,'resident','serve','--config',cp],{stdio:['ignore','pipe','pipe']}));
 }
 const obs=async i=>(await(await fetch(urls[i]+'/observations')).json());
 // no token needed, and both nodes appear on both residents (gossip)
 await wait(async()=>{const a=await obs(0),b=await obs(1);return a.n0&&a.n1&&b.n0&&b.n1;},'observation gossip');
 const all=await obs(0);
 for(const id of ids){
  const d=all[id];
  assert.equal(d.payload.node,id);
  assert.match(d.payload.did,/^did:key:z6Mk/);
  assert.ok(verify(null,Buffer.from(JSON.stringify(canonical(d.payload))),didKey(d.payload.did),Buffer.from(d.signature,'base64url')),'signed by the did it names');
  assert.equal(d.payload.topology.revision,1);
  assert.equal(d.payload.lanes.text.model,'mishima');
  assert.equal(d.payload.models.mishima.present,false,'intent model file checked under model-root');
 }
 assert.notEqual(all.n0.payload.did,all.n1.payload.did,'each node has its own key');
 // P3a: each node publishes its convergence plan in its signed observation;
 // the intent does not say enforce, so the mode is observe and nothing is acted on
 await wait(async()=>{const d=(await obs(0)).n0;return d.payload.converge?.plan?.length;},'convergence plan');
 const conv=(await obs(0)).n0.payload.converge;
 assert.equal(conv.mode,'observe');
 assert.deepEqual(conv.plan.map(s=>[s.class,s.step]),[['mishima-text','ok']]);
 assert.equal(conv.acted,undefined);
 // the rest of the resident still requires the fleet token
 assert.equal((await fetch(urls[0]+'/health')).status,401);
 // P3b: voters serve quorum leases on /leases (token required)
 const tok=(await readFile(tokenFile,'utf8')).trim(),H={authorization:'Bearer '+tok,'content-type':'application/json'};
 const id='l_'+'0'.repeat(24)+'_0_1',b=[Date.now(),'t'],value={node:'n1',class:'mishima-text',slot:0,epoch:1,until:Date.now()+60000,revision:1};
 assert.equal((await fetch(urls[0]+'/leases/'+id+'/prepare',{method:'POST',body:JSON.stringify({ballot:b})})).status,401);
 for(const u of urls){
  const p=await(await fetch(u+'/leases/'+id+'/prepare',{method:'POST',headers:H,body:JSON.stringify({ballot:b})})).json();assert.equal(p.ok,true);
  const a=await(await fetch(u+'/leases/'+id+'/accept',{method:'POST',headers:H,body:JSON.stringify({ballot:b,value})})).json();assert.equal(a.ok,true);
 }
 assert.equal((await(await fetch(urls[1]+'/leases/hint/l_'+'0'.repeat(24)+'_0',{headers:H})).json()).epoch,1);
 const bad=await(await fetch(urls[0]+'/leases/'+id.replace(/_1$/,'_2')+'/accept',{method:'POST',headers:H,body:JSON.stringify({ballot:[Date.now(),'u'],value:{...value,node:'nobody',epoch:2}})})).json();
 assert.equal(bad.ok,false,'voters refuse a lease for a node outside the topology');
 // P4: murakumo apply — validate, sign, send, follow until every resident holds it
 const live=JSON.parse(await readFile(signed,'utf8')).payload;
 const next={...live,revision:2,nodes:live.nodes.map(n=>n.node==='n1'?{...n,cordoned:true}:n)};
 const intentFile=join(dir,'intent-2.json');await writeFile(intentFile,JSON.stringify(next));
 const dry=execFileSync(process.execPath,[cli,'apply','-f',intentFile,'--dry-run','--allow-loopback','--token-file',tokenFile],{encoding:'utf8'});
 assert.match(dry,/cordoned/);assert.match(dry,/dry run/);
 const out=execFileSync(process.execPath,[cli,'apply','-f',intentFile,'--allow-loopback','--private-key-file',keyFile,'--token-file',tokenFile,'--poll-ms','500','--timeout-s','60'],{encoding:'utf8'});
 assert.match(out,/2\/2 residents hold 2/);
 const nodes=execFileSync(process.execPath,[cli,'get','nodes','-f',intentFile],{encoding:'utf8'});
 assert.match(nodes,/n0/);assert.match(nodes,/n1/);
 // a non-advancing revision is refused
 assert.throws(()=>execFileSync(process.execPath,[cli,'apply','-f',intentFile,'--allow-loopback','--private-key-file',keyFile,'--token-file',tokenFile],{encoding:'utf8',stdio:'pipe'}));
 // the node key is private and persists across restart
 const key=join(dir,'n0-receipts.json.node-key');
 assert.equal((await import('node:fs')).statSync(key).mode & 0o077,0);
});
