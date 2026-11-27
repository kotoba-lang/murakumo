import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm,readdir,stat} from 'node:fs/promises';
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

function makeBackend(){
 const state={calls:0,tokenizes:0};
 const server=createServer((req,res)=>{
  if(req.url==='/slots')return res.end('[{"is_processing":false}]');
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
   res.end(JSON.stringify({choices:[{message:{content:'OK'}}],usage:{prompt_tokens:5,completion_tokens:2}}));
  });
 });
 return {state,server};
}

function replayHeaders(token,bootId,{jobId,nonce,issuedAt}={}){
 const h={
  authorization:`Bearer ${token}`,
  'content-type':'application/json',
  'x-murakumo-job-id':jobId??`job_${randomBytes(12).toString('hex')}`,
  'x-murakumo-replay-nonce':nonce??randomBytes(16).toString('hex'),
  'x-murakumo-issued-at':String(issuedAt??Date.now())
 };
 if(bootId!==undefined&&bootId!==null)h['x-murakumo-boot-id']=bootId;
 return h;
}

const sampleBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'hello kagishi'}]});

test('kagishi-ram: zero disk writes, owner-id/bound-owner checks, duplicate 409, window 403, boot_id 410, nonce independence 400',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-kagishi-ram-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`,'content-type':'application/json'};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const processes=[];
 t.after(()=>{for(const p of processes)p.kill('SIGTERM');});

 async function launch(config,name){
  const cfgPath=join(dir,`${name}.json`);
  await writeFile(cfgPath,JSON.stringify(config));
  const p=spawn(process.execPath,[cli,'resident','serve','--config',cfgPath],{cwd:dir,stdio:['ignore','pipe','pipe']});
  let err='';
  p.stderr.on('data',b=>err+=b);
  processes.push(p);
  return {p,err:()=>err};
 }

 // Pre-seed .owner-id WITHOUT receipts.json (durable mode would refuse; kagishi-ram must succeed)
 const ownerId=randomUUID();
 const stateFile=join(dir,'receipts.json');
 await writeFile(stateFile+'.owner-id',ownerId,{mode:0o600});

 // Mismatched .bound-owner must still fail closed even in kagishi-ram mode
 const badOwner=randomUUID();
 await writeFile(stateFile+'.bound-owner',badOwner,{mode:0o600});
 const badPort=await port();
 const baseConfig={
  node:'kagishi0',bind:'127.0.0.1',port:badPort,
  'token-file':tokenFile,'state-file':stateFile,
  'receipt-mode':'kagishi-ram','observations?':false,
  'replay-window-ms':5000,'replay-clock-skew-ms':500,'replay-rotation-ms':6000,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const mismatch=await launch(baseConfig,'mismatch');
 const [mismatchCode]=await once(mismatch.p,'exit');
 assert.notEqual(mismatchCode,0,'mismatched .bound-owner must refuse startup');
 assert.equal(existsSync(stateFile),false,'receipts.json must not be created');

 // Matching .bound-owner without receipts.json must succeed
 await writeFile(stateFile+'.bound-owner',ownerId,{mode:0o600});
 const p1=await port();
 const cfg1={...baseConfig,port:p1};
 const r1=await launch(cfg1,'r1');
 const url1=`http://127.0.0.1:${p1}`;
 const health1=async()=>(await fetch(url1+'/health',{headers:auth})).json();
 await wait(async()=>(await health1()).lanes?.text?.ready);

 const h1=await health1();
 assert.equal(h1.replay_mode,'kagishi-ram');
 assert.match(h1.boot_id,/^[a-f0-9]{32}$/);
 assert.equal(existsSync(stateFile),false,'receipts.json never created on startup');
 assert.equal(existsSync(stateFile+'.poi-receipts.jsonl'),false,'poi-receipts defaults off in kagishi-ram');

 // 1. Valid request succeeds once
 const nonce1=randomBytes(16).toString('hex');
 const resOk=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{nonce:nonce1}),body:sampleBody});
 assert.equal(resOk.status,200);
 await resOk.json();
 assert.equal(backend.state.calls,1);
 assert.equal(existsSync(stateFile),false,'receipts.json never created after execution');
 assert.equal(existsSync(stateFile+'.poi-receipts.jsonl'),false);

 // 2. Duplicate replay_nonce rejected 409 (even with new job-id and different body), zero re-execution
 const beforeTok=backend.state.tokenizes;
 const dup=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{nonce:nonce1}),body:sampleBody.replace('hello kagishi','changed payload')});
 assert.equal(dup.status,409);
 assert.equal((await dup.json()).executed,'unknown');
 assert.equal(backend.state.calls,1);
 assert.equal(backend.state.tokenizes,beforeTok,'duplicate replay_nonce rejected before tokenizer');

 // 3. Expired and future issued_at rejected before admission
 const expired=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{issuedAt:Date.now()-6000}),body:sampleBody});
 assert.equal(expired.status,403);
 assert.deepEqual(await expired.json(),{error:'replay_issued_at_expired',executed:false});

 const future=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{issuedAt:Date.now()+1000}),body:sampleBody});
 assert.equal(future.status,403);
 assert.deepEqual(await future.json(),{error:'replay_issued_at_future',executed:false});

 const badIssued=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{issuedAt:'not-a-timestamp'}),body:sampleBody});
 assert.equal(badIssued.status,400);
 assert.equal((await badIssued.json()).error,'invalid_issued_at');

 assert.equal(backend.state.calls,1);
 assert.equal(backend.state.tokenizes,beforeTok,'expired/future rejected before tokenizer');

 // 4. Missing or mismatched boot_id rejected
 const noBoot=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,null),body:sampleBody});
 assert.equal(noBoot.status,400);
 assert.equal((await noBoot.json()).error,'boot_id_required');

 const wrongBoot=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,randomBytes(16).toString('hex')),body:sampleBody});
 assert.equal(wrongBoot.status,410);
 assert.equal((await wrongBoot.json()).error,'boot_id_mismatch');

 // 5. replay_nonce == job-id and deterministic derivations rejected
 const shared=randomBytes(16).toString('hex');
 const sameAsJob=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{jobId:shared,nonce:shared}),body:sampleBody});
 assert.equal(sameAsJob.status,400);
 assert.equal((await sameAsJob.json()).error,'replay_nonce_not_independent');

 const suffixNonce=randomBytes(16).toString('hex');
 const suffixMatch=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{jobId:`kagishi0_text_${suffixNonce}`,nonce:suffixNonce}),body:sampleBody});
 assert.equal(suffixMatch.status,400);
 assert.equal((await suffixMatch.json()).error,'replay_nonce_not_independent');

 const jobForHash='job_hash_check_1234';
 const hashedNonce=createHash('sha256').update(jobForHash).digest('hex');
 const hashMatch=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{jobId:jobForHash,nonce:hashedNonce}),body:sampleBody});
 assert.equal(hashMatch.status,400);
 assert.equal((await hashMatch.json()).error,'replay_nonce_not_independent');

 const shortNonce=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{nonce:'too_short_nonce'}),body:sampleBody});
 assert.equal(shortNonce.status,400);
 assert.equal((await shortNonce.json()).error,'invalid_replay_nonce');

 assert.equal(backend.state.calls,1);

 // 6. Restart generates fresh boot_id; old boot_id rejected (410) and existing receipts.json untouched
 r1.p.kill('SIGTERM');
 await once(r1.p,'exit');

 // Place a sentinel receipts.json on disk and verify ram-hmac alias never reads, modifies, or deletes it
 const sentinel='SENTINEL_UNTOUCHED_NOT_JSON';
 await writeFile(stateFile,sentinel,{mode:0o600});
 const beforeStat=await stat(stateFile);
 await sleep(20);

 const cfg2={...cfg1,'receipt-mode':'ram-hmac'};
 const r2=await launch(cfg2,'r2');
 await wait(async()=>(await health1()).lanes?.text?.ready);
 const h2=await health1();
 assert.equal(h2.replay_mode,'kagishi-ram');
 assert.match(h2.boot_id,/^[a-f0-9]{32}$/);
 assert.notEqual(h2.boot_id,h1.boot_id,'boot_id rotates on restart');

 const staleBoot=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id),body:sampleBody});
 assert.equal(staleBoot.status,410);
 assert.equal((await staleBoot.json()).error,'boot_id_mismatch');
 assert.equal(backend.state.calls,1);

 const freshAfterRestart=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h2.boot_id),body:sampleBody});
 assert.equal(freshAfterRestart.status,200);
 await freshAfterRestart.json();
 assert.equal(backend.state.calls,2);

 const afterStat=await stat(stateFile);
 assert.equal(await readFile(stateFile,'utf8'),sentinel,'pre-existing receipts.json must not be modified or deleted');
 assert.equal(afterStat.mtimeMs,beforeStat.mtimeMs,'pre-existing receipts.json mtime must be unchanged');
});

