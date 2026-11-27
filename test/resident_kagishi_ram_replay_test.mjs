import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,writeFile,readFile,rm,readdir,stat} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes,randomUUID,createHash} from 'node:crypto';

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
  'content-type':'application/json'
 };
 if(jobId!==null)h['x-murakumo-job-id']=jobId??`job_${randomBytes(12).toString('hex')}`;
 if(nonce!==null)h['x-murakumo-replay-nonce']=nonce??randomBytes(16).toString('hex');
 if(issuedAt!==null)h['x-murakumo-issued-at']=String(issuedAt??Date.now());
 if(bootId!==undefined&&bootId!==null)h['x-murakumo-boot-id']=bootId;
 return h;
}

const sampleBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'hello kagishi'}]});

test('kagishi-ram: startup refusals (legacy receipts.json untouched, bound-owner mismatch, compat-port, TTL > 900s), strict headers, /estimate disabled, PoI disabled, and restart boot_id rotation',async t=>{
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
  let out='';
  p.stdout.on('data',b=>out+=b);
  p.stderr.on('data',b=>out+=b);
  processes.push(p);
  return {p,err:()=>out};
 }

 const ownerId=randomUUID();
 const stateFile=join(dir,'receipts.json');
 const baseConfig={
  node:'kagishi0',bind:'127.0.0.1',port:await port(),
  'token-file':tokenFile,'state-file':stateFile,
  'receipt-mode':'kagishi-ram','observations?':false,
  'replay-window-ms':5000,'replay-clock-skew-ms':500,'replay-rotation-ms':6000,
  'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };

 // Negative Test 1: Existing receipts.json on disk causes fail-closed startup refusal WITHOUT deleting or modifying it
 const sentinel='{"jobs":{"legacy_job":{"status":"completed"}}}';
 await writeFile(stateFile,sentinel,{mode:0o600});
 const beforeStat=await stat(stateFile);
 await sleep(20);
 for(const mode of ['kagishi-ram','ram-hmac']){
  const refuse=await launch({...baseConfig,'receipt-mode':mode,port:await port()},`legacy-${mode}`);
  const [code]=await once(refuse.p,'close');
  assert.notEqual(code,0,`existing receipts.json must refuse startup in ${mode} mode`);
  assert.match(refuse.err(),/legacy receipts\.json/i);
  assert.equal(existsSync(stateFile),true,'legacy receipts.json must NOT be deleted');
  assert.equal(await readFile(stateFile,'utf8'),sentinel,'legacy receipts.json content must NOT be modified');
  const afterStat=await stat(stateFile);
  assert.equal(afterStat.mtimeMs,beforeStat.mtimeMs,'legacy receipts.json mtime must remain untouched');
 }
 // Remove legacy receipts.json (simulating owner-approved archival/removal)
 await rm(stateFile);

 // Negative Test: Invalid TTL / retention / sweep bounds must fail closed at startup
 {
  const tooLong=await launch({...baseConfig,port:await port(),'replay-window-ms':600000,'replay-clock-skew-ms':60000,'replay-rotation-ms':720000},'ttl-too-long');
  const [c1]=await once(tooLong.p,'close');
  assert.notEqual(c1,0,'2 * T_rot > 900000 ms must refuse startup');
  assert.match(tooLong.err(),/900000/);

  const tooShortRot=await launch({...baseConfig,port:await port(),'replay-window-ms':5000,'replay-clock-skew-ms':500,'replay-rotation-ms':5500},'rot-too-short');
  const [c2]=await once(tooShortRot.p,'close');
  assert.notEqual(c2,0,'T_rot < W + 2*delta must refuse startup');

  const badSweep=await launch({...baseConfig,port:await port(),'replay-sweep-interval-ms':900001},'sweep-too-long');
  const [c3]=await once(badSweep.p,'close');
  assert.notEqual(c3,0,'sweep-interval-ms > 900000 ms must refuse startup');
 }

 // Negative Test 5b: Enabling anonymous :compat-port under kagishi-ram must fail closed at startup
 {
  const withCompat=await launch({...baseConfig,port:await port(),'compat-port':await port(),'compat-bind':'127.0.0.1'},'compat-refused');
  const [cCompat]=await once(withCompat.p,'close');
  assert.notEqual(cCompat,0,'compat-port must refuse startup in kagishi-ram mode');
  assert.match(withCompat.err(),/compat-port/i);
 }

 // Pre-seed .owner-id WITHOUT receipts.json; mismatched .bound-owner must still fail closed
 await writeFile(stateFile+'.owner-id',ownerId,{mode:0o600});
 await writeFile(stateFile+'.bound-owner',randomUUID(),{mode:0o600});
 const mismatch=await launch({...baseConfig,port:await port()},'mismatch');
 const [mismatchCode]=await once(mismatch.p,'close');
 assert.notEqual(mismatchCode,0,'mismatched .bound-owner must refuse startup');
 assert.equal(existsSync(stateFile),false,'receipts.json must not be created');

 // Matching .bound-owner without receipts.json must succeed (also check compliant defaults)
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

 // Negative Test 5a: PoI receipts endpoint is disabled when poi-receipts? is off
 const poiUnauth=await fetch(url1+'/poi/receipts');
 assert.equal(poiUnauth.status,401,'unauthenticated /poi/receipts rejected when disabled');
 const poiAuth=await fetch(url1+'/poi/receipts',{headers:auth});
 assert.equal(poiAuth.status,404,'authenticated /poi/receipts disabled when poi-receipts? is off');
 assert.equal((await poiAuth.json()).error,'poi_receipts_disabled');

 // Negative Test 7: POST /estimate/<lane> is disabled under kagishi-ram (zero unsalted body sha256)
 const tokBeforeEst=backend.state.tokenizes;
 const estRes=await fetch(url1+'/estimate/text',{method:'POST',headers:auth,body:sampleBody});
 assert.equal(estRes.status,403,'/estimate/<lane> must be disabled in kagishi-ram mode');
 const estBody=await estRes.json();
 assert.equal(estBody.error,'estimate_disabled_in_privacy_mode');
 assert.equal('digest' in estBody,false,'/estimate/<lane> must never emit unsalted body digest');
 assert.equal(backend.state.tokenizes,tokBeforeEst,'/estimate/<lane> must not touch tokenizer in kagishi-ram');

 // Negative Test 3: Strict mode without x-murakumo-boot-id rejected with calls === 0
 const noBoot=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,null),body:sampleBody});
 assert.equal(noBoot.status,400);
 assert.deepEqual(await noBoot.json(),{error:'boot_id_required',executed:false});
 assert.equal(backend.state.calls,0);

 // Negative Test 4: Missing/partial headers & nonce === job-id all rejected with calls === 0
 const noNonce=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{nonce:null}),body:sampleBody});
 assert.equal(noNonce.status,400);
 assert.equal((await noNonce.json()).error,'invalid_replay_nonce');

 const noIssued=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{issuedAt:null}),body:sampleBody});
 assert.equal(noIssued.status,400);
 assert.equal((await noIssued.json()).error,'invalid_issued_at');

 const noJobId=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{jobId:null}),body:sampleBody});
 assert.equal(noJobId.status,400);
 assert.equal((await noJobId.json()).error,'job_id_required');

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

 const wrongBoot=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,randomBytes(16).toString('hex')),body:sampleBody});
 assert.equal(wrongBoot.status,410);
 assert.equal((await wrongBoot.json()).error,'boot_id_mismatch');

 const badIssued=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{issuedAt:'not-a-timestamp'}),body:sampleBody});
 assert.equal(badIssued.status,400);
 assert.equal((await badIssued.json()).error,'invalid_issued_at');

 const expired=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{issuedAt:Date.now()-6000}),body:sampleBody});
 assert.equal(expired.status,403);
 assert.deepEqual(await expired.json(),{error:'replay_issued_at_expired',executed:false});

 const future=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{issuedAt:Date.now()+1000}),body:sampleBody});
 assert.equal(future.status,403);
 assert.deepEqual(await future.json(),{error:'replay_issued_at_future',executed:false});

 assert.equal(backend.state.calls,0,'all rejected requests must execute zero backend calls');
 assert.equal(backend.state.tokenizes,tokBeforeEst,'all rejected requests must fail before tokenizer');

 // Positive: Valid request succeeds once
 const nonce1=randomBytes(16).toString('hex');
 const resOk=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{nonce:nonce1}),body:sampleBody});
 assert.equal(resOk.status,200);
 await resOk.json();
 assert.equal(backend.state.calls,1);
 assert.equal(existsSync(stateFile),false,'receipts.json never created after execution');
 assert.equal(existsSync(stateFile+'.poi-receipts.jsonl'),false,'poi-receipts.jsonl never written when disabled');

 // Duplicate replay_nonce rejected 409 (even with new job-id and different body), zero re-execution
 const beforeTok=backend.state.tokenizes;
 const dup=await fetch(url1+'/execute/text',{method:'POST',headers:replayHeaders(token,h1.boot_id,{nonce:nonce1}),body:sampleBody.replace('hello kagishi','changed payload')});
 assert.equal(dup.status,409);
 assert.equal((await dup.json()).executed,'unknown');
 assert.equal(backend.state.calls,1);
 assert.equal(backend.state.tokenizes,beforeTok,'duplicate replay_nonce rejected before tokenizer');

 // Restart rotates boot_id (with ram-hmac alias and default compliant TTL parameters); old boot_id rejected 410
 r1.p.kill('SIGTERM');
 await once(r1.p,'exit');

 const cfg2={
  node:'kagishi0',bind:'127.0.0.1',port:p1,
  'token-file':tokenFile,'state-file':stateFile,
  'receipt-mode':'ram-hmac','observations?':false,
  'probe-interval-ms':30000,
  lanes:baseConfig.lanes
 };
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
 assert.equal(existsSync(stateFile),false);
 assert.equal(existsSync(stateFile+'.poi-receipts.jsonl'),false);
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

