import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm,readdir,stat,symlink} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes,randomUUID,createHash,createHmac} from 'node:crypto';

const root=resolve('.');
const cli=join(root,'release/node.mjs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));

async function port(){
 const s=createServer();
 s.listen(0,'127.0.0.1');
 await once(s,'listening');
 const p=s.address().port;
 await new Promise(r=>s.close(r));
 return p;
}

async function wait(fn,tries=150){
 for(let i=0;i<tries;i++){
  try{if(await fn())return;}catch{}
  await sleep(50);
 }
 throw Error('condition timed out');
}

function u32be(n){
 const b=Buffer.alloc(4);
 b.writeUInt32BE(n,0);
 return b;
}

function signReplayBundle(authKey,bootId,nonce,issuedAt,body){
 const prefix=Buffer.concat([Buffer.from('awai/v1/murakumo-replay-bundle','utf8'),Buffer.from([0])]);
 const bootBuf=Buffer.from(bootId??'','utf8');
 const nonceBuf=Buffer.from(nonce??'','utf8');
 const issuedBuf=Buffer.from(String(issuedAt??''),'utf8');
 const bodyBuf=Buffer.isBuffer(body)?body:Buffer.from(body??'','utf8');
 return createHmac('sha256',Buffer.from(authKey,'utf8'))
  .update(prefix)
  .update(u32be(bootBuf.length)).update(bootBuf)
  .update(u32be(nonceBuf.length)).update(nonceBuf)
  .update(u32be(issuedBuf.length)).update(issuedBuf)
  .update(bodyBuf)
  .digest('hex');
}

function replayHeaders(token,bootId,body,{authKey,jobId,nonce,issuedAt,auth,extra}={}){
 const n=nonce===undefined?randomBytes(16).toString('hex'):nonce;
 const iat=issuedAt===undefined?String(Date.now()):(issuedAt===null?null:String(issuedAt));
 const h={
  authorization:`Bearer ${token}`,
  'content-type':'application/json'
 };
 if(jobId!==undefined&&jobId!==null)h['x-murakumo-job-id']=jobId;
 if(n!==null)h['x-murakumo-replay-nonce']=n;
 if(iat!==null)h['x-murakumo-issued-at']=iat;
 if(bootId!==undefined&&bootId!==null)h['x-murakumo-boot-id']=bootId;
 if(auth!==null){
  h['x-murakumo-replay-auth']=auth??signReplayBundle(authKey??token,bootId??'',n??'',iat??'',body??'');
 }
 if(extra)Object.assign(h,extra);
 return h;
}

function makeBackend({delayMs=0,disconnectOn=null,streamDelayMs=400,emitTraceHeaders=false}={}){
 const state={calls:0,tokenizes:0,seenHeaders:[],busy:false};
 const server=createServer((req,res)=>{
  if(req.url==='/slots')return res.end(JSON.stringify([{is_processing:state.busy}]));
  let data='';
  req.on('data',b=>data+=b);
  req.on('end',()=>{
   const body=JSON.parse(data);
   if(req.url==='/apply-template'){
    state.tokenizes++;
    return res.end(JSON.stringify({prompt:body.messages.map(m=>m.content).join('\n')}));
   }
   if(req.url==='/tokenize'){
    state.tokenizes++;
    return res.end(JSON.stringify({tokens:[1,2,3,4]}));
   }
   const content=body.messages?.[0]?.content??'';
   if(content.startsWith('2+3'))return res.end(JSON.stringify({choices:[{message:{content:'5'}}]}));
   state.calls++;
   state.seenHeaders.push({...req.headers});
   if(disconnectOn&&content.includes(disconnectOn)){
    return setTimeout(()=>res.destroy(),20);
   }
   const extraHeaders=emitTraceHeaders?{
    'cf-ray':'backend-cf-ray-leak',
    'traceparent':'00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
    'tracestate':'congo=backend',
    'x-request-id':'backend-req-id',
    'x-correlation-id':'backend-corr-id',
    'access-control-expose-headers':'x-murakumo-job-id, x-murakumo-node'
   }:{};
   if(body.stream){
    res.writeHead(200,{'content-type':'text/event-stream',...extraHeaders});
    res.flushHeaders();
    setTimeout(()=>{
     res.write('data: {"id":"chatcmpl-stream","model":"mishima","choices":[{"delta":{"content":"OK"}}]}\n\ndata: [DONE]\n\n');
     res.end();
    },streamDelayMs);
    return;
   }
   const send=()=>{
    res.writeHead(200,{'content-type':'application/json',...extraHeaders});
    res.end(JSON.stringify({choices:[{message:{content:'OK'}}],usage:{prompt_tokens:5,completion_tokens:2}}));
   };
   if(delayMs>0)setTimeout(send,delayMs);else send();
  });
 });
 return {state,server};
}

const sampleBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'hello kagishi privacy'}]});