test('kagishi-ram: provisional omitted boot_id rejects pre-boot window and accepts after started_at + delta',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-kagishi-prov-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const p=await port();
 const cfg={
  node:'prov0',bind:'127.0.0.1',port:p,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'receipt-mode':'kagishi-ram','observations?':false,
  'allow-provisional-no-boot-id?':true,
  'replay-window-ms':2000,'replay-clock-skew-ms':400,'replay-rotation-ms':2800,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const cfgPath=join(dir,'cfg.json');
 await writeFile(cfgPath,JSON.stringify(cfg));
 const startedApprox=Date.now();
 const child=spawn(process.execPath,[cli,'resident','serve','--config',cfgPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>child.kill('SIGTERM'));

 const url=`http://127.0.0.1:${p}`;
 await wait(async()=>(await(await fetch(url+'/health',{headers:auth})).json()).lanes?.text?.ready);

 // issued_at < resident_started_at + delta (even if within normal window) must fail closed
 const preBoot=await fetch(url+'/execute/text',{method:'POST',headers:replayHeaders(token,null,{issuedAt:startedApprox}),body:sampleBody});
 assert.equal(preBoot.status,409);
 assert.deepEqual(await preBoot.json(),{error:'provisional_pre_boot_window',executed:false});
 assert.equal(backend.state.calls,0);

 // Wait past started_at + delta
 await sleep(500);
 const provNonce=randomBytes(16).toString('hex');
 const ok=await fetch(url+'/execute/text',{method:'POST',headers:replayHeaders(token,null,{nonce:provNonce,issuedAt:Date.now()}),body:sampleBody});
 assert.equal(ok.status,200);
 await ok.json();
 assert.equal(backend.state.calls,1);

 // Replay of same provisional nonce is still rejected 409
 const dup=await fetch(url+'/execute/text',{method:'POST',headers:replayHeaders(token,null,{nonce:provNonce,issuedAt:Date.now()}),body:sampleBody});
 assert.equal(dup.status,409);
 assert.equal(backend.state.calls,1);
});

test('kagishi-ram: 2-generation rotation across T_rot, exact HMAC vector, and K_prev zeroization',async t=>{
 // Part A: Direct cryptographic vector + Buffer.fill(0) zeroization verification against resident.cljk
 const verifyClj=`
 (require '[murakumo.infer.resident :as r] '["node:crypto" :as crypto])
 (let [key (.from js/Buffer "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" "hex")
       boot-id "00112233445566778899aabbccddeeff"
       nonce "fedcba9876543210fedcba9876543210"
       tag (r/replay-tag key boot-id nonce)
       ;; Independently construct expected HMAC-SHA256
       b-boot (js/Buffer.from boot-id "utf8")
       b-nonce (js/Buffer.from nonce "utf8")
       len1 (.alloc js/Buffer 4)
       len2 (.alloc js/Buffer 4)
       _ (.writeUInt32BE len1 (.-length b-boot) 0)
       _ (.writeUInt32BE len2 (.-length b-nonce) 0)
       prefix (.concat js/Buffer #js [(js/Buffer.from "awai/v1/murakumo-replay" "utf8") (js/Buffer.from #js [0])])
       expected (.digest (.update (.createHmac crypto "sha256" key)
                                  (.concat js/Buffer #js [prefix len1 b-boot len2 b-nonce])) "hex")
       _ (when-not (= tag expected) (throw (js/Error. (str "hmac mismatch: " tag " != " expected))))
       ledger (r/make-replay-ledger {:replay-window-ms 400 :replay-clock-skew-ms 100 :replay-rotation-ms 600 :max-receipts 10} boot-id 1000)
       k0 (:k-curr @ledger)
       s0 (:curr-set @ledger)
       state (atom {:groups {}})
       lane {:group "gpu" :ready true}
       ;; Admit in gen 0 (t=1400)
       a1 (r/admit-ram-replay! state ledger {} lane "job_00000001" nonce 1400)
       _ (when-not (= 200 (:status a1)) (throw (js/Error. "expected 200")))
       _ (swap! state assoc :groups {})
       ;; Rotation 1 at t=1650: k0 -> k-prev, s0 -> prev-set; nonce still rejected
       _ (r/rotate-replay-ledger! ledger 1650)
       _ (when-not (identical? k0 (:k-prev @ledger)) (throw (js/Error. "k0 must move to k-prev")))
       _ (when-not (r/replay-seen? ledger nonce 1700) (throw (js/Error. "nonce must survive 1st rotation in prev-set")))
       a2 (r/admit-ram-replay! state ledger {} lane "job_00000002" nonce 1700)
       _ (when-not (= 409 (:status a2)) (throw (js/Error. "expected 409 after 1st rotation")))
       k1 (:k-curr @ledger)
       ;; Rotation 2 at t=2300: k0 (old k-prev) MUST be zeroized and s0 cleared!
       _ (r/rotate-replay-ledger! ledger 2300)
       _ (when-not (.every k0 #(= 0 %)) (throw (js/Error. "k0 was NOT zeroized on 2nd rotation")))
       _ (when-not (= 0 (.-size s0)) (throw (js/Error. "s0 was NOT cleared on 2nd rotation")))
       _ (when-not (identical? k1 (:k-prev @ledger)) (throw (js/Error. "k1 must move to k-prev")))
       ;; Destroy zeroizes remaining keys
       k2 (:k-curr @ledger)
       _ (r/destroy-replay-ledger! ledger)
       _ (when-not (and (.every k1 #(= 0 %)) (.every k2 #(= 0 %)))
           (throw (js/Error. "destroy did not zeroize remaining keys")))]
   (println "CRYPTO_ROTATION_OK"))
 `;
 const cljDir=await mkdtemp(join(tmpdir(),'murakumo-kagishi-crypto-'));
 t.after(()=>rm(cljDir,{recursive:true,force:true}));
 await writeFile(join(cljDir,'nbb.edn'),'{:paths []}\n');
 const cljOut=execFileSync('kbb',['--backend','sci','--classpath',join(root,'src'),'-e',verifyClj],{cwd:cljDir,encoding:'utf8'});
 assert.match(cljOut,/CRYPTO_ROTATION_OK/);

 // Part B: Live HTTP rotation across T_rot via release/node.mjs
 const dir=await mkdtemp(join(tmpdir(),'murakumo-kagishi-rot-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const p=await port();
 // W=500, delta=100, T_rot=700 (700 >= 500 + 2*100)
 const cfg={
  node:'rot0',bind:'127.0.0.1',port:p,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'receipt-mode':'kagishi-ram','observations?':false,
  'replay-window-ms':500,'replay-clock-skew-ms':100,'replay-rotation-ms':700,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const cfgPath=join(dir,'cfg.json');
 await writeFile(cfgPath,JSON.stringify(cfg));
 const started=Date.now();
 const child=spawn(process.execPath,[cli,'resident','serve','--config',cfgPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>child.kill('SIGTERM'));

 const url=`http://127.0.0.1:${p}`;
 await wait(async()=>(await(await fetch(url+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const {boot_id:bootId}=await(await fetch(url+'/health',{headers:auth})).json();

 // Wait until ~400ms after startup so R1's issued_at stays valid across rotation 1 (at ~700ms)
 const wait1=Math.max(0,started+400-Date.now());
 if(wait1>0)await sleep(wait1);
 const r1Nonce=randomBytes(16).toString('hex');
 const r1Issued=Date.now();
 const first=await fetch(url+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:r1Nonce,issuedAt:r1Issued}),body:sampleBody});
 assert.equal(first.status,200);
 await first.json();
 assert.equal(backend.state.calls,1);

 // Wait past 1st rotation (~820ms after startup); r1Issued + 600 > 820 so window still valid, must hit prev_set (409)
 const wait2=Math.max(0,started+820-Date.now());
 if(wait2>0)await sleep(wait2);
 const afterRot1=await fetch(url+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:r1Nonce,issuedAt:r1Issued}),body:sampleBody});
 assert.equal(afterRot1.status,409,'entry must survive 1st rotation in prev_set');
 assert.equal(backend.state.calls,1);

 // Wait past 2nd rotation (~1550ms after startup); old issued_at is expired (403)
 const wait3=Math.max(0,started+1550-Date.now());
 if(wait3>0)await sleep(wait3);
 const afterRot2=await fetch(url+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:r1Nonce,issuedAt:r1Issued}),body:sampleBody});
 assert.equal(afterRot2.status,403,'old issued_at must be expired by 2nd rotation');
 assert.equal((await afterRot2.json()).error,'replay_issued_at_expired');
 assert.equal(backend.state.calls,1);
});

