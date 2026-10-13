import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes} from 'node:crypto';
const cli=resolve('release/node.mjs');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function wait(fn){for(let i=0;i<160;i++){try{if(await fn())return;}catch{}await delay(50);}throw Error('readiness timeout');}
test('backend tokenizer admits a large byte payload to 32K without weakening context or auth gates',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-tokenizer-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex'),tokenFile=join(dir,'token');await writeFile(tokenFile,token,{mode:0o600});
 const headers={authorization:'Bearer '+token,'content-type':'application/json'};
 let templates=0,executions=0,tokenizerBroken=false;const received=[];
 const backend=createServer((req,res)=>{
  if(req.url==='/slots')return res.end('[{"is_processing":false}]');
  let text='';req.on('data',b=>text+=b);req.on('end',()=>{
   const body=JSON.parse(text);
   if(req.url==='/apply-template'){templates++;if(tokenizerBroken){res.statusCode=404;return res.end('{}');}assert.equal(body.add_generation_prompt,true);return res.end(JSON.stringify({prompt:body.messages.map(m=>m.content).join('\n')}));}
   if(req.url==='/tokenize'){assert.equal(body.parse_special,true);assert.equal(body.add_special,false);return res.end(JSON.stringify({tokens:Array(body.content.includes('REAL_TOKEN_OVERFLOW')?40000:8000).fill(1)}));}
   if(body.stream){
    res.writeHead(200,{'content-type':'text/event-stream'});res.flushHeaders();
    setTimeout(()=>{res.write('data: {"model":"mishima","choices":[{"delta":{"content":"');
      setTimeout(()=>res.end('OK"}}]}\n\ndata: [DONE]\n\n'),250);},900);return;
   }
   if(body.messages?.some(m=>m.content.length>10000)){executions++;received.push(body);}
   res.end(JSON.stringify({model:'mishima',choices:[{message:{content:body.messages?.some(m=>m.content.length>10000)?'OK':'5'}}]}));
  });
 });backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const rp=await port(),gp=await port(),ru='http://127.0.0.1:'+rp,gu='http://127.0.0.1:'+gp;
 const children=[];t.after(()=>children.forEach(p=>p.kill('SIGTERM')));
 async function launch(kind,c){const f=join(dir,kind+'.json');await writeFile(f,JSON.stringify(c));const p=spawn(process.execPath,[cli,kind,'serve','--config',f],{stdio:['ignore','pipe','pipe']});children.push(p);p.stderr.on('data',b=>process.stderr.write(b));}
 await launch('resident',{node:'n',bind:'127.0.0.1',port:rp,'token-file':tokenFile,'state-file':join(dir,'receipts.json'),'probe-interval-ms':30000,'heartbeat-ms':50,lanes:{legacy:{kind:'text',model:'mishima',context:32768,group:'legacy',backend:'http://127.0.0.1:'+backend.address().port,path:'/v1/chat/completions'},text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',backend:'http://127.0.0.1:'+backend.address().port,path:'/v1/chat/completions'}}});
 await launch('gateway',{node:'g',bind:'127.0.0.1',port:gp,'token-file':tokenFile,'probe-interval-ms':50,peers:[{node:'n',url:ru}]});
 await wait(async()=>{const l=(await(await fetch(gu+'/health',{headers})).json()).peers?.n?.lanes.text;return l?.ready&&!l.busy&&l['input-tokenizer']==='llama-template';});
 const body={model:'mishima',max_tokens:64,messages:[{role:'system',content:'Harmless public fixture. '.repeat(2300)},{role:'user',content:'Reply OK.'}]};
 assert.ok(Buffer.byteLength(JSON.stringify(body))>32768);
 let r=await fetch(ru+'/estimate/text',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)});assert.equal(r.status,401);
 r=await fetch(ru+'/estimate/text',{method:'POST',headers,body:JSON.stringify(body)});const estimate=await r.json();assert.equal(r.status,200);assert.equal(estimate['input-tokens'],8128);assert.equal(estimate.source,'backend-tokenizer');assert.ok(!JSON.stringify(estimate).includes('Harmless'));assert.match(estimate.digest,/^[a-f0-9]{64}$/);
 r=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(body)});assert.equal(r.status,200);const jobId=r.headers.get('x-murakumo-job-id');assert.equal((await r.json()).choices[0].message.content,'OK');assert.equal(executions,1);const beforeReplay=templates;r=await fetch(ru+'/execute/text',{method:'POST',headers:{...headers,'x-murakumo-job-id':jobId},body:JSON.stringify(body)});assert.equal(r.status,409);assert.equal(templates,beforeReplay,'known job does not contact tokenizer');assert.deepEqual(received[0].messages,body.messages);assert.ok(templates>=3);
 await wait(async()=>(await(await fetch(gu+'/health',{headers})).json()).peers.n.lanes.text.busy===false);
 const overflow=structuredClone(body);overflow.messages[0].content='REAL_TOKEN_OVERFLOW '+overflow.messages[0].content;
 r=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(overflow)});assert.equal(r.status,429);assert.equal(executions,1);
 r=await fetch(ru+'/execute/text',{method:'POST',headers:{...headers,'x-murakumo-job-id':'direct-overflow-123'},body:JSON.stringify(overflow)});assert.equal(r.status,422);assert.equal(executions,1);
 const largeOutput={...body,max_tokens:32768};
 r=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(largeOutput)});assert.equal(r.status,200);await r.json();assert.equal(received.at(-1).max_tokens,32768-8128,'output is bounded by actual head context');
 await wait(async()=>(await(await fetch(gu+'/health',{headers})).json()).peers.n.lanes.text.busy===false);
 const streaming={model:'mishima',max_tokens:64,stream:true,messages:[{role:'user',content:'Reply OK.'}]};
 const started=Date.now();r=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(streaming)});
 assert.equal(r.status,200);const reader=r.body.getReader();let part=await reader.read();let text=Buffer.from(part.value).toString();
 assert.ok(Date.now()-started<700,'prefill heartbeat arrived before first backend byte');
 assert.ok(text.includes('chat.completion.chunk'));assert.ok(text.includes('"choices":[]'));
 while(!(part=await reader.read()).done)text+=Buffer.from(part.value).toString();
 const events=text.split('\n').filter(l=>l.startsWith('data: ')&&l!=='data: [DONE]').map(l=>JSON.parse(l.slice(6)));
 assert.equal(events.filter(e=>e.choices?.[0]?.delta?.content==='OK').length,1,'heartbeat never corrupts a split upstream event');
 tokenizerBroken=true;
 r=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(body)});assert.equal(r.status,429);assert.equal(executions,2);
});
