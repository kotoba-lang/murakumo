// ADR-2610071730 P5b: residents configured with three operator keys and a
// threshold of two refuse a topology signed by one key and accept it once a
// second operator co-signs (murakumo topology sign --key-id + cosign).
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {openSync} from 'node:fs';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {generateKeyPairSync,randomBytes,randomUUID} from 'node:crypto';
const root=resolve('.'), cli=join(root,'release/node.mjs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
// residents log to files, never to a pipe: execFileSync blocks this process,
// and on Linux a resident writing to a full pipe blocks too — a deadlock
const logTo=(dir,name)=>{const f=openSync(join(dir,name+'.log'),'a');return ['ignore',f,f];};
async function wait(fn,what){for(let i=0;i<300;i++){try{if(await fn())return;}catch{}await sleep(100);}throw Error(what+' timed out');}
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}

test('a two-of-three operator quorum signs the fleet intent',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-quorum-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const keys={};
 for(const id of ['a','b','c']){const k=generateKeyPairSync('ed25519');keys[id]={priv:join(dir,id+'.pem'),pub:join(dir,id+'.pub')};
  await writeFile(keys[id].priv,k.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
  await writeFile(keys[id].pub,k.publicKey.export({type:'spki',format:'pem'}));}
 const tokenFile=join(dir,'peer-token');const token=randomBytes(32).toString('hex');await writeFile(tokenFile,token,{mode:0o600});
 const ids=['n0','n1'],ports=await Promise.all(ids.map(port)),gw=await port();
 const urls=ports.map(p=>'http://127.0.0.1:'+p),owners=ids.map(()=>randomUUID());
 const payload=rev=>({schema:1,revision:rev,nodes:ids.map((node,i)=>({node,url:urls[i],'owner-id':owners[i],enabled:true,roles:[{kind:'text',model:'mishima'}]})),
  gateways:[{node:'g0',url:'http://127.0.0.1:'+gw,enabled:true}],policies:{'minimum-replicas':{mishima:1}}});
 const signWith=async(rev,ids2)=>{const input=join(dir,'in-'+rev+'.json'),out=join(dir,'doc-'+rev+'-'+ids2.join('')+'.json');
  await writeFile(input,JSON.stringify(payload(rev)));
  execFileSync(process.execPath,[cli,'topology','sign','--input',input,'--output',out,'--private-key-file',keys[ids2[0]].priv,'--key-id',ids2[0],'--allow-loopback','true'],{timeout:120000,stdio:'pipe'});
  for(const id of ids2.slice(1)) execFileSync(process.execPath,[cli,'topology','cosign','--document',out,'--private-key-file',keys[id].priv,'--key-id',id,'--allow-loopback','true'],{timeout:120000,stdio:'pipe'});
  return out;};
 const v1=await signWith(1,['a','b']);
 const backend=createServer((req,res)=>{if(req.url==='/slots')return res.end('[{"is_processing":false}]');let b='';req.on('data',x=>b+=x);req.on('end',()=>res.end(JSON.stringify({choices:[{message:{content:'5'}}]})));});
 backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const procs=[];t.after(()=>{for(const p of procs)p.kill('SIGTERM');});
 for(let i=0;i<2;i++){
  const tf=join(dir,ids[i]+'-topology.json');await writeFile(tf,await readFile(v1),{mode:0o600});
  const state=join(dir,ids[i]+'-receipts.json');await writeFile(state+'.owner-id',owners[i],{mode:0o600});
  const cfg=join(dir,ids[i]+'.json');
  await writeFile(cfg,JSON.stringify({node:ids[i],port:ports[i],bind:'127.0.0.1','token-file':tokenFile,'topology-file':tf,
   'topology-public-keys':{a:keys.a.pub,b:keys.b.pub,c:keys.c.pub},'topology-threshold':2,
   'topology-poll-ms':100,'allow-loopback-topology?':true,'probe-interval-ms':100,'state-file':state,'legacy-empty-ledger-owner-id':owners[i],
   lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:'http://127.0.0.1:'+backend.address().port,path:'/v1/chat/completions'}}}));
  procs.push(spawn(process.execPath,[cli,'resident','serve','--config',cfg],{stdio:logTo(dir,'r'+procs.length)}));
 }
 const H={authorization:'Bearer '+token,'content-type':'application/json'};
 const rev=async i=>(await(await fetch(urls[i]+'/topology',{headers:H})).json()).payload.revision;
 await wait(async()=>(await rev(0))===1&&(await rev(1))===1,'start on the 2-of-3 document');
 // one operator alone cannot move the fleet
 const single=await signWith(2,['c']);
 const r1=await fetch(urls[0]+'/topology',{method:'POST',headers:H,body:await readFile(single)});
 assert.equal(r1.status,400,'a single-key revision is refused');
 assert.equal(await rev(0),1);
 // a second operator co-signs the same revision: accepted, and gossip carries it
 execFileSync(process.execPath,[cli,'topology','cosign','--document',single,'--private-key-file',keys.a.priv,'--key-id','a','--allow-loopback','true'],{timeout:120000,stdio:'pipe'});
 const r2=await fetch(urls[0]+'/topology',{method:'POST',headers:H,body:await readFile(single)});
 assert.equal(r2.status,200);
 await wait(async()=>(await rev(1))===2,'the co-signed revision reaches the other resident');
});
