import {test} from 'node:test';import assert from 'node:assert/strict';import {start,validateConfig,authorized} from '../macos-client/client.mjs';
const config={engineURL:'http://127.0.0.1:19841/v1',backend:'llama-metal',model:'test-model',port:19840};
test('native provider refuses non-loopback URLs and recursive ports',()=>{for(const engineURL of ['http://192.168.1.1:1234/v1','https://127.0.0.1:1234/v1','http://localhost:1234/v1','http://127.0.0.1:19840/v1','http://u:p@127.0.0.1:1234/v1'])assert.throws(()=>validateConfig({...config,engineURL}));assert.equal(authorized('Bearer t','t'),true);assert.equal(authorized('Bearer tt','t'),false);});
test('authorization, model binding, SSE streaming and slot refusal work on the real HTTP boundary',async()=>{
 let release;let calls=0;
 const s=await start(config,'private-token',{evidence:()=>({verified:true}),fetcher:async(_url,opts)=>{calls++;const v=JSON.parse(opts.body);if(v.stream)return new Response('data: {"choices":[{"delta":{"content":"4"}}]}\n\ndata: [DONE]\n\n');await new Promise(r=>{release=r;});return Response.json({choices:[{message:{content:'4'}}]});}});
 try{
 const request=(v,headers={})=>fetch('http://127.0.0.1:19840/v1/chat/completions',{method:'POST',headers:{connection:'close',authorization:'Bearer private-token','content-type':'application/json',...headers},body:JSON.stringify(v)});
 const v={model:'test-model',messages:[{role:'user',content:'2+2'}],max_tokens:16};
 assert.equal((await request(v,{authorization:'bad'})).status,401);assert.equal((await request(v,{origin:'https://evil.example'})).status,403);
 assert.equal((await request({...v,model:'wrong'})).status,400);assert.equal(calls,0);
 const first=request(v);await new Promise(r=>setTimeout(r,30));assert.equal((await request(v)).status,429);release();assert.equal((await first).status,200);
 const stream=await request({...v,stream:true});assert.match(await stream.text(),/\[DONE\]/);
 }finally{await new Promise(r=>s.close(r));}
});
test('unverified GPU refuses inference rather than silently using a CPU backend',async()=>{
 const s=await start(config,'token',{evidence:()=>({verified:false}),fetcher:()=>assert.fail('CPU fallback must not run')});
 try{const r=await fetch('http://127.0.0.1:19840/v1/chat/completions',{method:'POST',headers:{connection:'close',authorization:'Bearer token'},body:JSON.stringify({model:'test-model',messages:[{role:'user',content:'hi'}],max_tokens:1})});assert.equal(r.status,503);assert.equal((await r.json()).error,'native_gpu_not_verified');}finally{await new Promise(r=>s.close(r));}
});