test('TEST-INFER-PRIV-01: fail-closed startup scan (receipts/tmp/bak/lock/symlink/poi/compat) without deletion, allowed sidecars preserved, and 0 disk writes across success/failure/overload',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv01-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const stateDir=join(dir,'state');
 await rm(stateDir,{recursive:true,force:true});
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`,'content-type':'application/json'};

 const backend=makeBackend({disconnectOn:'BREAK_ME'});
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const processes=[];
 t.after(()=>{for(const p of processes)p.kill('SIGTERM');});

 async function launch(config,name){
  const cfgPath=join(dir,`${name}.json`);
  await writeFile(cfgPath,JSON.stringify(config));
  const p=spawn(process.execPath,[cli,'resident','serve','--config',cfgPath],{cwd:dir,stdio:['ignore','pipe','pipe']});
  let out='';
  p.stdout.on('data',b=>out+=b);
  p.stderr.on('data',b=>out+=b);
  processes.push(p);
  return {p,err:()=>out};
 }

 await (await import('node:fs/promises')).mkdir(stateDir,{recursive:true});
 const stateFile=join(stateDir,'receipts.json');
 const baseConfig={
  node:'priv01',bind:'127.0.0.1',port:await port(),
  'token-file':tokenFile,'state-file':stateFile,
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'job-timeout-ms':2000,'replay-window-ms':5000,'replay-clock-skew-ms':500,'replay-rotation-ms':6000,
  'replay-max-entries':2,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };

 // 1a. Legacy receipts.json must refuse startup across kagishi-ram, ephemeral-memory, and ram-hmac WITHOUT deleting/modifying
 const sentinel='{"jobs":{"legacy_job":{"status":"completed"}}}';
 await writeFile(stateFile,sentinel,{mode:0o600});
 const beforeStat=await stat(stateFile);
 await sleep(20);
 for(const [key,mode] of [['replay-ledger-mode','kagishi-ram'],['replay-ledger-mode','ephemeral-memory'],['receipt-mode','ram-hmac']]){
  const cfg={...baseConfig,port:await port()};
  delete cfg['replay-ledger-mode'];
  cfg[key]=mode;
  const refuse=await launch(cfg,`legacy-${mode}`);
  const [code]=await once(refuse.p,'close');
  assert.notEqual(code,0,`existing receipts.json must refuse startup in ${mode}`);
  assert.equal(existsSync(stateFile),true,'legacy receipts.json must NOT be deleted');
  assert.equal(await readFile(stateFile,'utf8'),sentinel,'legacy receipts.json content untouched');
  assert.equal((await stat(stateFile)).mtimeMs,beforeStat.mtimeMs,'legacy receipts.json mtime untouched');
 }
 await rm(stateFile);

 // 1b. Residual receipts*.json*, *.tmp, *.bak, *.lock, *.poi-receipts.jsonl* all refuse startup without deleting
 for(const badName of ['receipts-2026.json','receipts.json.1','stray.tmp','backup.bak','resident.lock','receipts.json.poi-receipts.jsonl','receipts.json.poi-receipts.jsonl.1']){
  const badPath=join(stateDir,badName);
  await writeFile(badPath,'sentinel-data',{mode:0o600});
  const refuse=await launch({...baseConfig,port:await port()},`bad-${badName.replace(/[^a-z0-9]/gi,'_')}`);
  const [code]=await once(refuse.p,'close');
  assert.notEqual(code,0,`residual ${badName} must refuse startup`);
  assert.equal(existsSync(badPath),true,`${badName} must NOT be deleted`);
  assert.equal(await readFile(badPath,'utf8'),'sentinel-data',`${badName} must NOT be modified`);
  await rm(badPath);
 }

 // 1c. Symlink (even dangling!) on :state-file or allowed sidecar must fail closed via lstatSync
 {
  const linkPath=stateFile;
  await symlink(join(dir,'nonexistent-target'),linkPath);
  const refuse=await launch({...baseConfig,port:await port()},'symlink-state');
  const [code]=await once(refuse.p,'close');
  assert.notEqual(code,0,'dangling symlink at state-file must refuse startup');
  await rm(linkPath);

  const realOwner=join(dir,'real-owner');
  await writeFile(realOwner,randomUUID(),{mode:0o600});
  await symlink(realOwner,stateFile+'.owner-id');
  const refuseSidecar=await launch({...baseConfig,port:await port()},'symlink-sidecar');
  const [code2]=await once(refuseSidecar.p,'close');
  assert.notEqual(code2,0,'symlink at .owner-id sidecar must refuse startup');
  await rm(stateFile+'.owner-id');
 }

 // 1d. :poi-receipts? true and :compat-port and conflicting mode keys must fail closed
 {
  const poiTrue=await launch({...baseConfig,port:await port(),'poi-receipts?':true},'poi-true');
  assert.notEqual((await once(poiTrue.p,'close'))[0],0,'poi-receipts?: true must refuse startup');

  const withCompat=await launch({...baseConfig,port:await port(),'compat-port':await port(),'compat-bind':'127.0.0.1'},'compat-refuse');
  assert.notEqual((await once(withCompat.p,'close'))[0],0,'compat-port must refuse startup');

  const conflict=await launch({...baseConfig,port:await port(),'replay-ledger-mode':'kagishi-ram','receipt-mode':'durable'},'conflict-mode');
  assert.notEqual((await once(conflict.p,'close'))[0],0,'conflicting replay-ledger-mode and receipt-mode must refuse startup');
 }

 // 1e. Fresh stateDir: 0 files written on startup, success, failure (502), and overload (503 replay_capacity)
 const p1=await port();
 const r1=await launch({...baseConfig,port:p1,'replay-ledger-mode':'ephemeral-memory'},'clean-priv');
 const u1=`http://127.0.0.1:${p1}`;
 const health1=async()=>(await fetch(u1+'/health',{headers:auth})).json();
 await wait(async()=>(await health1()).lanes?.text?.ready);
 const h1=await health1();
 assert.equal(h1.replay_mode,'ephemeral-memory');
 assert.match(h1.boot_id,/^[a-f0-9]{32}$/);
 assert.deepEqual(await readdir(stateDir),[],'0 files written to stateDir on startup');

 // PoI endpoint disabled
 assert.equal((await fetch(u1+'/poi/receipts')).status,401);
 const poiAuth=await fetch(u1+'/poi/receipts',{headers:auth});
 assert.equal(poiAuth.status,404);
 assert.equal((await poiAuth.json()).error,'poi_receipts_disabled');

 // Success
 const okRes=await fetch(u1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,sampleBody),body:sampleBody});
 assert.equal(okRes.status,200);
 await okRes.json();

 // Failure (backend disconnect -> 502)
 const failBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'BREAK_ME'}]});
 const failRes=await fetch(u1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,failBody),body:failBody});
 assert.equal(failRes.status,502);
 await failRes.json();

 // Re-probe or launch fresh with capacity=1? Here max-entries=2 was filled by okRes + failRes; wait for ready and hit overload (503)
 r1.p.kill('SIGTERM');
 await once(r1.p,'exit');

 // 1f. Allowed sidecars (.owner-id, .bound-owner, .node-key, .observation-pins.json) NOT falsely flagged
 const ownerId=randomUUID();
 await writeFile(stateFile+'.owner-id',ownerId,{mode:0o600});
 await writeFile(stateFile+'.bound-owner',randomUUID(),{mode:0o600});
 const mismatch=await launch({...baseConfig,port:await port()},'owner-mismatch');
 assert.notEqual((await once(mismatch.p,'close'))[0],0,'mismatched .bound-owner must refuse startup');

 await writeFile(stateFile+'.bound-owner',ownerId,{mode:0o600});
 await writeFile(stateFile+'.node-key','dummy-node-key',{mode:0o600});
 await writeFile(stateFile+'.observation-pins.json','{}',{mode:0o600});
 const p2=await port();
 const r2=await launch({...baseConfig,port:p2,'replay-max-entries':1},'with-allowed-sidecars');
 const u2=`http://127.0.0.1:${p2}`;
 await wait(async()=>(await(await fetch(u2+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const h2=await(await fetch(u2+'/health',{headers:auth})).json();
 assert.equal(h2.replay_mode,'kagishi-ram');
 assert.equal(h2['owner-id'],ownerId);

 // 1st succeeds, 2nd triggers overload 503 replay_capacity
 const ok2=await fetch(u2+'/execute/text',{method:'POST',headers:replayHeaders(token,h2.boot_id,sampleBody),body:sampleBody});
 assert.equal(ok2.status,200);await ok2.json();
 const ov=await fetch(u2+'/execute/text',{method:'POST',headers:replayHeaders(token,h2.boot_id,sampleBody),body:sampleBody});
 assert.equal(ov.status,503);
 assert.deepEqual(await ov.json(),{error:'replay_capacity',executed:false});

 const finalFiles=(await readdir(stateDir)).sort();
 assert.deepEqual(finalFiles,[
  'receipts.json.bound-owner',
  'receipts.json.node-key',
  'receipts.json.observation-pins.json',
  'receipts.json.owner-id'
 ],'only pre-existing allowed sidecars may exist; zero receipt/tmp/poi files written');
});

test('TEST-INFER-PRIV-02: unified defaults, startup invariants (W < job-timeout, T_rot < W+2d, 2*T_rot+jitter > 900s), 2*T_rot purge-after-reuse, Option A 409 already-seen (no 422), and 503 replay_capacity no-LRU',async t=>{
 // Part A: Direct defaults, crypto vector, pre-admit rotation, K_prev zeroization, and stuck in-flight sweep
 const verifyClj=`
 (require '[murakumo.infer.resident :as r] '["node:crypto" :as crypto])
 (let [def-ledger (r/make-replay-ledger {} "00112233445566778899aabbccddeeff" 1000)
       d @def-ledger
       _ (when-not (= 180000 (:job-timeout-ms d)) (throw (js/Error. (str "expected default job-timeout-ms 180000, got " (:job-timeout-ms d)))))
       _ (when-not (= 300000 (:window-ms d)) (throw (js/Error. (str "expected default W 300000, got " (:window-ms d)))))
       _ (when-not (= 30000 (:clock-skew-ms d)) (throw (js/Error. (str "expected default delta 30000, got " (:clock-skew-ms d)))))
       _ (when-not (= 360000 (:rotation-ms d)) (throw (js/Error. (str "expected default T_rot 360000, got " (:rotation-ms d)))))
       _ (when-not (= 10000 (:max-entries d)) (throw (js/Error. (str "expected default max-entries 10000, got " (:max-entries d)))))
       _ (when-not (= 0 (:rotation-jitter-ms d)) (throw (js/Error. "expected default jitter 0")))
       _ (when-not (<= (+ (* 2 (:rotation-ms d)) (:rotation-jitter-ms d)) 900000) (throw (js/Error. "default retention > 900000")))
       _ (r/destroy-replay-ledger! def-ledger)
       key (.from js/Buffer "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" "hex")
       boot-id "00112233445566778899aabbccddeeff"
       nonce "fedcba9876543210fedcba9876543210"
       tag (r/replay-tag key boot-id nonce)
       b-boot (js/Buffer.from boot-id "utf8")
       b-nonce (js/Buffer.from nonce "utf8")
       len1 (.alloc js/Buffer 4) len2 (.alloc js/Buffer 4)
       _ (.writeUInt32BE len1 (.-length b-boot) 0)
       _ (.writeUInt32BE len2 (.-length b-nonce) 0)
       prefix (.concat js/Buffer #js [(js/Buffer.from "awai/v1/murakumo-replay" "utf8") (js/Buffer.from #js [0])])
       expected (.digest (.update (.createHmac crypto "sha256" key)
                                  (.concat js/Buffer #js [prefix len1 b-boot len2 b-nonce])) "hex")
       _ (when-not (= tag expected) (throw (js/Error. "hmac mismatch")))
       ledger (r/make-replay-ledger {:job-timeout-ms 300 :replay-window-ms 400 :replay-clock-skew-ms 100 :replay-rotation-ms 600 :replay-max-entries 10} boot-id 1000)
       k0 (:k-curr @ledger) s0 (:curr-set @ledger)
       state (atom {:groups {}})
       lane {:group "gpu" :ready true}
       a1 (r/admit-ram-replay! state ledger {} lane "priv-1" nonce 1400)
       _ (when-not (= 200 (:status a1)) (throw (js/Error. "expected 200")))
       _ (swap! state assoc :groups {})
       _ (swap! ledger update :inflight dissoc "priv-1")
       a2 (r/admit-ram-replay! state ledger {} lane "priv-2" nonce 1650)
       _ (when-not (and (= 409 (:status a2)) (= "unknown" (:executed a2)) (false? (:retryable a2)))
           (throw (js/Error. (str "expected 409 already-seen, got " (pr-str a2)))))
       _ (when-not (identical? k0 (:k-prev @ledger)) (throw (js/Error. "k0 must move to k-prev")))
       k1 (:k-curr @ledger)
       _ (r/maybe-rotate-kagishi-replay! ledger 2300)
       _ (when-not (.every k0 #(= 0 %)) (throw (js/Error. "k0 NOT zeroized on 2nd rotation")))
       _ (when-not (= 0 (.-size s0)) (throw (js/Error. "s0 NOT cleared on 2nd rotation")))
       ;; Stuck in-flight entry swept when > max-inflight-ms + rotation-ms
       _ (swap! ledger assoc-in [:inflight "stuck-id"] {:admitted-at 1000 :group "gpu"})
       _ (swap! state assoc-in [:groups "gpu"] "stuck-id")
       _ (r/maybe-rotate-kagishi-replay! ledger 2500 state nil)
       _ (when (get-in @ledger [:inflight "stuck-id"]) (throw (js/Error. "stuck inflight was not swept")))
       _ (when (get-in @state [:groups "gpu"]) (throw (js/Error. "stuck group slot was not freed")))
       k2 (:k-curr @ledger)
       _ (r/destroy-replay-ledger! ledger)
       _ (when-not (and (.every k1 #(= 0 %)) (.every k2 #(= 0 %))) (throw (js/Error. "destroy did not zeroize")))]
   (println "PRIV02_UNIT_OK"))
 `;
 const cljDir=await mkdtemp(join(tmpdir(),'murakumo-priv02-clj-'));
 t.after(()=>rm(cljDir,{recursive:true,force:true}));
 await writeFile(join(cljDir,'nbb.edn'),'{:paths []}\n');
 const cljOut=execFileSync('kbb',['--backend','sci','--classpath',join(root,'src'),'-e',verifyClj],{cwd:cljDir,encoding:'utf8'});
 assert.match(cljOut,/PRIV02_UNIT_OK/);

 // Part B: Startup invariant refusals + live 2*T_rot purge + Option A 409 + 503 replay_capacity no-LRU + gateway failover
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv02-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const procs=[];
 t.after(()=>{for(const p of procs)p.kill('SIGTERM');});

 async function launchFail(cfg,name){
  const f=join(dir,`${name}.json`);
  await writeFile(f,JSON.stringify(cfg));
  const p=spawn(process.execPath,[cli,'resident','serve','--config',f],{cwd:dir,stdio:['ignore','pipe','pipe']});
  let err='';p.stdout.on('data',b=>err+=b);p.stderr.on('data',b=>err+=b);
  procs.push(p);
  const [code]=await once(p,'close');
  return {code,err};
 }

 const lane={kind:'text',model:'mishima',context:32768,group:'gpu',backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'};
 const invBase={node:'inv0',bind:'127.0.0.1','token-file':tokenFile,'state-file':join(dir,'r.json'),'replay-ledger-mode':'kagishi-ram','observations?':false,'probe-interval-ms':30000,lanes:{text:lane}};

 // W < job-timeout-ms (default 180000 vs W=60000)
 const rInv1=await launchFail({...invBase,port:await port(),'replay-window-ms':60000,'replay-clock-skew-ms':1000,'replay-rotation-ms':70000},'w-lt-default-jt');
 assert.notEqual(rInv1.code,0,'W < default job-timeout-ms (180000) must fail closed');
 assert.match(rInv1.err,/job-timeout-ms/i);

 // W < explicit lane job-timeout-ms
 const rInv2=await launchFail({...invBase,port:await port(),'replay-window-ms':10000,'replay-clock-skew-ms':1000,'replay-rotation-ms':12000,lanes:{text:{...lane,'job-timeout-ms':15000}}},'w-lt-lane-jt');
 assert.notEqual(rInv2.code,0,'W < lane job-timeout-ms must fail closed');

 // T_rot < W + 2*delta
 const rInv3=await launchFail({...invBase,port:await port(),'job-timeout-ms':2000,'replay-window-ms':5000,'replay-clock-skew-ms':1000,'replay-rotation-ms':6500},'trot-lt-w2d');
 assert.notEqual(rInv3.code,0,'T_rot < W + 2*delta must fail closed');

 // 2 * T_rot + rotation-jitter-ms > 900000
 const rInv4=await launchFail({...invBase,port:await port(),'job-timeout-ms':180000,'replay-window-ms':300000,'replay-clock-skew-ms':30000,'replay-rotation-ms':420000,'rotation-jitter-ms':60001},'jitter-gt-900s');
 assert.notEqual(rInv4.code,0,'2*T_rot + rotation-jitter-ms > 900000 must fail closed');
 assert.match(rInv4.err,/900000/);

 // Live rotation across 2*T_rot + Option A 409 + 503 replay_capacity no-LRU
 const rp=await port();
 const cfgRot={
  ...invBase,port:rp,'state-file':join(dir,'rot-receipts.json'),
  'job-timeout-ms':400,'replay-window-ms':500,'replay-clock-skew-ms':100,'replay-rotation-ms':700,
  'replay-sweep-interval-ms':150,'replay-max-entries':2
 };
 const rotPath=join(dir,'rot.json');
 await writeFile(rotPath,JSON.stringify(cfgRot));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rotPath],{cwd:dir,stdio:'ignore'});
 procs.push(rProc);
 const rUrl=`http://127.0.0.1:${rp}`;
 await wait(async()=>(await(await fetch(rUrl+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const readyAt=Date.now();
 const {boot_id:bootId}=await(await fetch(rUrl+'/health',{headers:auth})).json();

 // Admit R1 right after ready, then wait past 1st rotation (T_rot=700ms) while still within window?
 // Use a slightly larger W=900, delta=100, T_rot=1100? Wait: W=500, delta=100, T_rot=700 means
 // valid window is 600ms; so if R1 is sent at t ~ started+350, R1 stays valid until ~started+950 (> 700).
 // Wait until readyAt+200 so R1 is in gen 0/1, then after 1st rotation check prev_set, then after 2*T_rot check purge.
 const n1=randomBytes(16).toString('hex');
 const iat1=Date.now();
 const first=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{nonce:n1,issuedAt:iat1}),body:sampleBody});
 assert.equal(first.status,200);
 await first.json();
 assert.equal(backend.state.calls,1);

 // Option A strict single-use: same replay_nonce with DIFFERENT validly-signed body -> 409 already-seen (NEVER 422!)
 const diffBody=sampleBody.replace('hello kagishi privacy','completely different prompt');
 const dupDiff=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,diffBody,{nonce:n1,issuedAt:iat1}),body:diffBody});
 assert.equal(dupDiff.status,409,'Option A duplicate must be 409, never 422');
 assert.deepEqual(await dupDiff.json(),{error:'already-seen',executed:'unknown',retryable:false});
 assert.equal(backend.state.calls,1);

 // Wait past 2*T_rot (1400ms + margin) after readyAt so BOTH generations rotate and n1 is purged from RAM;
 // exact same R1 MUST STILL be rejected by issued_at expiry!
 const waitPurge=Math.max(0,readyAt+1750-Date.now());
 if(waitPurge>0)await sleep(waitPurge);
 const afterPurge=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{nonce:n1,issuedAt:iat1}),body:sampleBody});
 assert.equal(afterPurge.status,403);
 assert.deepEqual(await afterPurge.json(),{error:'replay_issued_at_expired',executed:false});
 assert.equal(backend.state.calls,1);

 // Capacity overflow (max-entries=2) + no LRU eviction -> 503 replay_capacity
 const capN1=randomBytes(16).toString('hex');
 const capN2=randomBytes(16).toString('hex');
 const capN3=randomBytes(16).toString('hex');
 const c1=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{nonce:capN1}),body:sampleBody});
 assert.equal(c1.status,200);await c1.json();
 const c2=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{nonce:capN2}),body:sampleBody});
 assert.equal(c2.status,200);await c2.json();
 const full=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{nonce:capN3}),body:sampleBody});
 assert.equal(full.status,503);
 assert.deepEqual(await full.json(),{error:'replay_capacity',executed:false});
 // No LRU eviction: capN1 & capN2 still 409
 assert.equal((await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{nonce:capN1}),body:sampleBody})).status,409);
 assert.equal((await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{nonce:capN2}),body:sampleBody})).status,409);
 assert.equal(backend.state.calls,3);

 // Gateway failover on 503 replay_capacity (executed:false): retries eligible peer, fails closed 503 executed:false when all peers are full
 const fp1=await port(),fp2=await port(),fgp=await port();
 const fUrl1=`http://127.0.0.1:${fp1}`,fUrl2=`http://127.0.0.1:${fp2}`,fgUrl=`http://127.0.0.1:${fgp}`;
 for(const [idx,fp,n] of [[1,fp1,'capfail1'],[2,fp2,'capfail2']]){
  const pDir=join(dir,`cf-${idx}`);
  await (await import('node:fs/promises')).mkdir(pDir,{recursive:true});
  const pCfg={
   node:n,bind:'127.0.0.1',port:fp,'token-file':tokenFile,'state-file':join(pDir,'receipts.json'),
   'replay-ledger-mode':'kagishi-ram','observations?':false,'allow-provisional-no-boot-id?':true,
   'job-timeout-ms':1000,'replay-window-ms':5000,'replay-clock-skew-ms':100,'replay-rotation-ms':6000,
   'replay-max-entries':1,'probe-interval-ms':30000,lanes:{text:lane}
  };
  const pf=join(dir,`cf-${idx}.json`);
  await writeFile(pf,JSON.stringify(pCfg));
  procs.push(spawn(process.execPath,[cli,'resident','serve','--config',pf],{cwd:dir,stdio:'ignore'}));
 }
 const fgf=join(dir,'cf-gw.json');
 await writeFile(fgf,JSON.stringify({
  node:'cf-gw',bind:'127.0.0.1',port:fgp,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram',
  'probe-interval-ms':40,peers:[{node:'capfail1',url:fUrl1},{node:'capfail2',url:fUrl2}]
 }));
 procs.push(spawn(process.execPath,[cli,'gateway','serve','--config',fgf],{cwd:dir,stdio:'ignore'}));
 await wait(async()=>{
  const h=await(await fetch(fgUrl+'/health',{headers:auth})).json();
  return h.peers?.capfail1?.lanes?.text?.ready&&h.peers?.capfail2?.lanes?.text?.ready;
 });
 await sleep(200); // past provisional started_at + delta (100ms)
 // 1st request fills one peer (max-entries=1); 2nd request retries past full peer onto the 2nd peer -> 200; 3rd fails closed 503 replay_capacity executed:false!
 const gf1=await fetch(fgUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,null,sampleBody),body:sampleBody});
 assert.equal(gf1.status,200);await gf1.json();
 const gf2=await fetch(fgUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,null,sampleBody),body:sampleBody});
 assert.equal(gf2.status,200,'gateway must retry another eligible peer on 503 replay_capacity executed:false');await gf2.json();
 const gf3=await fetch(fgUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,null,sampleBody),body:sampleBody});
 assert.equal(gf3.status,503,'gateway must fail closed 503 when all eligible peers are at replay_capacity');
 assert.deepEqual(await gf3.json(),{error:'replay_capacity',executed:false});
});