test('kagishi-ram: TTL <= 900s defaults, pre-admit rotation, exact HMAC vector, K_prev zeroization, and purge-after-reuse rejection',async t=>{
 // Part A: Direct cryptographic vector, defaults <= 900s, pre-admit rotation, Buffer.fill(0) zeroization, and post-purge replay rejection
 const verifyClj=`
 (require '[murakumo.infer.resident :as r] '["node:crypto" :as crypto])
 (let [;; Verify compliant default TTL & sweep <= 900000 ms
       def-ledger (r/make-replay-ledger {} "00112233445566778899aabbccddeeff" 1000)
       _ (when-not (<= (* 2 (:rotation-ms @def-ledger)) 900000)
           (throw (js/Error. (str "default 2*T_rot exceeds 900000ms: " (* 2 (:rotation-ms @def-ledger))))))
       _ (when-not (>= (:rotation-ms @def-ledger) (+ (:window-ms @def-ledger) (* 2 (:clock-skew-ms @def-ledger))))
           (throw (js/Error. "default T_rot < W + 2*delta")))
       _ (when-not (<= (:sweep-interval-ms @def-ledger) 900000)
           (throw (js/Error. "default sweep-interval-ms > 900000ms")))
       _ (r/destroy-replay-ledger! def-ledger)
       key (.from js/Buffer "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" "hex")
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
       r1-issued 1400
       ;; Admit R1 in gen 0 (t=1400)
       a1 (r/admit-ram-replay! state ledger {} lane "job_00000001" nonce 1400)
       _ (when-not (= 200 (:status a1)) (throw (js/Error. "expected 200")))
       _ (swap! state assoc :groups {})
       ;; Pre-admit rotation triggers synchronously on next admit at t=1650 (>= 1000 + 600)
       a2 (r/admit-ram-replay! state ledger {} lane "job_00000002" nonce 1650)
       _ (when-not (= 409 (:status a2)) (throw (js/Error. "expected 409 after pre-admit 1st rotation")))
       _ (when-not (identical? k0 (:k-prev @ledger)) (throw (js/Error. "k0 must move to k-prev via pre-admit rotation")))
       k1 (:k-curr @ledger)
       ;; Rotation 2 at t=2300 (> 2 * T_rot): k0 (old k-prev) MUST be zeroized and s0 purged!
       _ (r/maybe-rotate-kagishi-replay! ledger 2300)
       _ (when-not (.every k0 #(= 0 %)) (throw (js/Error. "k0 was NOT zeroized on 2nd rotation")))
       _ (when-not (= 0 (.-size s0)) (throw (js/Error. "s0 was NOT cleared on 2nd rotation")))
       _ (when-not (identical? k1 (:k-prev @ledger)) (throw (js/Error. "k1 must move to k-prev")))
       ;; Purge-after-reuse: exact same R1 replayed at t=2300 MUST still be rejected by issued_at expiry!
       v-post-purge (r/validate-replay-headers ledger #js {"x-murakumo-replay-nonce" nonce
                                                           "x-murakumo-issued-at" (str r1-issued)
                                                           "x-murakumo-boot-id" boot-id}
                                               "job_00000003" 2300)
       _ (when-not (and (false? (:ok v-post-purge)) (= 403 (:status v-post-purge)) (= "replay_issued_at_expired" (:error v-post-purge)))
           (throw (js/Error. (str "expected post-purge 403 replay_issued_at_expired, got: " (pr-str v-post-purge)))))
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

 // Part B: Live HTTP rotation & Negative Test 2 (purge-after-reuse rejection across 2 * T_rot <= 900s)
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
 // W=500, delta=100, T_rot=700 (2*T_rot = 1400 <= 900000), periodic sweep every 200ms
 const cfg={
  node:'rot0',bind:'127.0.0.1',port:p,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'receipt-mode':'kagishi-ram','observations?':false,
  'replay-window-ms':500,'replay-clock-skew-ms':100,'replay-rotation-ms':700,
  'replay-sweep-interval-ms':200,
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

 // Wait idle past 2 * T_rot (~1650ms after startup) so periodic sweep purges bucket & zeroizes K_prev;
 // exact same R1 MUST STILL be rejected by issued_at expiry with calls === 1
 const wait3=Math.max(0,started+1650-Date.now());
 if(wait3>0)await sleep(wait3);
 const afterPurge=await fetch(url+'/execute/text',{method:'POST',headers:replayHeaders(token,bootId,{nonce:r1Nonce,issuedAt:r1Issued}),body:sampleBody});
 assert.equal(afterPurge.status,403,'exact same R1 replayed after 2*T_rot purge must still be rejected');
 assert.equal((await afterPurge.json()).error,'replay_issued_at_expired');
 assert.equal(backend.state.calls,1);
});

test('kagishi-ram: capacity 503 no-LRU, gateway verbatim forwarding with NO upstream re-issuance, and gateway skips /estimate',async t=>{
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
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };

 // Negative Test 6a: Gateway refuses startup if mint-replay-headers? is enabled (upstream re-issuance prohibited)
 const badGwPath=join(dir,'g-bad.json');
 await writeFile(badGwPath,JSON.stringify({
  node:'gw-bad',bind:'127.0.0.1',port:await port(),
  'token-file':tokenFile,'probe-interval-ms':50,
  'mint-replay-headers?':true,
  peers:[{node:'cap0',url:rUrl}]
 }));
 const badGw=spawn(process.execPath,[cli,'gateway','serve','--config',badGwPath],{cwd:dir,stdio:['ignore','pipe','pipe']});
 let badGwErr='';
 badGw.stdout.on('data',b=>badGwErr+=b);
 badGw.stderr.on('data',b=>badGwErr+=b);
 const [badGwCode]=await once(badGw,'close');
 assert.notEqual(badGwCode,0,'gateway must refuse startup when mint-replay-headers? is configured');
 assert.match(badGwErr,/mint-replay-headers/i);

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

 // Negative Test 6b: Calling gateway with missing or partial replay headers MUST NOT synthesize them (calls === 0)
 const gwMissingAll=await fetch(gUrl+'/v1/chat/completions',{
  method:'POST',
  headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},
  body:sampleBody
 });
 assert.equal(gwMissingAll.status,400,'gateway must not synthesize missing replay headers');
 assert.equal((await gwMissingAll.json()).error,'invalid_replay_nonce');
 assert.equal(backend.state.calls,0);

 const gwMissingBoot=await fetch(gUrl+'/v1/chat/completions',{
  method:'POST',
  headers:{
   authorization:`Bearer ${token}`,'content-type':'application/json',
   'x-murakumo-replay-nonce':randomBytes(16).toString('hex'),
   'x-murakumo-issued-at':String(Date.now())
  },
  body:sampleBody
 });
 assert.equal(gwMissingBoot.status,400,'gateway must not synthesize missing boot_id');
 assert.equal((await gwMissingBoot.json()).error,'boot_id_required');
 assert.equal(backend.state.calls,0);

 // 1st entry through gateway (preserving caller replay headers verbatim, skipping /estimate)
 const n1=randomBytes(16).toString('hex');
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

 // Negative Test 6c: Replaying the exact same used replay headers through gateway MUST NOT re-issue new headers -> 409, calls === 1
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.cap0?.lanes?.text?.busy===false);
 const dupGw=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:gwHeaders1,body:sampleBody});
 assert.equal(dupGw.status,409,'gateway must not re-issue replay headers on replay');
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
