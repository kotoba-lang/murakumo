import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer,get} from 'node:http';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes} from 'node:crypto';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const root=resolve('.');
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function wait(fn){for(let i=0;i<300;i++){try{if(await fn())return;}catch{}await sleep(50);}throw Error('condition timed out');}
// A llama-server stand-in: advertises Keep-Alive timeout=2 and FIN-closes each socket after 2 s idle.
// An 'end' on a socket it has not ended itself is a FIN from the resident, i.e. the client closed first.
async function backend(){
 const b={connections:0,clientClosedFirst:0};
 const s=createServer((req,res)=>{const sk=req.socket;clearTimeout(sk.idle);res.on('finish',()=>{sk.idle=setTimeout(()=>{sk.serverEnded=true;sk.end();},2000);});
  if(req.url==='/slots')return res.end('[{"is_processing":false}]');req.resume();req.on('end',()=>res.end(JSON.stringify({choices:[{message:{content:'5'}}]})));});
 s.keepAliveTimeout=2000;
 s.on('connection',sk=>{b.connections++;sk.on('end',()=>{clearTimeout(sk.idle);if(!sk.serverEnded)b.clientClosedFirst++;});});
 s.listen(0,'127.0.0.1');await once(s,'listening');b.server=s;b.url='http://127.0.0.1:'+s.address().port;return b;}
test('resident probes reuse backend sockets and never close an idle socket first',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-keepalive-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex'),tokenFile=join(dir,'token');await writeFile(tokenFile,token,{mode:0o600});
 const headers={authorization:'Bearer '+token};
 const processes=[],backends=[];
 t.after(()=>{for(const p of processes)p.kill('SIGTERM');for(const b of backends){b.server.closeAllConnections();b.server.close();}});
 async function resident(name,b,probeMs){
  const p=await port(),path=join(dir,name+'.json');
  await writeFile(path,JSON.stringify({node:name,port:p,bind:'127.0.0.1','token-file':tokenFile,'state-file':join(dir,name+'-state.json'),'probe-interval-ms':probeMs,lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:b.url,path:'/v1/chat/completions'}}}));
  const proc=spawn(process.execPath,[join(root,'release/node.mjs'),'resident','serve','--config',path],{cwd:dir,stdio:['ignore','pipe','pipe']});processes.push(proc);
  const url='http://127.0.0.1:'+p;
  await wait(async()=>(await(await fetch(url+'/health',{headers})).json()).lanes.text.ready);
  return url;}
 // Probes inside the backend's keep-alive window share one socket.
 const warm=await backend();backends.push(warm);
 const url=await resident('warm',warm,100);
 await sleep(1500);
 assert.equal(warm.connections,1);
 // Probes slower than the backend's keep-alive: the backend closes each idle socket, never the resident.
 const cold=await backend();backends.push(cold);
 await resident('cold',cold,2600);
 await sleep(6000);
 assert.ok(cold.connections>=2,'expected one socket per probe, got '+cold.connections);
 assert.equal(cold.clientClosedFirst,0);
 // The resident's own server advertises a keep-alive longer than peer topology polls.
 const res=await new Promise((ok,fail)=>get(url+'/health',{headers},ok).on('error',fail));res.resume();
 assert.match(res.headers['keep-alive']??'',/timeout=120\b/);
});