test('TEST-INFER-PRIV-03: 0 unsalted SHA-256(body) anywhere; POST /estimate/:lane uses x-murakumo-estimate-nonce echo and rejects missing/mismatched nonce',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv03-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`,'content-type':'application/json'};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'est0',bind:'127.0.0.1',port:rp,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={
  node:'gw-est0',bind:'127.0.0.1',port:gp,
  'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram',
  'probe-interval-ms':50,
  peers:[{node:'est0',url:rUrl}]
 };
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));
 await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.est0?.lanes?.text?.ready);
 const {boot_id:bootId}=await(await fetch(rUrl+'/health',{headers:auth})).json();

 const largeBody=JSON.stringify({model:'mishima',max_tokens:32,messages:[{role:'user',content:'Private prompt text. '.repeat(2000)}]});
 assert.ok(Buffer.byteLength(largeBody)>32768,'exceeds byte upper bound so gateway MUST rely on /estimate/:lane');
 const unsaltedSha=createHash('sha256').update(largeBody).digest('hex');

 // Missing x-murakumo-estimate-nonce -> rejected 400 with 0 tokenizer calls
 const tokBefore=backend.state.tokenizes;
 const estNoNonce=await fetch(rUrl+'/estimate/text',{method:'POST',headers:auth,body:largeBody});
 assert.equal(estNoNonce.status,400);
 const estNoNonceText=await estNoNonce.text();
 assert.ok(!estNoNonceText.includes(unsaltedSha),'error JSON must never contain unsalted body sha256');
 assert.equal(backend.state.tokenizes,tokBefore,'missing estimate nonce must fail before tokenizer');

 // Short/malformed nonce -> rejected 400
 const estBadNonce=await fetch(rUrl+'/estimate/text',{method:'POST',headers:{...auth,'x-murakumo-estimate-nonce':'too-short'},body:largeBody});
 assert.equal(estBadNonce.status,400);
 assert.equal(backend.state.tokenizes,tokBefore);

 // Valid 128-bit x-murakumo-estimate-nonce -> 200 with echoed estimate_nonce, NO digest, NO unsalted sha256
 const estNonce=randomBytes(16).toString('hex');
 const estOk=await fetch(rUrl+'/estimate/text',{method:'POST',headers:{...auth,'x-murakumo-estimate-nonce':estNonce},body:largeBody});
 assert.equal(estOk.status,200);
 const estJson=await estOk.json();
 assert.equal(estJson.estimate_nonce,estNonce);
 assert.equal(estJson.source,'backend-tokenizer');
 assert.equal(estJson['input-tokens'],132);
 assert.equal('digest' in estJson,false,'/estimate/:lane must NOT return :digest');
 assert.ok(!JSON.stringify(estJson).includes(unsaltedSha),'zero unsalted SHA-256(body) in /estimate response');

 // End-to-end through fleet_gateway: largeBody (>32768 bytes) succeeds ONLY because gateway calls /estimate/:lane with x-murakumo-estimate-nonce
 const gwOk=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,bootId,largeBody),body:largeBody});
 assert.equal(gwOk.status,200,'gateway must admit large payload via nonce-authenticated /estimate/:lane');
 const gwOkText=await gwOk.text();
 assert.ok(!gwOkText.includes(unsaltedSha),'inference response must not contain unsalted body sha256');
 assert.equal(backend.state.calls,1);

 // Gateway fails closed if a rogue resident returns a mismatched estimate_nonce (falls back to byte upper bound -> rejects >32K payload)
 const rogueBoot=randomBytes(16).toString('hex');
 let rogueExecCalls=0;
 const rogueResident=createServer((req,res)=>{
  if(req.url==='/health'){
   return res.end(JSON.stringify({node:'rogue0',boot_id:rogueBoot,replay_mode:'kagishi-ram',lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',ready:true,busy:false}}}));
  }
  if(req.url==='/estimate/text'){
   req.resume();
   return req.on('end',()=>res.end(JSON.stringify({model:'mishima','input-tokens':10,source:'backend-tokenizer',estimate_nonce:'0'.repeat(32)})));
  }
  rogueExecCalls++;
  req.resume();
  req.on('end',()=>res.end(JSON.stringify({choices:[{message:{content:'SHOULD_NOT_RUN'}}]})));
 });
 rogueResident.listen(0,'127.0.0.1');
 await once(rogueResident,'listening');
 t.after(()=>{rogueResident.closeAllConnections();rogueResident.close();});

 const gp2=await port();
 const g2Path=join(dir,'g2.json');
 await writeFile(g2Path,JSON.stringify({node:'gw-rogue',bind:'127.0.0.1',port:gp2,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':40,peers:[{node:'rogue0',url:`http://127.0.0.1:${rogueResident.address().port}`}]}));
 const g2Proc=spawn(process.execPath,[cli,'gateway','serve','--config',g2Path],{cwd:dir,stdio:'ignore'});
 t.after(()=>g2Proc.kill('SIGTERM'));
 const g2Url=`http://127.0.0.1:${gp2}`;
 await wait(async()=>(await(await fetch(g2Url+'/health',{headers:auth})).json()).peers?.rogue0?.lanes?.text?.ready);

 const rogueRes=await fetch(g2Url+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,rogueBoot,largeBody),body:largeBody});
 assert.equal(rogueRes.status,503,'mismatched estimate_nonce must be discarded so oversized payload fails closed');
 assert.equal(rogueExecCalls,0,'rogue resident must not be dispatched when estimate_nonce mismatches');
});

