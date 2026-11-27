import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm,symlink,chmod} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes,generateKeyPairSync,randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
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
  const s=createServer((req,res)=>{if(req.url==='/readiness'){res.statusCode=n.bridgeDown?503:200;return res.end(JSON.stringify({models:{'animagine-xl-4.0':{ready:!n.bridgeDown,free:!n.active}}}));}if(req.url==='/queue'&&n.queueFailure){res.statusCode=503;return res.end('{}');}if(req.url==='/queue')return res.end(JSON.stringify({queue_running:n.active||n.externalImageBusy?[['job']]:[],queue_pending:[]}));if(req.url==='/inventory')return res.end(JSON.stringify({CheckpointLoaderSimple:{input:{required:{ckpt_name:[['animagine-xl-4.0.safetensors']]}}}}));if(req.url==='/slots'){res.setHeader('content-type','application/json');return res.end(JSON.stringify([{is_processing:n.active>0||n.externalBusy===true}]));}
   let data='';req.on('data',b=>data+=b);req.on('end',()=>{const b=JSON.parse(data);const content=b.messages?.[0]?.content??b.prompt;if(content.startsWith('2+3'))return res.end(JSON.stringify({choices:[{message:{content:'5'}}]}));
    n.calls++;n.active++;n.max=Math.max(n.max,n.active);setTimeout(()=>{n.active--;if(content==='break')res.destroy();else res.end(JSON.stringify(req.url==='/image'?{data:[{b64_json:Buffer.from('image-fixture').toString('base64')}]}:{choices:[{message:{content:'OK'}}]}));},150);
   });});s.listen(0,'127.0.0.1');await once(s,'listening');servers.push(s);
  n.compatPort=await port();n.port=await port();n.url=`http://127.0.0.1:${n.port}`;n.stateFile=join(dir,n.node+'-state.json');
  n.config={node:n.node,port:n.port,tokenFile,"compat-port":n.compatPort,"compat-bind":"127.0.0.1", 'token-file':tokenFile,'state-file':n.stateFile,'probe-interval-ms':100,lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:`http://127.0.0.1:${s.address().port}`,path:'/v1/chat/completions','job-timeout-ms':2000}}};
  n.config.lanes.image={kind:'image',model:'animagine-xl-4.0',group:'gpu',backend:`http://127.0.0.1:${s.address().port}`,path:'/image','readiness-url':`http://127.0.0.1:${s.address().port}/inventory`};n.process=await launch('resident.cljk',n.config,n.node);
  await wait(async()=>{const r=await fetch(n.url+'/health',{headers});const h=await r.json();return h.lanes.text.ready&&h.lanes.image.ready;});nodes.push(n);
 }
 nodes[0].externalImageBusy=true;
 await wait(async()=>{const h=await(await fetch(nodes[0].url+'/health',{headers})).json();return h.lanes.image.busy&&!h.lanes.image.ready&&h.lanes.text.busy;});
 const nativeSlots=await(await fetch('http://127.0.0.1:'+nodes[0].compatPort+'/slots')).json();assert.equal(nativeSlots[0].is_processing,true);
 const rejected=await fetch('http://127.0.0.1:'+nodes[0].compatPort+'/v1/chat/completions',{method:'POST',body:JSON.stringify({model:'mishima',messages:[{role:'user',content:'while external image runs'}],max_tokens:32})});assert.equal(rejected.status,429);assert.equal((await rejected.json()).executed,false);
 nodes[0].queueFailure=true;
 await wait(async()=>{const h=await(await fetch(nodes[0].url+'/health',{headers})).json();return h.lanes.image.failures>0;});
 const indeterminate=await(await fetch(nodes[0].url+'/health',{headers})).json();assert.equal(indeterminate.lanes.image.busy,true);assert.equal(indeterminate.lanes.text.busy,true);
 nodes[0].queueFailure=false;
 nodes[0].externalImageBusy=false;
 await wait(async()=>{const h=await(await fetch(nodes[0].url+'/health',{headers})).json();return !h.lanes.text.busy&&h.lanes.image.ready;});
 const staleConfig={...nodes[0].config,'probe-interval-ms':1000};
 nodes[0].process.kill('SIGTERM');await once(nodes[0].process,'exit');nodes[0].process=await launch('resident.cljk',staleConfig,nodes[0].node);
 await wait(async()=>{const h=await(await fetch(nodes[0].url+'/health',{headers})).json();return h.lanes.text.ready&&h.lanes.image.ready&&!h.lanes.text.busy;});
 nodes[0].externalImageBusy=true;
 const beforeCalls=nodes[0].calls;
 const freshGuard=await fetch(nodes[0].url+'/execute/text',{method:'POST',headers:{...headers,'x-murakumo-job-id':'new_external_queue_job'},body:JSON.stringify({model:'mishima',messages:[{role:'user',content:'live queue admission'}],max_tokens:16})});
 assert.equal(freshGuard.status,429);assert.equal((await freshGuard.json()).executed,false);assert.equal(nodes[0].calls,beforeCalls);
 nodes[0].externalImageBusy=false;
 nodes[0].process.kill('SIGTERM');await once(nodes[0].process,'exit');nodes[0].process=await launch('resident.cljk',nodes[0].config,nodes[0].node);
 await wait(async()=>{const h=await(await fetch(nodes[0].url+'/health',{headers})).json();return h.lanes.text.ready&&h.lanes.image.ready;});
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

