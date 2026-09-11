import {spawn} from 'node:child_process';
import {mkdtempSync,readFileSync,statSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
const home=mkdtempSync(path.join(tmpdir(),'murakumo-node-test-'));
let denied=false;const calls=[];
const server=http.createServer(async(req,res)=>{
 let body='';for await(const c of req)body+=c;
 calls.push({url:req.url,auth:req.headers.authorization,body:body?JSON.parse(body):null});
 res.setHeader('content-type','application/json');
 if(req.url==='/v1/models')return res.end(JSON.stringify({data:[{id:'test-model'}]}));
 if(req.url==='/infer/nodes'){res.statusCode=denied?401:201;return res.end(JSON.stringify({'node/admission':'pending'}));}
 if(req.url.endsWith('/heartbeat')){res.statusCode=201;return res.end('{}');}
 res.statusCode=404;res.end('{}');
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const base=`http://127.0.0.1:${server.address().port}`;
const run=(...args)=>new Promise(resolve=>{
 const p=spawn(process.execPath,['release/node.mjs','node',...args],{env:{...process.env,MURAKUMO_NODE_HOME:home,MURAKUMO_NODE_CACAO:'',MURAKUMO_NODE_DID:'',MURAKUMO_SERVICE_TOKEN:''}});
 let out='';p.stdout.on('data',d=>out+=d);p.stderr.on('data',d=>out+=d);
 p.on('close',code=>resolve({code,out}));
});
try{
 assert.equal((await run('init')).code,0);const original=readFileSync(path.join(home,'identity.json'),'utf8');
 assert.equal(statSync(path.join(home,'identity.json')).mode&0o777,0o600);
 assert.equal((await run('init')).code,0);assert.equal(readFileSync(path.join(home,'identity.json'),'utf8'),original);
 const flags=['--model','test-model','--local-url',base+'/v1','--base',base,'--name','test-node'];
 assert.equal((await run('doctor',...flags)).code,0);assert.equal(calls.filter(x=>x.url.startsWith('/infer/')).length,0);
 assert.notEqual((await run('doctor','--model','wrong','--local-url',base+'/v1')).code,0);
 assert.notEqual((await run('doctor','--model')).code,0);
 let result=await run('check',...flags);assert.equal(result.code,0,result.out);assert.match(result.out,/heartbeat HTTP 201/);assert.match(result.out,/pending/);
 const hb=calls.find(x=>x.url.endsWith('/heartbeat'));assert.match(hb.auth,/^CACAO /);assert.equal(hb.body['node/ready?'],false);assert.equal(hb.body['node/capacity']['slots-free'],0);
 assert(!calls.some(x=>x.url.includes('/queue')));assert(!result.out.includes('PRIVATE KEY'));
 denied=true;assert.notEqual((await run('check',...flags)).code,0);
 console.log('Packaged CLI: private identity, idempotent init, read-only diagnosis, signed heartbeat, no job claim, and refusal exits passed.');
}finally{server.close();rmSync(home,{recursive:true,force:true});}