test('TEST-INFER-PRIV-04: 4-layer job-id suppression, fixed SSE prefill-heartbeat :id, and correlation header stripping across gateway and resident',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv04-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`};

 const backend=makeBackend({disconnectOn:'DISCONNECT_502',streamDelayMs:350,emitTraceHeaders:true});
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'priv04-node',bind:'127.0.0.1',port:rp,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'heartbeat-ms':50,'replay-max-entries':3,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={
  node:'priv04-gw',bind:'127.0.0.1',port:gp,
  'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram',
  'probe-interval-ms':50,
  peers:[{node:'priv04-node',url:rUrl}]
 };
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));
 await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv04-node']?.lanes?.text?.ready);
 const {boot_id:bootId}=await(await fetch(rUrl+'/health',{headers:auth})).json();

 const forbiddenRespHeaders=['x-murakumo-job-id','x-murakumo-node','access-control-expose-headers','cf-ray','traceparent','tracestate','x-request-id','x-correlation-id'];
 const injectedHeaders={
  'x-murakumo-job-id':'attacker-job-corr-9999',
  'cf-ray':'caller-cf-ray-123',
  'traceparent':'00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
  'tracestate':'congo=caller',
  'x-request-id':'caller-req-777',
  'x-correlation-id':'caller-corr-888'
 };

 function assertNoLeakHeaders(resp,label){
  for(const h of forbiddenRespHeaders){
   assert.equal(resp.headers.get(h),null,`${label}: forbidden header ${h} leaked (${resp.headers.get(h)})`);
  }
 }

 // 4a. Normal JSON response through gateway
 const okRes=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{extra:injectedHeaders}),body:sampleBody});
 assert.equal(okRes.status,200);
 assertNoLeakHeaders(okRes,'normal 200');
 const okText=await okRes.text();
 assert.ok(!okText.includes('attacker-job-corr-9999'));
 // Backend never received injected trace/job headers
 const bHdrs=backend.state.seenHeaders.at(-1);
 for(const h of ['x-murakumo-job-id','cf-ray','traceparent','tracestate','x-request-id','x-correlation-id']){
  assert.equal(bHdrs[h],undefined,`backend must not receive ${h}`);
 }

 // 4b. Streaming SSE response with prefill-heartbeat! fixed :id === "0"
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv04-node']?.lanes?.text?.busy===false);
 const streamBody=JSON.stringify({model:'mishima',max_tokens:16,stream:true,messages:[{role:'user',content:'stream test'}]});
 const sseRes=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,bootId,streamBody,{extra:injectedHeaders}),body:streamBody});
 assert.equal(sseRes.status,200);
 assertNoLeakHeaders(sseRes,'SSE 200');
 const sseText=await sseRes.text();
 const sseEvents=sseText.split('\n').filter(l=>l.startsWith('data: ')&&l!=='data: [DONE]').map(l=>JSON.parse(l.slice(6)));
 const heartbeats=sseEvents.filter(e=>Array.isArray(e.choices)&&e.choices.length===0);
 assert.ok(heartbeats.length>=1,'expected at least one prefill heartbeat chunk');
 for(const hb of heartbeats){
  assert.equal(hb.id,'0','SSE prefill-heartbeat :id must be fixed/uncorrelated "0"');
 }
 assert.ok(!sseText.includes('attacker-job-corr-9999'));

 // 4c. 502 execution_unknown response (no job-id in JSON body or headers)
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv04-node']?.lanes?.text?.busy===false);
 const discBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'DISCONNECT_502'}]});
 const r502=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,bootId,discBody,{extra:injectedHeaders}),body:discBody});
 assert.equal(r502.status,502);
 assertNoLeakHeaders(r502,'502 execution_unknown');
 const j502=await r502.json();
 assert.deepEqual(j502,{error:'execution_unknown',retryable:false},'502 body must omit job-id in privacy mode');

 // 4d. 503 replay_capacity (ledger now has 3 entries = max-entries) directly on resident (no leak headers, no job-id)
 const r503=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,sampleBody,{extra:injectedHeaders}),body:sampleBody});
 assert.equal(r503.status,503);
 assertNoLeakHeaders(r503,'503 replay_capacity');
 const j503=await r503.json();
 assert.equal('job-id' in j503,false);
 assert.equal(j503.executed,false);
});

test('TEST-INFER-PRIV-05: combines PR #715 inbound-token-file + inbound-allowed-routes with kagishi-ram privacy lane (403 route_not_allowed on /jobs/*, /images, /topology)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv05-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const stateDir=join(dir,'state');
 await (await import('node:fs/promises')).mkdir(stateDir,{recursive:true});
 const fleetToken=randomBytes(32).toString('hex');
 const inboundToken=randomBytes(32).toString('hex');
 const fleetTokenFile=join(dir,'fleet.token');
 const inboundTokenFile=join(dir,'inbound.token');
 await writeFile(fleetTokenFile,fleetToken,{mode:0o600});
 await writeFile(inboundTokenFile,inboundToken,{mode:0o600});

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'priv05-node',bind:'127.0.0.1',port:rp,
  'token-file':fleetTokenFile,'state-file':join(stateDir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={
  node:'priv05-gw',bind:'127.0.0.1',port:gp,
  'token-file':fleetTokenFile,
  'inbound-token-file':inboundTokenFile,
  'inbound-allowed-routes':['GET /health','POST /v1/chat/completions'],
  'replay-ledger-mode':'kagishi-ram',
  'probe-interval-ms':50,
  peers:[{node:'priv05-node',url:rUrl}]
 };
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));
 await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 const inboundAuth={authorization:`Bearer ${inboundToken}`,'content-type':'application/json'};
 const fleetAuth={authorization:`Bearer ${fleetToken}`,'content-type':'application/json'};

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:inboundAuth})).json()).peers?.['priv05-node']?.lanes?.text?.ready);
 const gwHealth=await(await fetch(gUrl+'/health',{headers:inboundAuth})).json();
 const bootId=gwHealth.peers['priv05-node'].boot_id;
 assert.equal(gwHealth.replay_mode,'kagishi-ram');

 // Unauthenticated -> 401; fleet token rejected on inbound route -> 401
 assert.equal((await fetch(gUrl+'/health')).status,401);
 assert.equal((await fetch(gUrl+'/health',{headers:fleetAuth})).status,401);
 assert.equal((await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:fleetAuth,body:sampleBody})).status,401);

 // Disallowed routes with valid inbound token -> 403 route_not_allowed
 for(const [method,path] of [['GET','/jobs/job-12345678'],['POST','/jobs/job-12345678'],['POST','/v1/images/generations'],['GET','/topology']]){
  const resp=await fetch(gUrl+path,{method,headers:inboundAuth,body:method==='POST'?'{}':undefined});
  assert.equal(resp.status,403,`${method} ${path} must return 403 route_not_allowed`);
  assert.deepEqual(await resp.json(),{error:'route_not_allowed'});
 }

 // Allowed route POST /v1/chat/completions with inboundToken + fleetToken-signed replay bundle -> 200, 0 disk writes, 0 leaked headers
 const ok=await fetch(gUrl+'/v1/chat/completions',{
  method:'POST',
  headers:replayHeaders(inboundToken,bootId,sampleBody,{authKey:fleetToken,extra:{'x-murakumo-job-id':'ext-id','cf-ray':'ray-05'}}),
  body:sampleBody
 });
 assert.equal(ok.status,200);
 assert.equal(ok.headers.get('x-murakumo-job-id'),null);
 assert.equal(ok.headers.get('x-murakumo-node'),null);
 assert.equal(ok.headers.get('cf-ray'),null);
 await ok.json();
 assert.equal(backend.state.calls,1);
 assert.deepEqual(await readdir(stateDir),[],'0 files written to stateDir');
});

test('TEST-INFER-PRIV-06: B1 disconnect (502 execution_unknown) -> restart B2 -> old request returns 410 executed:unknown retryable:false with 0 gateway auto-resign (+ negative mutant & provisional 409 check)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv06-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`};

 const backend=makeBackend({disconnectOn:'DROP_ONCE'});
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'priv06-node',bind:'127.0.0.1',port:rp,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={
  node:'priv06-gw',bind:'127.0.0.1',port:gp,
  'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram',
  'probe-interval-ms':40,
  peers:[{node:'priv06-node',url:rUrl}]
 };
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));
 await writeFile(gPath,JSON.stringify(gCfg));

 // Verify mint-replay-headers? still refused fail-closed on gateway
 const badGwPath=join(dir,'g-mint.json');
 await writeFile(badGwPath,JSON.stringify({...gCfg,port:await port(),'mint-replay-headers?':true}));
 const badGw=spawn(process.execPath,[cli,'gateway','serve','--config',badGwPath],{cwd:dir,stdio:'ignore'});
 assert.notEqual((await once(badGw,'close'))[0],0,'gateway must refuse mint-replay-headers?: true');

 let rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv06-node']?.lanes?.text?.ready);
 const boot1=(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers['priv06-node'].boot_id;

 // Request signed for B1 disconnects mid-flight -> 502 execution_unknown, retryable: false
 const dropBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'DROP_ONCE'}]});
 const b1Nonce=randomBytes(16).toString('hex');
 const b1Issued=Date.now();
 const b1Headers=replayHeaders(token,boot1,dropBody,{nonce:b1Nonce,issuedAt:b1Issued});
 const res502=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:b1Headers,body:dropBody});
 assert.equal(res502.status,502);
 assert.deepEqual(await res502.json(),{error:'execution_unknown',retryable:false});
 assert.equal(backend.state.calls,1);

 // Restart resident as B2
 rProc.kill('SIGTERM');
 await once(rProc,'exit');
 rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 await wait(async()=>{
  const h=await(await fetch(gUrl+'/health',{headers:auth})).json();
  const p=h.peers?.['priv06-node'];
  return p?.lanes?.text?.ready&&p?.boot_id&&p.boot_id!==boot1;
 });
 const boot2=(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers['priv06-node'].boot_id;
 assert.notEqual(boot2,boot1);

 // Replay exact old B1 request through gateway -> MUST return 410 boot_id_mismatch {executed:"unknown", retryable:false}, 0 calls on B2
 const replayOld=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:b1Headers,body:dropBody});
 assert.equal(replayOld.status,410);
 assert.deepEqual(await replayOld.json(),{error:'boot_id_mismatch',executed:'unknown',retryable:false});
 assert.equal(backend.state.calls,1,'gateway must perform 0 automatic re-signing/re-dispatch to B2');

 // Negative mutant check: prove that an auto-resigning mutant WOULD cause double execution on B2 (confirming test sensitivity)
 const mutantBody=sampleBody;
 const mutantResignHeaders=replayHeaders(token,boot2,mutantBody,{nonce:b1Nonce,issuedAt:Date.now()});
 const mutantRes=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:mutantResignHeaders,body:mutantBody});
 assert.equal(mutantRes.status,200,'mutant check: re-signing onto B2 would execute and break single-use');
 assert.equal(backend.state.calls,2,'mutant check confirms test catches any auto-resign regression');

 // Provisional pre-boot window also returns 409 {error:"provisional_pre_boot_window", executed:"unknown", retryable:false}
 const pp=await port();
 const provCfg={
  node:'priv06-prov',bind:'127.0.0.1',port:pp,
  'token-file':tokenFile,'state-file':join(dir,'prov-receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'allow-provisional-no-boot-id?':true,
  'job-timeout-ms':1000,'replay-window-ms':2000,'replay-clock-skew-ms':500,'replay-rotation-ms':3000,
  'probe-interval-ms':30000,
  lanes:rCfg.lanes
 };
 const provPath=join(dir,'prov.json');
 await writeFile(provPath,JSON.stringify(provCfg));
 const provStart=Date.now();
 const provProc=spawn(process.execPath,[cli,'resident','serve','--config',provPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>provProc.kill('SIGTERM'));
 const provUrl=`http://127.0.0.1:${pp}`;
 await wait(async()=>(await(await fetch(provUrl+'/health',{headers:auth})).json()).lanes?.text?.ready);

 const preBoot=await fetch(provUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,null,sampleBody,{issuedAt:provStart}),body:sampleBody});
 assert.equal(preBoot.status,409);
 assert.deepEqual(await preBoot.json(),{error:'provisional_pre_boot_window',executed:'unknown',retryable:false});
 assert.equal(backend.state.calls,2,'provisional pre-boot rejection must execute 0 backend calls');
});

