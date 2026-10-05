import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {generateKeyPairSync,randomBytes,randomUUID} from 'node:crypto';
const root=resolve('.'), cli=join(root,'release/node.mjs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function wait(fn){for(let i=0;i<150;i++){try{if(await fn())return;}catch{}await sleep(50);}throw Error('topology convergence timed out');}
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}
test('signed topology converges without its publisher, admits approved joins and survives restarts',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-topology-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const {privateKey,publicKey}=generateKeyPairSync('ed25519');
 const keyFile=join(dir,'operator.pem'),publicFile=join(dir,'operator.pub'),tokenFile=join(dir,'peer-token');
 await writeFile(keyFile,privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
 await writeFile(publicFile,publicKey.export({type:'spki',format:'pem'}));
 const token=randomBytes(32).toString('hex');await writeFile(tokenFile,token,{mode:0o600});
 const headers={authorization:'Bearer '+token,'content-type':'application/json'};
 const ids=['n0','n1','g0','g1'],ports=await Promise.all(ids.map(port));
 const urls=ports.map(p=>'http://127.0.0.1:'+p),files=ids.map(id=>join(dir,id+'-topology.json'));
 const owners=[randomUUID(),randomUUID()];
 const payload={schema:1,revision:1,nodes:ids.slice(0,2).map((node,i)=>({node,url:urls[i],'owner-id':owners[i],enabled:i===0,roles:[{kind:'text',model:'mishima'}]})),gateways:ids.slice(2).map((node,i)=>({node,url:urls[i+2],enabled:true})),policies:{'minimum-replicas':{mishima:2}}};
 async function signed(p){const input=join(dir,'input-'+p.revision+'.json'),output=join(dir,'signed-'+p.revision+'-'+randomBytes(4).toString('hex')+'.json');await writeFile(input,JSON.stringify(p));execFileSync(process.execPath,[cli,'topology','sign','--input',input,'--output',output,'--private-key-file',keyFile,'--allow-loopback','true'],{stdio:'pipe'});return JSON.parse(await readFile(output));}
 const v1=await signed(payload);await Promise.all(files.map(f=>writeFile(f,JSON.stringify(v1),{mode:0o600})));
 let calls=0,canaries=0,backendBad=false;
 const backend=createServer((req,res)=>{if(req.url==='/slots')return res.end('[{"is_processing":false}]');let bytes='';req.on('data',b=>bytes+=b);req.on('end',()=>{let b=JSON.parse(bytes);if(b.messages[0].content.startsWith('2+3'))canaries++;else calls++;res.end(JSON.stringify({choices:[{message:{content:b.messages[0].content.startsWith('2+3')?(backendBad?'bad':'5'):'OK'}}]}));});});
 backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const processes=[];t.after(()=>{for(const p of processes)p.kill('SIGTERM');});
 const configs=ids.map((node,i)=>({node,port:ports[i],bind:'127.0.0.1','token-file':tokenFile,'topology-file':files[i],'topology-public-key-file':publicFile,'topology-seeds':[urls[0]],'topology-poll-ms':50,'topology-fanout':3,'allow-loopback-topology?':true,'probe-interval-ms':50,...(i<2?{'state-file':join(dir,node+'-receipts.json'),lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:'http://127.0.0.1:'+backend.address().port,path:'/v1/chat/completions'}}}:{peers:[]})}));
 async function launch(i){const path=join(dir,ids[i]+'.json');await writeFile(path,JSON.stringify(configs[i]));const p=spawn(process.execPath,[cli,i<2?'resident':'gateway','serve','--config',path],{stdio:['ignore','pipe','pipe']});processes.push(p);return p;}
 for(let i=0;i<2;i++)await writeFile(configs[i]['state-file']+'.owner-id',owners[i],{mode:0o600});
 for(let i=0;i<4;i++)await launch(i);
 const health=async i=>(await(await fetch(urls[i]+'/health',{headers})).json());
 await wait(async()=>{const h=await health(2);return h.peers?.n0?.lanes.text.ready;});
 assert.equal((await health(1)).lanes.text.ready,false);
 assert.equal((await fetch(urls[2]+'/topology')).status,401);
 const tampered=structuredClone(v1);tampered.payload.revision=999;
 assert.equal((await fetch(urls[0]+'/topology',{method:'POST',headers,body:JSON.stringify(tampered)})).status,400);
 const v2=await signed({...payload,revision:2,nodes:payload.nodes.map(n=>({...n,enabled:true,url:n.url+'/'}))});
 assert.equal((await fetch(urls[0]+'/topology',{method:'POST',headers,body:JSON.stringify(v2)})).status,200);
 await wait(async()=>{const hs=await Promise.all(ids.map((_,i)=>health(i)));return hs.every(h=>h.topology?.revision===2)&&hs.slice(2).every(h=>h.peers?.n1?.lanes.text.ready);});
 const before=canaries;await sleep(100);assert.ok(canaries>before);
 // An old valid snapshot cannot roll back membership.
 await fetch(urls[1]+'/topology',{method:'POST',headers,body:JSON.stringify(v1)});assert.equal((await health(1)).topology.revision,2);
 // All replicas continue after the initial publisher disappears.
 processes[0].kill('SIGTERM');await once(processes[0],'exit');
 await wait(async()=>!(await health(2)).peers?.n0);
 const request=async()=>fetch(urls[3]+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'distributed request'}]})});
 let response;await wait(async()=>{response=await request();return response.status===200;});assert.equal(response.headers.get('x-murakumo-node'),'n1');const jobId=response.headers.get('x-murakumo-job-id');await response.text();assert.equal(calls,1);
 // Persisted replicas restart without fetching the publisher.
 processes[1].kill('SIGTERM');await once(processes[1],'exit');await launch(1);
 await wait(async()=>{const h=await health(1);return h.topology?.revision===2&&h.lanes.text.ready;});
 // Equal revision with different signed content is a durable conflict, never a random winner.
 const conflict=await signed({...payload,revision:2,nodes:payload.nodes});
 assert.equal((await fetch(urls[1]+'/topology',{method:'POST',headers,body:JSON.stringify(conflict)})).status,400);
 assert.equal((await health(1)).topology.conflict,true);assert.equal((await health(1)).lanes.text.ready,false);
 const resident=processes.at(-1);resident.kill('SIGTERM');await once(resident,'exit');await launch(1);
 await wait(async()=>(await health(1)).topology?.conflict===true);
 const v3=await signed({...payload,revision:3,nodes:payload.nodes.map(n=>({...n,enabled:n.node==='n1'}))});
 assert.equal((await fetch(urls[3]+'/topology',{method:'POST',headers,body:JSON.stringify(v3)})).status,200);
 await wait(async()=>{const h=await health(1);return h.topology?.revision===3&&!h.topology.conflict&&h.lanes.text.ready;});
 // A signed retirement converges to admission denial, without editing each gateway.
 const v4=await signed({...payload,revision:4,nodes:payload.nodes.map(n=>({...n,enabled:false}))});
 await fetch(urls[1]+'/topology',{method:'POST',headers,body:JSON.stringify(v4)});
 await wait(async()=>{const h=await health(3);return h.topology?.revision===4&&Object.keys(h.peers).length===0;});
 const retired=await request();assert.equal(retired.status,429);assert.equal((await retired.json()).executed,false);assert.equal(calls,1);
 const replay=await fetch(urls[3]+'/v1/chat/completions',{method:'POST',headers:{...headers,'x-murakumo-job-id':jobId},body:JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'distributed request'}]})});
 assert.equal(replay.status,409);assert.equal((await replay.json()).executed,'unknown');assert.equal(calls,1);
 // Re-enabling a role must not reuse an old successful canary.
 backendBad=true;
 const enabledAgain=await signed({...payload,revision:5,nodes:payload.nodes.map(n=>({...n,enabled:n.node==='n1'}))});
 await fetch(urls[1]+'/topology',{method:'POST',headers,body:JSON.stringify(enabledAgain)});
 assert.equal((await health(1)).lanes.text.ready,false);
 await sleep(250);assert.equal((await health(1)).lanes.text.ready,false);
 const denied=await fetch(urls[1]+'/execute/text',{method:'POST',headers:{...headers,'x-murakumo-job-id':'fresh_job_123'},body:JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'fresh'}]})});
 assert.notEqual(denied.status,200);assert.equal(calls,1);
 backendBad=false;await wait(async()=>(await health(1)).lanes.text.ready);
 const namespaced=await signed({...payload,revision:6,policies:{'minimum-replicas':{'org/model':2}},nodes:payload.nodes.map(n=>({...n,enabled:n.node==='n1'}))});
 const nsResponse=await fetch(urls[1]+'/topology',{method:'POST',headers,body:JSON.stringify(namespaced)});assert.equal(nsResponse.status,200,await nsResponse.text());
 await wait(async()=>(await health(3)).topology?.revision===6);
 assert.ok((await(await fetch(urls[3]+'/topology',{headers})).json()).payload.policies['minimum-replicas']['org/model']);
 // A signer refuses both oversized artifacts and replacement of retained owners.
 await assert.rejects(()=>signed({...payload,revision:7,description:'x'.repeat(262144)}));
 const historyIn=join(dir,'history-in.json'),historyOut=join(dir,'history-out.json');
 await writeFile(historyOut,JSON.stringify(namespaced));
 await writeFile(historyIn,JSON.stringify({...payload,revision:7,nodes:payload.nodes.slice(0,1)}));
 assert.throws(()=>execFileSync(process.execPath,[cli,'topology','sign','--input',historyIn,'--output',historyOut,'--private-key-file',keyFile,'--allow-loopback','true'],{stdio:'pipe'}));
 assert.deepEqual(JSON.parse(await readFile(historyOut)),namespaced);
 const replacement=await signed({...payload,revision:7,nodes:payload.nodes.map(n=>({...n,'owner-id':randomUUID()}))});
 assert.equal((await fetch(urls[3]+'/topology',{method:'POST',headers,body:JSON.stringify(replacement)})).status,400);
 assert.equal((await health(3)).topology.revision,6);
});
