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
async function port(){const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function wait(fn){for(let i=0;i<100;i++){try{if(await fn())return;}catch{}await new Promise(r=>setTimeout(r,50));}throw Error('readiness timeout');}
test('vision uses bounded image tokens through gateway and resident; text and remote URLs remain refused',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-vision-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex'),tokenFile=join(dir,'token');await writeFile(tokenFile,token,{mode:0o600});
 const headers={authorization:'Bearer '+token,'content-type':'application/json'};
 let calls=0;
 const backend=createServer((req,res)=>{if(req.url==='/slots')return res.end('[{"is_processing":false}]');let bytes='';req.on('data',b=>bytes+=b);req.on('end',()=>{const b=JSON.parse(bytes);if(typeof b.messages[0].content==='string')return res.end('{"choices":[{"message":{"content":"5"}}]}');calls++;res.end(JSON.stringify({choices:[{message:{content:String(b.messages[0].content[0].image_url.url.length)}}]}));});});
 backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{backend.closeAllConnections();backend.close();});
 const rp=await port(),gp=await port(),ru='http://127.0.0.1:'+rp,gu='http://127.0.0.1:'+gp;
 const processes=[];t.after(()=>processes.forEach(p=>p.kill('SIGTERM')));
 async function launch(kind,config){const f=join(dir,kind+'.json');await writeFile(f,JSON.stringify(config));processes.push(spawn(process.execPath,[cli,kind,'serve','--config',f],{stdio:'ignore'}));}
 await launch('resident',{node:'vision',bind:'127.0.0.1',port:rp,'token-file':tokenFile,'state-file':join(dir,'receipts.json'),'probe-interval-ms':50,lanes:{vision:{kind:'text',model:'vl',context:4096,'max-image-tokens':1024,'max-images':1,group:'gpu',backend:'http://127.0.0.1:'+backend.address().port,path:'/v1/chat/completions'}}});
 await launch('gateway',{node:'g',bind:'127.0.0.1',port:gp,'token-file':tokenFile,'probe-interval-ms':50,peers:[{node:'vision',url:ru}]});
 await wait(async()=>{const l=(await(await fetch(gu+'/health',{headers})).json()).peers?.vision?.lanes.vision;return l?.ready&&!l.busy;});
 const body={model:'vl',max_tokens:64,messages:[{role:'user',content:[{type:'image_url',image_url:{url:'data:image/png;base64,'+'A'.repeat(500000)}}]}]};
 let response=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(body)});assert.equal(response.status,200);assert.equal((await response.json()).choices[0].message.content,'500022');assert.equal(calls,1);
 const remote=structuredClone(body);remote.messages[0].content[0].image_url.url='http://127.0.0.1/private';
 response=await fetch(ru+'/execute/vision',{method:'POST',headers:{...headers,'x-murakumo-job-id':'remote-url-refused'},body:JSON.stringify(remote)});assert.equal(response.status,422);assert.equal(calls,1);
 const tooMany=structuredClone(body);tooMany.messages[0].content.push(tooMany.messages[0].content[0]);
 response=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(tooMany)});assert.equal(response.status,429);assert.equal(calls,1);
 const text=structuredClone(body);text.messages[0].content='X'.repeat(5000);
 response=await fetch(gu+'/v1/chat/completions',{method:'POST',headers,body:JSON.stringify(text)});assert.equal(response.status,429);assert.equal(calls,1);
});