test('TEST-INFER-PRIV-07: authenticated (issued_at, replay_nonce, resident_boot_id) + payload bundle rejects single-field tamper, body substitution, and plaintext header overwrite with 0 backend calls',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv07-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const fleetToken=randomBytes(32).toString('hex');
 const replayAuthKey=randomBytes(32).toString('hex');
 const fleetTokenFile=join(dir,'fleet.token');
 const replayAuthFile=join(dir,'replay-auth.token');
 await writeFile(fleetTokenFile,fleetToken,{mode:0o600});
 await writeFile(replayAuthFile,replayAuthKey,{mode:0o600});
 const auth={authorization:`Bearer ${fleetToken}`};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'priv07-node',bind:'127.0.0.1',port:rp,
  'token-file':fleetTokenFile,'replay-auth-token-file':replayAuthFile,
  'state-file':join(dir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={
  node:'priv07-gw',bind:'127.0.0.1',port:gp,
  'token-file':fleetTokenFile,'replay-ledger-mode':'kagishi-ram',
  'probe-interval-ms':50,
  peers:[{node:'priv07-node',url:rUrl}]
 };
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));
 await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv07-node']?.lanes?.text?.ready);
 const bootId=(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers['priv07-node'].boot_id;

 const nonce=randomBytes(16).toString('hex');
 const issuedAt=String(Date.now());
 const validSig=signReplayBundle(replayAuthKey,bootId,nonce,issuedAt,sampleBody);
 const baseHeaders={
  authorization:`Bearer ${fleetToken}`,'content-type':'application/json',
  'x-murakumo-boot-id':bootId,'x-murakumo-replay-nonce':nonce,
  'x-murakumo-issued-at':issuedAt,'x-murakumo-replay-auth':validSig
 };

 // Missing replay auth -> 401
 const noAuth={...baseHeaders};delete noAuth['x-murakumo-replay-auth'];
 const rNoAuth=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:noAuth,body:sampleBody});
 assert.equal(rNoAuth.status,401);
 assert.equal((await rNoAuth.json()).error,'replay_auth_required');

 // Malformed replay auth -> 400
 const rBadFmt=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...baseHeaders,'x-murakumo-replay-auth':'not-hex'},body:sampleBody});
 assert.equal(rBadFmt.status,400);

 // Wrong key (signed with fleetToken instead of replayAuthKey) -> 403
 const wrongKeySig=signReplayBundle(fleetToken,bootId,nonce,issuedAt,sampleBody);
 const rWrongKey=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...baseHeaders,'x-murakumo-replay-auth':wrongKeySig},body:sampleBody});
 assert.equal(rWrongKey.status,403);

 // Tamper issued_at by 1ms -> 403
 const rTamperIat=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...baseHeaders,'x-murakumo-issued-at':String(Number(issuedAt)+1)},body:sampleBody});
 assert.equal(rTamperIat.status,403);

 // Tamper replay_nonce by 1 char -> 403
 const flippedNonce=(nonce[0]==='a'?'b':'a')+nonce.slice(1);
 const rTamperNonce=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...baseHeaders,'x-murakumo-replay-nonce':flippedNonce},body:sampleBody});
 assert.equal(rTamperNonce.status,403);

 // Tamper resident_boot_id by 1 char (plaintext header overwrite) -> 403
 const flippedBoot=(bootId[0]==='a'?'b':'a')+bootId.slice(1);
 const rTamperBoot=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...baseHeaders,'x-murakumo-boot-id':flippedBoot},body:sampleBody});
 assert.equal(rTamperBoot.status,403);

 // Payload substitution (different body with original sig) -> 403
 const substitutedBody=sampleBody.replace('hello kagishi privacy','evil substituted body');
 const rSubBody=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:baseHeaders,body:substitutedBody});
 assert.equal(rSubBody.status,403);

 // Conflicting x-murakumo-replay-auth vs x-murakumo-replay-sig -> 400
 const rConflict=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...baseHeaders,'x-murakumo-replay-sig':'0'.repeat(64)},body:sampleBody});
 assert.equal(rConflict.status,400);

 assert.equal(backend.state.calls,0,'all tampered/unauthenticated bundles must result in 0 backend calls');

 // Untampered authentic bundle succeeds
 const rOk=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:baseHeaders,body:sampleBody});
 assert.equal(rOk.status,200);
 await rOk.json();
 assert.equal(backend.state.calls,1);
});