test('text-only residents guard an optional external image queue without dispatching work',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-external-queue-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex'),key=join(dir,'token');await writeFile(key,token,{mode:0o600});
 let busy=false,bad=false,calls=0;
 const engine=createServer((req,res)=>{if(req.url==='/queue'){res.statusCode=bad?503:200;return res.end(JSON.stringify({queue_running:busy?[['external']]:[],queue_pending:[]}));}if(req.url==='/slots')return res.end('[{"is_processing":false}]');let data='';req.on('data',b=>data+=b);req.on('end',()=>{const b=JSON.parse(data);if(!b.messages[0].content.startsWith('2+3'))calls++;res.end('{"choices":[{"message":{"content":"5"}}]}');});});engine.listen(0,'127.0.0.1');await once(engine,'listening');t.after(()=>{engine.closeAllConnections();engine.close();});
 const p=await port(),ep=engine.address().port,headers={authorization:'Bearer '+token,'content-type':'application/json'};
 const config={node:'text-only',port:p,'token-file':key,'state-file':join(dir,'state.json'),'probe-interval-ms':50,lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:'http://127.0.0.1:'+ep,path:'/v1/chat/completions','external-image-queue-url':'http://127.0.0.1:'+ep+'/queue','external-image-queue-optional?':true}}};
 const f=join(dir,'config.json');await writeFile(f,JSON.stringify(config));const child=spawn(process.execPath,[join(root,'release/node.mjs'),'resident','serve','--config',f],{stdio:'ignore'});t.after(()=>child.kill('SIGTERM'));
 const health=async()=>(await(await fetch('http://127.0.0.1:'+p+'/health',{headers})).json());await wait(async()=>(await health()).lanes.text.ready);
 for(const mode of ['busy','unknown']){busy=mode==='busy';bad=mode==='unknown';const response=await fetch('http://127.0.0.1:'+p+'/execute/text',{method:'POST',headers:{...headers,'x-murakumo-job-id':'guard_'+mode+'_123'},body:JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'actual work'}]})});assert.equal(response.status,429);assert.equal((await response.json()).executed,false);assert.equal(calls,0);}
 busy=false;bad=false;await wait(async()=>(await health()).lanes.text.ready);
 // A genuinely absent optional engine is allowed, while a broken one was denied.
 const absent=await port();config.lanes.text['external-image-queue-url']='http://127.0.0.1:'+absent+'/queue';child.kill('SIGTERM');await once(child,'exit');await writeFile(f,JSON.stringify(config));const restarted=spawn(process.execPath,[join(root,'release/node.mjs'),'resident','serve','--config',f],{stdio:'ignore'});t.after(()=>restarted.kill('SIGTERM'));await wait(async()=>(await health()).lanes.text.ready);
});

