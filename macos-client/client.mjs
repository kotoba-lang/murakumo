#!/usr/bin/env node
// Native macOS platform adapter. Identity/admission remains the shared Kotoba CLI.
import http from 'node:http';
import {hostname,platform,arch,totalmem,freemem,homedir} from 'node:os';
import {readFileSync,writeFileSync,mkdirSync,chmodSync,existsSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {randomBytes,timingSafeEqual} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {Readable} from 'node:stream';import {pipeline} from 'node:stream/promises';
import {fileURLToPath} from 'node:url';
export const ROOT=join(homedir(),'github/workspaces/murakumo/native-client');
export function validateConfig(c){
 const u=new URL(c.engineURL);
 if(u.protocol!=='http:'||!['127.0.0.1','[::1]'].includes(u.hostname)||!u.port||u.username||u.password||u.pathname!=='/v1'||u.search||u.hash)throw Error('Engine must use a numeric loopback /v1 URL');
 if(!['llama-metal','mlx-metal'].includes(c.backend)||typeof c.model!=='string'||!c.model||c.model.length>1024||!Number.isInteger(c.port)||c.port<1024||c.port>65535||Number(u.port)===c.port)throw Error('Invalid native configuration');
 return c;
}
export function validateJob(v,c){if(v.model!==c.model||!Array.isArray(v.messages)||!v.messages.length||v.messages.length>128||v.messages.some(x=>!['system','user','assistant'].includes(x.role)||typeof x.content!=='string')||!Number.isInteger(v.max_tokens)||v.max_tokens<1||v.max_tokens>1024||v.stream!==undefined&&typeof v.stream!=='boolean')throw Error('Invalid inference request');return v;}
export function authorized(header,token){const a=Buffer.from(header||''),b=Buffer.from('Bearer '+token);return a.length===b.length&&timingSafeEqual(a,b);}
export function gpuEvidence(c){
 const port=new URL(c.engineURL).port;
 const r=spawnSync('/usr/sbin/lsof',['-nP','-iTCP:'+port,'-sTCP:LISTEN','-Fp'],{encoding:'utf8',timeout:5000});
 const pid=r.stdout?.match(/^p(\d+)$/m)?.[1];if(!pid)return {verified:false};
 const libs=spawnSync('/usr/sbin/lsof',['-p',pid,'-Fn'],{encoding:'utf8',timeout:5000,maxBuffer:4*1024*1024}).stdout||'';
 const command=spawnSync('/bin/ps',['-p',pid,'-o','command='],{encoding:'utf8',timeout:3000}).stdout||'';
 const layers=command.match(/(?:-ngl|--gpu-layers|--n-gpu-layers)\s+(\d+)/)?.[1];
 return {verified:c.backend==='llama-metal'?/libggml-metal/.test(libs)&&Number(layers)>0:/libmlx|mlx\/core/.test(libs),pid:Number(pid),kind:c.backend,gpuLayers:layers?Number(layers):null};
}
export async function start(c,token,{fetcher=fetch,evidence=gpuEvidence}={}){
 validateConfig(c);let busy=false;
 let gpu={verified:false};let refreshed=0;
 const server=http.createServer(async(req,res)=>{
 const send=(s,v)=>{res.writeHead(s,{'content-type':'application/json','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify(v));};
 if(!authorized(req.headers.authorization,token))return send(401,{error:'authentication_required'});
 // Reject browser cross-origin access even if another local app leaks the token.
 if(req.headers.origin)return send(403,{error:'browser_origin_refused'});
 const controller=new AbortController();req.on('aborted',()=>controller.abort());res.on('close',()=>{if(!res.writableEnded)controller.abort();});
 let ownsSlot=false;
 try{
  if(req.method==='GET'&&req.url==='/status'){
   if(Date.now()-refreshed>30000){gpu=evidence(c);refreshed=Date.now();}
   let engineReady=false;try{const r=await fetcher(c.engineURL+'/models',{signal:AbortSignal.timeout(5000),redirect:'error'});const d=await r.json();engineReady=r.ok&&d.data?.some(m=>m.id===c.model);}catch{}
   return send(200,{schema:'murakumo.native-status.v1',host:hostname(),os:platform(),architecture:arch(),backend:c.backend,gpu,engineReady,model:c.model,busy,memory:{total:totalmem(),available:freemem()},residentBytes:process.memoryUsage().rss,virtualMachine:false,communityAdmission:'not-enabled-by-native-adapter'});
  }
  if(req.method==='GET'&&req.url==='/v1/models'){
   const r=await fetcher(c.engineURL+'/models',{signal:AbortSignal.timeout(5000),redirect:'error'});if(!r.ok)return send(503,{error:'engine_unavailable'});
   const d=await r.json();return send(200,{object:'list',data:(d.data||[]).filter(m=>m.id===c.model)});
  }
  if(req.method!=='POST'||req.url!=='/v1/chat/completions')return send(404,{error:'not_found'});
  if(Date.now()-refreshed>30000){gpu=evidence(c);refreshed=Date.now();}
  if(!gpu.verified)return send(503,{error:'native_gpu_not_verified'});
  if(busy)return send(429,{error:'native_slot_busy'});
  busy=true;ownsSlot=true;
  const chunks=[];let n=0;for await(const b of req){n+=b.length;if(n>1024*1024)throw Error('Request too large');chunks.push(b);}
  const v=validateJob(JSON.parse(Buffer.concat(chunks)),c);
  const r=await fetcher(c.engineURL+'/chat/completions',{method:'POST',headers:{'content-type':'application/json',...(c.engineTokenFile?{authorization:'Bearer '+readFileSync(c.engineTokenFile,'utf8').trim()}:{})},body:JSON.stringify(v),signal:AbortSignal.any([controller.signal,AbortSignal.timeout(180000)]),redirect:'error'});
  if(!r.ok)return send(r.status===429?429:503,{error:'engine_refused'});
  res.writeHead(200,{'content-type':v.stream?'text/event-stream':'application/json','cache-control':'no-store'});
  await pipeline(Readable.fromWeb(r.body),res);
 }catch(e){if(!res.headersSent)send(e.message==='Invalid inference request'||e instanceof SyntaxError||e.message==='Request too large'?400:503,{error:'native_request_failed'});else res.destroy();}
 finally{if(ownsSlot)busy=false;}
 });
 server.requestTimeout=190000;server.headersTimeout=10000;
 await new Promise((ok,bad)=>{server.once('error',bad);server.listen(c.port,'127.0.0.1',ok);});return server;
}
export async function api(c,token,path,body){const r=await fetch('http://127.0.0.1:'+c.port+path,{method:body?'POST':'GET',headers:{authorization:'Bearer '+token,...(body?{'content-type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(180000)});if(!r.ok)throw Error('Native API HTTP '+r.status);return r.json();}
export async function bench(c,token,count=3){
 const payload={model:c.model,messages:[{role:'user',content:'Reply with only the number: 2+2=?'}],max_tokens:16,temperature:0,chat_template_kwargs:{enable_thinking:false}};
 const samples=[];
 for(let i=0;i<count;i++)for(const route of ['direct','client']){
  const begun=performance.now();let r;
  if(route==='client')r=await api(c,token,'/v1/chat/completions',payload);
  else{const h={'content-type':'application/json'};if(c.engineTokenFile)h.authorization='Bearer '+readFileSync(c.engineTokenFile,'utf8').trim();const response=await fetch(c.engineURL+'/chat/completions',{method:'POST',headers:h,body:JSON.stringify(payload),signal:AbortSignal.timeout(180000)});if(!response.ok)throw Error('Direct benchmark failed');r=await response.json();}
  const text=r.choices?.[0]?.message?.content?.trim();if(!text||!text.includes('4'))throw Error('Inference diagnostic failed');
  samples.push({route,seconds:(performance.now()-begun)/1000,tokens:r.usage?.completion_tokens,tokensPerSecond:r.timings?.predicted_per_second||r.murakumo?.tokens_per_second||null,response:text});
 }
 return {schema:'murakumo.native-bench.v1',observedAt:new Date().toISOString(),backend:c.backend,samples,status:await api(c,token,'/status')};
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
 try{
 const args=process.argv.slice(2),command=args.shift()||'status';const ix=args.indexOf('--root');const root=ix>=0?resolve(args[ix+1]):ROOT;
 const c=validateConfig(JSON.parse(readFileSync(join(root,'config.json'),'utf8'))),token=readFileSync(c.tokenFile,'utf8').trim();
 if(command==='serve')await start(c,token);
 else if(command==='status')console.log(JSON.stringify(await api(c,token,'/status'),null,2));
 else if(command==='bench')console.log(JSON.stringify(await bench(c,token),null,2));
 else if(command==='node'){
  const filtered=args.filter((_,i)=>ix<0||i<ix||i>ix+1);
  const r=spawnSync(process.execPath,[join(c.runtime,'node.mjs'),'node',...filtered],{stdio:'inherit',env:{...process.env,MURAKUMO_INFER_LOCAL_TOKEN:token}});process.exitCode=r.status||0;
 }else throw Error('Use status, bench, serve or node');
 }catch(e){console.error(e.message);process.exitCode=1;}
}
