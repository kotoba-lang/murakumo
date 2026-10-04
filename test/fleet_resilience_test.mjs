import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes} from 'node:crypto';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const root=resolve('.');
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function wait(fn){for(let i=0;i<100;i++){try{if(await fn())return;}catch{}await sleep(50);}throw Error('condition timed out');}
test('two entrypoints share node-owned slots, survive peer loss and do not replay unknown work',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-fleet-test-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');const tokenFile=join(dir,'token');await writeFile(tokenFile,token,{mode:0o600});
 const headers={'authorization':`Bearer ${token}`,'content-type':'application/json'};
 const processes=[];const servers=[];
 t.after(async()=>{for(const p of processes)p.kill('SIGTERM');for(const s of servers){s.closeAllConnections();s.close();}});
 const nodes=[];
 async function launch(script,config,name){const path=join(dir,name+'.json');await writeFile(path,JSON.stringify(config));const source=process.env.MURAKUMO_TEST_SOURCE==='1';const p=spawn(source?'kbb':process.execPath,source?['--backend','sci','--classpath',join(root,'src'),join(root,'scripts',script),'serve','--config',path]:[join(root,'release/node.mjs'),script==='resident.cljk'?'resident':'gateway','serve','--config',path],{cwd:dir,stdio:['ignore','pipe','pipe']});let output='';p.stderr.on('data',b=>output+=b);processes.push(p);await wait(async()=>{if(p.exitCode!==null)throw Error(output);try{return (await fetch(`http://127.0.0.1:${config.port}/health`,{headers})).status!==404;}catch{return false;}});return p;}
 for(let i=0;i<2;i++){
  const n={node:'n'+i,active:0,max:0,calls:0};
  const s=createServer((req,res)=>{if(req.url==='/readiness'){res.statusCode=n.bridgeDown?503:200;return res.end(JSON.stringify({models:{'animagine-xl-4.0':{ready:!n.bridgeDown,free:!n.active}}}));}if(req.url==='/queue')return res.end(JSON.stringify({queue_running:n.active?[['job']]:[],queue_pending:[]}));if(req.url==='/inventory')return res.end(JSON.stringify({CheckpointLoaderSimple:{input:{required:{ckpt_name:[['animagine-xl-4.0.safetensors']]}}}}));if(req.url==='/slots'){res.setHeader('content-type','application/json');return res.end(JSON.stringify([{is_processing:n.active>0||n.externalBusy===true}]));}
   let data='';req.on('data',b=>data+=b);req.on('end',()=>{const b=JSON.parse(data);const content=b.messages?.[0]?.content??b.prompt;if(content.startsWith('2+3'))return res.end(JSON.stringify({choices:[{message:{content:'5'}}]}));
    n.calls++;n.active++;n.max=Math.max(n.max,n.active);setTimeout(()=>{n.active--;if(content==='break')res.destroy();else res.end(JSON.stringify(req.url==='/image'?{data:[{b64_json:Buffer.from('image-fixture').toString('base64')}]}:{choices:[{message:{content:'OK'}}]}));},150);
   });});s.listen(0,'127.0.0.1');await once(s,'listening');servers.push(s);
  n.compatPort=await port();n.port=await port();n.url=`http://127.0.0.1:${n.port}`;n.stateFile=join(dir,n.node+'-state.json');
  n.config={node:n.node,port:n.port,tokenFile,"compat-port":n.compatPort,"compat-bind":"127.0.0.1", 'token-file':tokenFile,'state-file':n.stateFile,'probe-interval-ms':100,lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:`http://127.0.0.1:${s.address().port}`,path:'/v1/chat/completions','job-timeout-ms':2000}}};
  n.config.lanes.image={kind:'image',model:'animagine-xl-4.0',group:'gpu',backend:`http://127.0.0.1:${s.address().port}`,path:'/image','readiness-url':`http://127.0.0.1:${s.address().port}/inventory`};n.process=await launch('resident.cljk',n.config,n.node);
  await wait(async()=>{const r=await fetch(n.url+'/health',{headers});const h=await r.json();return h.lanes.text.ready&&h.lanes.image.ready;});nodes.push(n);
 }
 nodes[0].bridgeDown=true;
 await wait(async()=>{const h=await(await fetch(nodes[0].url+"/health",{headers})).json();return !h.lanes.image.ready&&h.lanes.text.ready;});
 nodes[0].bridgeDown=false;
 await wait(async()=>{const h=await(await fetch(nodes[0].url+"/health",{headers})).json();return h.lanes.image.ready;});
 const gateways=[];
 for(let i=0;i<2;i++){const p=await port(),compatPort=await port();const config={node:'g'+i,port:p,'compat-image-port':compatPort,'token-file':tokenFile,'probe-interval-ms':50,peers:nodes.map(n=>({node:n.node,url:n.url}))};const process=await launch('fleet-gateway.cljk',config,'g'+i);const url=`http://127.0.0.1:${p}`;await wait(async()=>{const r=await fetch(url+'/health',{headers});return r.ok&&Object.keys((await r.json()).peers).length===2;});gateways.push({url,process,compatPort});}
 const body=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'hello'}]});
 assert.equal((await fetch(nodes[0].url+'/health')).status,401);
 // Different gateways race on the same resident: only its atomic admission may dispatch.
 const request=(g,content=body,extra={})=>fetch(g.url+'/v1/chat/completions',{method:'POST',headers:{...headers,...extra},body:content});
 // External occupancy is capacity, never a failure or a dead gateway.
 for(const n of nodes)n.externalBusy=true;
 await wait(async()=>{const states=await Promise.all(nodes.map(async n=>(await(await fetch(n.url+"/health",{headers})).json()).lanes.text));return states.every(x=>x.busy&&!x.ready&&x.failures===0);});
 await wait(async()=>{const r=await fetch(gateways[0].url+"/health",{headers});const h=await r.json();return r.status===200&&Object.values(h.peers).every(p=>p.lanes.text.busy);});
 const occupied=await request(gateways[0]);assert.equal(occupied.status,429);assert.equal((await occupied.json()).executed,false);
 for(const n of nodes)n.externalBusy=false;
 await wait(async()=>{const h=await(await fetch(gateways[0].url+"/health",{headers})).json();return Object.values(h.peers).every(p=>p.lanes.text.ready&&!p.lanes.text.busy);});
 const rs=await Promise.all(Array.from({length:8},(_,i)=>i<2?fetch(`http://127.0.0.1:${nodes[i].compatPort}/v1/chat/completions`,{method:"POST",headers:{"content-type":"application/json"},body}):request(gateways[i%2])));const successful=rs.filter(r=>r.ok);assert.ok(successful.length>=2);await Promise.all(rs.map(r=>r.text()));
 assert.equal(nodes[0].max,1);assert.equal(nodes[1].max,1);assert.ok(nodes.every(n=>n.calls>0));
 const first=successful[0];const id=first.headers.get('x-murakumo-job-id');const owner=nodes.find(n=>id.startsWith(n.node+'_'));const count=owner.calls;
 const replay=await request(gateways[1],body,{'x-murakumo-job-id':id});assert.equal(replay.status,409);assert.equal(owner.calls,count);
 const changed=await request(gateways[0],body.replace('hello','different'),{'x-murakumo-job-id':id});assert.equal(changed.status,422);
 // After resident restart, receipts remain and successful jobs are not re-executed.
 owner.process.kill('SIGTERM');await once(owner.process,'exit');owner.process=await launch('resident.cljk',owner.config,owner.node);
 const again=await request(gateways[0],body,{'x-murakumo-job-id':id});assert.equal(again.status,409);assert.equal(owner.calls,count);
 // Dead peer disappears; surviving peer accepts new work.
 nodes[1].process.kill('SIGTERM');await once(nodes[1].process,'exit');
 await wait(async()=>{const r=await fetch(gateways[0].url+'/health',{headers});return r.ok&&Object.keys((await r.json()).peers).length===1;});
 await wait(async()=>{const r=await fetch(nodes[0].url+'/health',{headers});return (await r.json()).lanes.text.ready;});
 let live;await wait(async()=>{live=await request(gateways[0]);if(live.status===429){assert.equal((await live.json()).executed,false);return false;}return true;});assert.equal(live.status,200);assert.equal(live.headers.get('x-murakumo-node'),'n0');await live.text();
 await sleep(200);
 await wait(async()=>{const imageState=await(await fetch(`http://127.0.0.1:${gateways[0].compatPort}/readiness`)).json();return imageState.models['animagine-xl-4.0'].free;});
 const forbidden=await fetch(`http://127.0.0.1:${gateways[0].compatPort}/v1/chat/completions`,{method:'POST',body});assert.equal(forbidden.status,404);
 let image;await wait(async()=>{image=await fetch(`http://127.0.0.1:${gateways[0].compatPort}/v1/images/generations`,{method:'POST',body:JSON.stringify({model:'animagine-xl-4.0',prompt:'image'})});if(image.status===429){assert.equal((await image.json()).executed,false);return false;}return true;});assert.equal(image.status,200);assert.equal(Buffer.from((await image.json()).data[0].b64_json,'base64').toString(),'image-fixture');await sleep(200);
 gateways[0].process.kill('SIGTERM');await once(gateways[0].process,'exit');
 let bad;
 await wait(async()=>{bad=await request(gateways[1],body.replace("hello","break"));if(bad.status===429){assert.equal((await bad.json()).executed,false);return false;}return true;});  assert.equal(bad.status,502);const failure=await bad.json();assert.equal(failure.retryable,false);assert.equal(failure.error,'execution_unknown');
 assert.equal(nodes[0].max,1);
 const ledger=JSON.parse(await readFile(nodes[0].stateFile,'utf8'));assert.ok(Object.values(ledger.jobs).some(j=>j.status==='unknown'),JSON.stringify({ledger,failure}));assert.deepEqual(ledger.groups,{});
});
test('local declared repair runs once and readiness returns only after a successful canary',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-heal-test-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');const key=join(dir,'token');const marker=join(dir,'repaired');await writeFile(key,token,{mode:0o600});
 const repair=join(dir,'repair.mjs');await writeFile(repair,`import fs from 'node:fs';fs.appendFileSync(${JSON.stringify(marker)},'restart\\n');`);
 const s=createServer(async(req,res)=>{let repaired=false;try{await readFile(marker);repaired=true;}catch{}if(!repaired){res.statusCode=503;return res.end('{}');}if(req.url==='/slots')return res.end('[{"is_processing":false}]');return res.end('{"choices":[{"message":{"content":"5"}}]}');});s.listen(0,'127.0.0.1');await once(s,'listening');t.after(()=>{s.closeAllConnections();s.close();});
 const p=await port();const config={node:'heal-node',port:p,'token-file':key,'state-file':join(dir,'state.json'),'probe-interval-ms':50,lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:`http://127.0.0.1:${s.address().port}`,path:'/v1/chat/completions','restart-argv':[process.execPath,repair],'cooldown-ms':30000}}};
 const file=join(dir,'config.json');await writeFile(file,JSON.stringify(config));const child=spawn(process.execPath,[join(root,'release/node.mjs'),'resident','serve','--config',file],{cwd:dir,stdio:['ignore','pipe','pipe']});t.after(()=>child.kill('SIGTERM'));let log='';child.stdout.on('data',b=>log+=b);
 await wait(async()=>{const r=await fetch(`http://127.0.0.1:${p}/health`,{headers:{authorization:`Bearer ${token}`}});return (await r.json()).lanes.text.ready;});
 assert.equal((await readFile(marker,'utf8')).trim(),'restart');assert.match(log,/"event":"heal"/);await sleep(200);assert.equal((await readFile(marker,'utf8')).trim(),'restart');
});