test('gateway withdraws unhealthy image ingress after inflight work finishes, and survives all-busy capacity',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-ingress-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex'),key=join(dir,'token'),marker=join(dir,'ingress.log'),wdMarker=join(dir,'watchdog.log'),action=join(dir,'action.mjs'),drain=join(dir,'draining');await writeFile(key,token,{mode:0o600});await writeFile(action,'import fs from "node:fs";fs.appendFileSync(process.argv[2],process.argv[3]+"\\n");');
 let up=true,busy=false;
 const source=createServer((req,res)=>{if(req.url==='/health'){res.statusCode=up?200:503;return res.end(JSON.stringify({node:'image0',lanes:{image:{kind:'image',model:'animagine-xl-4.0',ready:!busy,busy}}}));}req.resume();req.on('end',()=>setTimeout(()=>res.end('{"ok":true}'),350));});source.listen(0,'127.0.0.1');await once(source,'listening');t.after(()=>{source.closeAllConnections();source.close();});
 const p=await port(),headers={authorization:'Bearer '+token,'content-type':'application/json'},url='http://127.0.0.1:'+p;
 const config={node:'ingress-gateway',port:p,'token-file':key,'probe-interval-ms':30,peers:[{node:'image0',url:'http://127.0.0.1:'+source.address().port}],'drain-file':drain,'tunnel-start-argv':[process.execPath,action,marker,'start'],'tunnel-stop-argv':[process.execPath,action,marker,'stop'],'watchdog-argv':[process.execPath,action,wdMarker,'tick'],'watchdog-interval-ms':25};const f=join(dir,'config.json');await writeFile(f,JSON.stringify(config));const child=spawn(process.execPath,[join(root,'release/node.mjs'),'gateway','serve','--config',f],{stdio:'ignore',env:{...process.env,NOTIFY_SOCKET:join(dir,'fake-notify-socket')}});t.after(()=>child.kill('SIGTERM'));
 const events=async()=>{try{return(await readFile(marker,'utf8')).trim().split('\n');}catch{return[];}};const health=async()=>(await(await fetch(url+'/health',{headers})).json());
 await wait(async()=>(await events()).includes('start'));await wait(async()=>{try{return(await readFile(wdMarker,'utf8')).includes('tick');}catch{return false;}});
 busy=true;await sleep(150);assert.equal((await events()).filter(x=>x==='stop').length,0);busy=false;await wait(async()=>(await health()).peers.image0?.lanes.image.ready);
 const response=fetch(url+'/v1/images/generations',{method:'POST',headers,body:JSON.stringify({model:'animagine-xl-4.0',prompt:'fixture'})});await wait(async()=>(await health()).inflight===1);up=false;await sleep(150);assert.equal((await events()).filter(x=>x==='stop').length,0);assert.equal((await response).status,200);await wait(async()=>(await events()).includes('stop'));
 up=true;await wait(async()=>(await events()).filter(x=>x==='start').length===2);
 await writeFile(drain,'maintenance');await wait(async()=>(await health()).draining===true);const denied=await fetch(url+'/v1/images/generations',{method:'POST',headers,body:'{}'});assert.equal(denied.status,503);assert.equal((await denied.json()).executed,false);await wait(async()=>(await events()).filter(x=>x==='stop').length===2);
 await rm(drain);await wait(async()=>(await events()).filter(x=>x==='start').length===3);assert.equal((await health()).inflight,0);
});