test('kagishi-ram: capacity overflow returns 503 replay_ledger_full with zero LRU eviction, and gateway forwards replay headers',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-kagishi-cap-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const auth={authorization:`Bearer ${token}`};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'cap0',bind:'127.0.0.1',port:rp,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'receipt-mode':'kagishi-ram','observations?':false,
  'max-receipts':2,
  'replay-window-ms':10000,'replay-clock-skew-ms':1000,'replay-rotation-ms':12000,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={
  node:'gw0',bind:'127.0.0.1',port:gp,
  'token-file':tokenFile,'probe-interval-ms':50,
  peers:[{node:'cap0',url:rUrl}]
 };
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));
 await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>{
  const h=await(await fetch(gUrl+'/health',{headers:auth})).json();
  return h.peers?.cap0?.lanes?.text?.ready;
 });

 const gwHealth=await(await fetch(gUrl+'/health',{headers:auth})).json();
 const bootId=gwHealth.peers.cap0.boot_id;
 assert.equal(gwHealth.peers.cap0.replay_mode,'kagishi-ram');
 assert.match(bootId,/^[a-f0-9]{32}$/);

 // 1st entry through gateway (preserving caller replay headers)
 const n1=randomBytes(16).toString('hex');
 const gwReq1=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:replayHeaders(token,bootId,{nonce:n1,jobId:undefined}),body:sampleBody});
 // Remove x-murakumo-job-id on gateway call so gateway assigns node_lane_nonce while forwarding replay headers
 assert.equal(gwReq1.status,400,'gateway rejects non-node_lane_hex incoming job-id as expected');

 const gwHeaders1={
  authorization:`Bearer ${token}`,'content-type':'application/json',
  'x-murakumo-replay-nonce':n1,
  'x-murakumo-issued-at':String(Date.now()),
  'x-murakumo-boot-id':bootId
 };
 const ok1=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:gwHeaders1,body:sampleBody});
 assert.equal(ok1.status,200);
 await ok1.json();
 assert.equal(backend.state.calls,1);

 // Replay of n1 through gateway returns 409
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.cap0?.lanes?.text?.busy===false);
 const dupGw=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:gwHeaders1,body:sampleBody});
 assert.equal(dupGw.status,409);
 assert.equal(backend.state.calls,1);

 // 2nd entry directly to resident fills capacity (max-receipts = 2)
 const n2=randomBytes(16).toString('hex');
 const ok2=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:n2}),body:sampleBody});
 assert.equal(ok2.status,200);
 await ok2.json();
 assert.equal(backend.state.calls,2);

 // 3rd entry overflows capacity -> MUST fail closed with 503 {"error":"replay_ledger_full","executed":false}
 const n3=randomBytes(16).toString('hex');
 const full=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:n3}),body:sampleBody});
 assert.equal(full.status,503);
 assert.deepEqual(await full.json(),{error:'replay_ledger_full',executed:false});
 assert.equal(backend.state.calls,2);

 // Verify NO LRU eviction: n1 and n2 are STILL rejected 409
 const stillSeen1=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:n1}),body:sampleBody});
 assert.equal(stillSeen1.status,409);
 const stillSeen2=await fetch(rUrl+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:n2}),body:sampleBody});
 assert.equal(stillSeen2.status,409);
 assert.equal(backend.state.calls,2);

 // Zero disk receipt writes throughout
 assert.equal(existsSync(join(dir,'receipts.json')),false);
 const files=await readdir(dir);
 assert.ok(!files.some(f=>f.endsWith('receipts.json')||f.endsWith('.bound-owner')||f.endsWith('.poi-receipts.jsonl')),
  'unexpected disk receipt artifacts: '+JSON.stringify(files));
});