test('TEST-INFER-PRIV-08: privacy-lane gateway forbids fallback to durable peers (0 durable calls, 0 receipts.json created when durable-only or privacy peer unready)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv08-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const privStateDir=join(dir,'priv-state');
 const durStateDir=join(dir,'dur-state');
 await (await import('node:fs/promises')).mkdir(privStateDir,{recursive:true});
 await (await import('node:fs/promises')).mkdir(durStateDir,{recursive:true});
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`};

 const durBackend=makeBackend();
 const privBackend=makeBackend();
 durBackend.server.listen(0,'127.0.0.1');
 privBackend.server.listen(0,'127.0.0.1');
 await Promise.all([once(durBackend.server,'listening'),once(privBackend.server,'listening')]);
 t.after(()=>{
  durBackend.server.closeAllConnections();durBackend.server.close();
  privBackend.server.closeAllConnections();privBackend.server.close();
 });

 // Launch a healthy durable peer
 const durPort=await port();
 const durUrl=`http://127.0.0.1:${durPort}`;
 const durCfg={
  node:'dur0',bind:'127.0.0.1',port:durPort,
  'token-file':tokenFile,'state-file':join(durStateDir,'receipts.json'),
  'observations?':false,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${durBackend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const durPath=join(dir,'dur.json');
 await writeFile(durPath,JSON.stringify(durCfg));
 const durProc=spawn(process.execPath,[cli,'resident','serve','--config',durPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>durProc.kill('SIGTERM'));
 await wait(async()=>(await(await fetch(durUrl+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const durHealth=await(await fetch(durUrl+'/health',{headers:auth})).json();
 assert.equal(durHealth.replay_mode,'durable');

 // Also a mock peer advertising kagishi-ram with INVALID boot_id (must be rejected by INFER-PRIV-09)
 let badBootCalls=0;
 const badBootPeer=createServer((req,res)=>{
  if(req.url==='/health'){
   return res.end(JSON.stringify({node:'badboot0',replay_mode:'kagishi-ram',boot_id:'not-128-bit',lanes:{text:{kind:'text',model:'mishima',context:32768,ready:true,busy:false}}}));
  }
  badBootCalls++;
  req.resume();req.on('end',()=>res.end('{}'));
 });
 badBootPeer.listen(0,'127.0.0.1');
 await once(badBootPeer,'listening');
 t.after(()=>{badBootPeer.closeAllConnections();badBootPeer.close();});

 // Reserve port for privacy peer (initially NOT running -> only durable + badboot are alive)
 const privPort=await port();
 const privUrl=`http://127.0.0.1:${privPort}`;
 const gp=await port();
 const gUrl=`http://127.0.0.1:${gp}`;
 const gCfg={
  node:'priv08-gw',bind:'127.0.0.1',port:gp,
  'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram',
  'probe-interval-ms':40,
  peers:[
   {node:'dur0',url:durUrl},
   {node:'badboot0',url:`http://127.0.0.1:${badBootPeer.address().port}`},
   {node:'priv0',url:privUrl}
  ]
 };
 const gPath=join(dir,'gw.json');
 await writeFile(gPath,JSON.stringify(gCfg));
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>gProc.kill('SIGTERM'));

 // Wait for gateway to start polling
 await wait(async()=>{
  const r=await fetch(gUrl+'/health',{headers:auth});
  return r.status===503;
 });
 const hNoPriv=await(await fetch(gUrl+'/health',{headers:auth})).json();
 assert.deepEqual(hNoPriv.peers,{},'privacy gateway must not expose durable or invalid-boot_id peers as eligible');

 // Case A: Only durable + invalid-boot_id peers alive -> MUST refuse 503 executed:false, 0 durable calls!
 const resRefused=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,durHealth.boot_id,sampleBody),body:sampleBody});
 assert.equal(resRefused.status,503);
 assert.deepEqual(await resRefused.json(),{error:'no_privacy_peer_available',executed:false});
 assert.equal(durBackend.state.calls,0,'durable peer MUST NEVER receive fallback execution');
 assert.equal(durBackend.state.tokenizes,0,'durable peer MUST NEVER receive fallback /estimate calls');
 assert.equal(badBootCalls,0,'peer with invalid boot_id must not receive calls');

 // Case B: Launch privacy peer with backend initially busy (unready) -> still refuses 503, 0 durable calls!
 privBackend.state.busy=true;
 const privCfg={
  node:'priv0',bind:'127.0.0.1',port:privPort,
  'token-file':tokenFile,'state-file':join(privStateDir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'probe-interval-ms':100,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${privBackend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const privPath=join(dir,'priv.json');
 await writeFile(privPath,JSON.stringify(privCfg));
 const privProc=spawn(process.execPath,[cli,'resident','serve','--config',privPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>privProc.kill('SIGTERM'));
 await wait(async()=>(await(await fetch(privUrl+'/health',{headers:auth})).json()).boot_id);
 await sleep(150);

 const privBoot=(await(await fetch(privUrl+'/health',{headers:auth})).json()).boot_id;
 const resUnready=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,privBoot,sampleBody),body:sampleBody});
 assert.equal(resUnready.status,503);
 assert.deepEqual(await resUnready.json(),{error:'no_privacy_peer_available',executed:false});
 assert.equal(durBackend.state.calls,0,'unready privacy peer must NOT trigger fallback to durable peer');

 // Case C: Privacy peer becomes ready -> succeeds exclusively on privacy peer; durable peer still has 0 calls; 0 receipts.json on privacy peer
 privBackend.state.busy=false;
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.priv0?.lanes?.text?.ready);
 const resOk=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,privBoot,sampleBody),body:sampleBody});
 assert.equal(resOk.status,200);
 await resOk.json();
 assert.equal(privBackend.state.calls,1);
 assert.equal(durBackend.state.calls,0,'durable peer still has 0 calls');
 assert.deepEqual(await readdir(privStateDir),[],'0 receipts.json created on privacy peer');
});