test('gateway supports separate inbound-token-file while keeping peer-token for residents and topology',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-inbound-token-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const peerToken=randomBytes(32).toString('hex'),inboundToken=randomBytes(32).toString('hex');
 const peerTokenFile=join(dir,'peer-token'),inboundTokenFile=join(dir,'gateway-token');
 await writeFile(peerTokenFile,peerToken,{mode:0o600});
 await writeFile(inboundTokenFile,inboundToken,{mode:0o600});
 const peerHeaders={authorization:'Bearer '+peerToken,'content-type':'application/json'};
 const inboundHeaders={authorization:'Bearer '+inboundToken,'content-type':'application/json'};
 const {privateKey,publicKey}=generateKeyPairSync('ed25519');
 const opKey=join(dir,'op.pem'),opPub=join(dir,'op.pub');
 await writeFile(opKey,privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
 await writeFile(opPub,publicKey.export({type:'spki',format:'pem'}));
 const backend=createServer((req,res)=>{
  if(req.url==='/slots')return res.end('[{"is_processing":false}]');
  let data='';req.on('data',b=>data+=b);req.on('end',()=>{
   const b=JSON.parse(data);
   res.end(JSON.stringify({choices:[{message:{content:b.messages?.[0]?.content?.startsWith('2+3')?'5':'OK'}}]}));
  });
 });
 backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const rp=await port(),gp=await port(),ru=`http://127.0.0.1:${rp}`,gu=`http://127.0.0.1:${gp}`;
 const ownerId=randomUUID(),topoIn=join(dir,'topo-in.json'),topoOut=join(dir,'topo.json');
 await writeFile(topoIn,JSON.stringify({schema:1,revision:1,nodes:[{node:'n0',url:ru,'owner-id':ownerId,enabled:true,roles:[{kind:'text',model:'mishima'}]}],gateways:[{node:'g0',url:gu,enabled:true}],policies:{'minimum-replicas':{mishima:1}}}));
 execFileSync(process.execPath,[join(root,'release/node.mjs'),'topology','sign','--input',topoIn,'--output',topoOut,'--private-key-file',opKey,'--allow-loopback','true'],{stdio:'pipe'});
 const stateFile=join(dir,'receipts.json');await writeFile(stateFile+'.owner-id',ownerId,{mode:0o600});
 const source=process.env.MURAKUMO_TEST_SOURCE==='1';
 const procs=[];t.after(()=>{for(const p of procs)p.kill('SIGTERM');});
 async function spawnSvc(kind,cfg,name){
  const f=join(dir,name+'.json');await writeFile(f,JSON.stringify(cfg));
  const script=kind==='resident'?'resident.cljk':'fleet-gateway.cljk';
  const p=spawn(source?'kbb':process.execPath,source?['--backend','sci','--classpath',join(root,'src'),join(root,'scripts',script),'serve','--config',f]:[join(root,'release/node.mjs'),kind,'serve','--config',f],{cwd:dir,stdio:['ignore','pipe','pipe']});
  procs.push(p);return p;
 }
 await spawnSvc('resident',{node:'n0',bind:'127.0.0.1',port:rp,'token-file':peerTokenFile,'state-file':stateFile,'legacy-empty-ledger-owner-id':ownerId,'topology-file':topoOut,'topology-public-key-file':opPub,'allow-loopback-topology?':true,'probe-interval-ms':5000,'topology-poll-ms':60000,lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:`http://127.0.0.1:${backend.address().port}`,path:'/v1/chat/completions'}}},'r0');
 await spawnSvc('gateway',{node:'g0',bind:'127.0.0.1',port:gp,'token-file':peerTokenFile,'inbound-token-file':inboundTokenFile,'topology-file':topoOut,'topology-public-key-file':opPub,'allow-loopback-topology?':true,'probe-interval-ms':50,'topology-poll-ms':60000},'g0');
 await wait(async()=>{const r=await fetch(gu+'/health',{headers:inboundHeaders});if(r.status!==200)return false;const l=(await r.json()).peers?.n0?.lanes?.text;return l?.ready===true&&l?.busy===false;});
 // Gateway accepts inbound-token and rejects peer-token on caller routes.
 assert.equal((await fetch(gu+'/health',{headers:peerHeaders})).status,401);
 assert.equal((await fetch(gu+'/health')).status,401);
 const body=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'hello'}]});
 assert.equal((await fetch(gu+'/v1/chat/completions',{method:'POST',headers:peerHeaders,body})).status,401);
 const ok=await fetch(gu+'/v1/chat/completions',{method:'POST',headers:inboundHeaders,body});
 assert.equal(ok.status,200);assert.equal((await ok.json()).choices[0].message.content,'OK');
 // Resident rejects inbound-token and accepts peer-token.
 assert.equal((await fetch(ru+'/health',{headers:inboundHeaders})).status,401);
 assert.equal((await fetch(ru+'/execute/text',{method:'POST',headers:{...inboundHeaders,'x-murakumo-job-id':'inbound_rejected_123'},body})).status,401);
 assert.equal((await fetch(ru+'/health',{headers:peerHeaders})).status,200);
 // Gateway /topology stays scoped to peer-token so resident gossip works and inbound-token cannot access it.
 assert.equal((await fetch(gu+'/topology',{headers:inboundHeaders})).status,401);
 assert.equal((await fetch(gu+'/topology',{headers:peerHeaders})).status,200);
 // Invalid inbound-token-file (non-0600, symlink, or <32 chars) fails startup closed.
 const badMode=join(dir,'bad-mode');await writeFile(badMode,inboundToken,{mode:0o644});await chmod(badMode,0o644);
 const badLink=join(dir,'bad-link');await symlink(inboundTokenFile,badLink);
 const badShort=join(dir,'bad-short');await writeFile(badShort,'too-short',{mode:0o600});
 for(const [label,badFile] of [['mode',badMode],['symlink',badLink],['short',badShort]]){
  const p=await spawnSvc('gateway',{node:'gbad',bind:'127.0.0.1',port:await port(),'token-file':peerTokenFile,'inbound-token-file':badFile,peers:[{node:'n0',url:ru}]},'gbad-'+label);
  const [code]=await once(p,'exit');assert.notEqual(code,0,`expected non-zero exit for ${label}`);
 }
});

test('gateway enforces optional inbound-allowed-routes allowlist and rejects invalid route lists',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-allowed-routes-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const peerToken=randomBytes(32).toString('hex'),inboundToken=randomBytes(32).toString('hex');
 const peerTokenFile=join(dir,'peer-token'),inboundTokenFile=join(dir,'gateway-token');
 await writeFile(peerTokenFile,peerToken,{mode:0o600});
 await writeFile(inboundTokenFile,inboundToken,{mode:0o600});
 const peerHeaders={authorization:'Bearer '+peerToken,'content-type':'application/json'};
 const inboundHeaders={authorization:'Bearer '+inboundToken,'content-type':'application/json'};
 const {privateKey,publicKey}=generateKeyPairSync('ed25519');
 const opKey=join(dir,'op.pem'),opPub=join(dir,'op.pub');
 await writeFile(opKey,privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
 await writeFile(opPub,publicKey.export({type:'spki',format:'pem'}));
 const backend=createServer((req,res)=>{
  if(req.url==='/slots')return res.end('[{"is_processing":false}]');
  let data='';req.on('data',b=>data+=b);req.on('end',()=>{
   const b=JSON.parse(data);
   res.end(JSON.stringify({choices:[{message:{content:b.messages?.[0]?.content?.startsWith('2+3')?'5':'OK'}}]}));
  });
 });
 backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const rp=await port(),gp=await port(),ru=`http://127.0.0.1:${rp}`,gu=`http://127.0.0.1:${gp}`;
 const ownerId=randomUUID(),topoIn=join(dir,'topo-in.json'),topoOut=join(dir,'topo.json');
 await writeFile(topoIn,JSON.stringify({schema:1,revision:1,nodes:[{node:'n0',url:ru,'owner-id':ownerId,enabled:true,roles:[{kind:'text',model:'mishima'}]}],gateways:[{node:'g0',url:gu,enabled:true}],policies:{'minimum-replicas':{mishima:1}}}));
 execFileSync(process.execPath,[join(root,'release/node.mjs'),'topology','sign','--input',topoIn,'--output',topoOut,'--private-key-file',opKey,'--allow-loopback','true'],{stdio:'pipe'});
 const stateFile=join(dir,'receipts.json');await writeFile(stateFile+'.owner-id',ownerId,{mode:0o600});
 const source=process.env.MURAKUMO_TEST_SOURCE==='1';
 const procs=[];t.after(()=>{for(const p of procs)p.kill('SIGTERM');});
 async function spawnSvc(kind,cfg,name){
  const f=join(dir,name+'.json');await writeFile(f,JSON.stringify(cfg));
  const script=kind==='resident'?'resident.cljk':'fleet-gateway.cljk';
  const p=spawn(source?'kbb':process.execPath,source?['--backend','sci','--classpath',join(root,'src'),join(root,'scripts',script),'serve','--config',f]:[join(root,'release/node.mjs'),kind,'serve','--config',f],{cwd:dir,stdio:['ignore','pipe','pipe']});
  procs.push(p);return p;
 }
 await spawnSvc('resident',{node:'n0',bind:'127.0.0.1',port:rp,'token-file':peerTokenFile,'state-file':stateFile,'legacy-empty-ledger-owner-id':ownerId,'topology-file':topoOut,'topology-public-key-file':opPub,'allow-loopback-topology?':true,'probe-interval-ms':5000,'topology-poll-ms':60000,lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:`http://127.0.0.1:${backend.address().port}`,path:'/v1/chat/completions'}}},'r0');
 await spawnSvc('gateway',{node:'g0',bind:'127.0.0.1',port:gp,'token-file':peerTokenFile,'inbound-token-file':inboundTokenFile,'inbound-allowed-routes':['GET /health','POST /v1/chat/completions'],'topology-file':topoOut,'topology-public-key-file':opPub,'allow-loopback-topology?':true,'probe-interval-ms':50,'topology-poll-ms':60000},'g0');
 await wait(async()=>{const r=await fetch(gu+'/health',{headers:inboundHeaders});if(r.status!==200)return false;const l=(await r.json()).peers?.n0?.lanes?.text;return l?.ready===true&&l?.busy===false;});
 // Allowed routes succeed.
 const chatBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'hello'}]});
 const chat=await fetch(gu+'/v1/chat/completions',{method:'POST',headers:inboundHeaders,body:chatBody});
 assert.equal(chat.status,200);assert.equal((await chat.json()).choices[0].message.content,'OK');
 // Disallowed routes return 403 route_not_allowed (while unauthenticated stays 401).
 for(const [method,path,hdrs,body] of [
  ['GET','/jobs/job_12345678',inboundHeaders,undefined],
  ['POST','/jobs/job_12345678',inboundHeaders,'{}'],
  ['POST','/v1/images/generations',inboundHeaders,JSON.stringify({model:'animagine-xl-4.0',prompt:'x'})],
  ['GET','/topology',peerHeaders,undefined],
  ['POST','/topology',peerHeaders,'{}'],
 ]){
  const r=await fetch(gu+path,{method,headers:hdrs,body});
  assert.equal(r.status,403,`expected 403 for ${method} ${path}`);
  assert.equal((await r.json()).error,'route_not_allowed');
 }
 assert.equal((await fetch(gu+'/topology',{headers:inboundHeaders})).status,401);
 assert.equal((await fetch(gu+'/jobs/job_12345678')).status,401);
 // Static-peer gateway without topology also blocks /topology with 403 when restricted.
 const gp2=await port(),gu2=`http://127.0.0.1:${gp2}`;
 await spawnSvc('gateway',{node:'g1',bind:'127.0.0.1',port:gp2,'token-file':peerTokenFile,'inbound-token-file':inboundTokenFile,'inbound-allowed-routes':['GET /health','POST /v1/chat/completions'],peers:[{node:'n0',url:ru}],'probe-interval-ms':50},'g1');
 await wait(async()=>(await fetch(gu2+'/health',{headers:inboundHeaders})).status===200);
 const topoStatic=await fetch(gu2+'/topology',{headers:inboundHeaders});
 assert.equal(topoStatic.status,403);assert.equal((await topoStatic.json()).error,'route_not_allowed');
 // Invalid inbound-allowed-routes fail startup closed.
 for(const [label,badRoutes] of [['empty',[]],['unknown',['GET /nope']],['not-array','GET /health']]){
  const p=await spawnSvc('gateway',{node:'gbad',bind:'127.0.0.1',port:await port(),'token-file':peerTokenFile,'inbound-allowed-routes':badRoutes,peers:[{node:'n0',url:ru}]},'gbad-routes-'+label);
  const [code]=await once(p,'exit');assert.notEqual(code,0,`expected non-zero exit for ${label}`);
 }
});
