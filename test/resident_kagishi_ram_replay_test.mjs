import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {connect as netConnect} from 'node:net';
import {spawn,execFile,execFileSync} from 'node:child_process';
import {promisify} from 'node:util';
const execFileAsync=promisify(execFile);
import {once} from 'node:events';
import {mkdtemp,mkdir,writeFile,readFile,rm,readdir,stat,symlink} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes,randomUUID,createHash,generateKeyPairSync,sign as cryptoSign} from 'node:crypto';

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

function makeEd25519Keypair(){
 return generateKeyPairSync('ed25519',{
  publicKeyEncoding:{type:'spki',format:'pem'},
  privateKeyEncoding:{type:'pkcs8',format:'pem'}
 });
}

function makeDualAdmitKeys(activeKp,stagedKp=makeEd25519Keypair()){
 return {
  'k-active':{'public-key':activeKp.publicKey,state:'active'},
  'k-staged':{'public-key':stagedKp.publicKey,state:'staged'}
 };
}

function admitSigMessage(envelopeRaw,body){
 const prefix=Buffer.concat([Buffer.from('awai/v1/murakumo-admit-envelope','utf8'),Buffer.from([0])]);
 const envBuf=Buffer.isBuffer(envelopeRaw)?envelopeRaw:Buffer.from(envelopeRaw,'utf8');
 const bodyBuf=Buffer.isBuffer(body)?body:Buffer.from(body??'','utf8');
 return Buffer.concat([prefix,u32be(envBuf.length),envBuf,u32be(bodyBuf.length),bodyBuf]);
}

function signWireV1Ed25519(kid,privateKeyPem,envelopeRaw,body,{bareSig=false}={}){
 const msg=admitSigMessage(envelopeRaw,body);
 const sig=cryptoSign(null,msg,privateKeyPem).toString('base64url');
 return bareSig?sig:`${kid}.${sig}`;
}

function wireV1Headers(authToken,{
 owner,lane='text',route='POST /v1/chat/completions',boot,body,
 kid='k-active',alg='ed25519',privateKey,nonce,iat,v=1,
 sig,bareSig=false,extraFields,extra,includeLegacy=false
}={}){
 const envObj={
  v,alg,kid,route,owner,lane,
  iat:iat===undefined?Date.now():iat,
  nonce:nonce??randomBytes(16).toString('hex')
 };
 if(boot!==undefined&&boot!==null)envObj.boot=boot;
 if(extraFields)Object.assign(envObj,extraFields);
 const envRaw=Buffer.from(JSON.stringify(envObj),'utf8');
 const envB64u=envRaw.toString('base64url');
 const computedSig=sig!==undefined
  ?sig
  :signWireV1Ed25519(kid,privateKey,envRaw,body??'',{bareSig});
 const h={
  authorization:`Bearer ${authToken}`,
  'content-type':'application/json',
  'x-murakumo-admit-envelope':envB64u
 };
 if(computedSig!==null)h['x-murakumo-admit-sig']=computedSig;
 if(includeLegacy){
  if(envObj.boot!==undefined)h['x-murakumo-boot-id']=envObj.boot;
  h['x-murakumo-issued-at']=String(envObj.iat);
  h['x-murakumo-replay-nonce']=envObj.nonce;
 }
 if(extra)Object.assign(h,extra);
 return {headers:h,envelope:envObj,envelopeRaw:envRaw,envelopeB64u:envB64u,sig:computedSig};
}

function rawHttpRequest(portNum,method,path,rawHeaderLines,body=''){
 return new Promise((resolveReq,rejectReq)=>{
  const sock=netConnect(portNum,'127.0.0.1',()=>{
   const buf=Buffer.from(body,'utf8');
   const req=[
    `${method} ${path} HTTP/1.1`,
    `Host: 127.0.0.1:${portNum}`,
    `Content-Length: ${buf.length}`,
    'Connection: close',
    ...rawHeaderLines,
    '',
    ''
   ].join('\r\n');
   sock.write(req);
   if(buf.length>0)sock.write(buf);
  });
  let resp='';
  sock.on('data',d=>resp+=d.toString('utf8'));
  sock.on('error',rejectReq);
  sock.on('end',()=>{
   const [head,...rest]=resp.split('\r\n\r\n');
   const status=Number(head.split('\r\n')[0].split(' ')[1]);
   resolveReq({status,body:rest.join('\r\n\r\n')});
  });
 });
}

function makeBackend({delayMs=0,disconnectOn=null,streamDelayMs=350,emitTraceHeaders=false}={}){
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
     res.write('data: {"id":"backend-upstream-stream-id-999","model":"mishima","choices":[{"delta":{"content":"OK"}}]}\n\ndata: [DONE]\n\n');
     res.end();
    },streamDelayMs);
    return;
   }
   const send=()=>{
    res.writeHead(200,{'content-type':'application/json',...extraHeaders});
    res.end(JSON.stringify({id:'backend-upstream-json-id-888',choices:[{message:{content:'OK'}}],usage:{prompt_tokens:5,completion_tokens:2}}));
   };
   if(delayMs>0)setTimeout(send,delayMs);else send();
  });
 });
 return {state,server};
}

const sampleBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'hello kagishi privacy'}]});

test('TEST-INFER-PRIV-01: fail-closed startup scan (receipts/tmp/bak/lock/symlink/poi/compat/missing admit-public-keys) without deletion, allowed sidecars preserved, and 0 disk writes across success/failure/overload',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv01-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const stateDir=join(dir,'state');
 await mkdir(stateDir,{recursive:true});
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const kp=makeEd25519Keypair();
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

 const stateFile=join(stateDir,'receipts.json');
 const baseConfig={
  node:'priv01',bind:'127.0.0.1',port:await port(),
  'token-file':tokenFile,'state-file':stateFile,
  'replay-ledger-mode':'kagishi-ram','observations?':false,
  'admit-public-keys':makeDualAdmitKeys(kp),
  'job-timeout-ms':2000,'replay-window-ms':5000,'replay-clock-skew-ms':500,'replay-rotation-ms':6000,
  'replay-max-entries':2,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };

 // 1a. Legacy receipts.json refuses startup across kagishi-ram, ephemeral-memory, and ram-hmac WITHOUT deleting/modifying
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

 // 1b. Residual receipts*.json*, *.tmp, *.bak, *.lock, *.poi-receipts.jsonl* refuse startup without deleting
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

 // 1c. Symlink (even dangling!) on :state-file or allowed sidecar fails closed via lstatSync
 {
  await symlink(join(dir,'nonexistent-target'),stateFile);
  assert.notEqual((await once((await launch({...baseConfig,port:await port()},'symlink-state')).p,'close'))[0],0);
  await rm(stateFile);

  const realOwner=join(dir,'real-owner');
  await writeFile(realOwner,randomUUID(),{mode:0o600});
  await symlink(realOwner,stateFile+'.owner-id');
  assert.notEqual((await once((await launch({...baseConfig,port:await port()},'symlink-sidecar')).p,'close'))[0],0);
  await rm(stateFile+'.owner-id');
 }

 // 1d. :poi-receipts? true, :compat-port, conflicting modes, missing admit-public-keys, and symmetric admit-keys all fail closed
 assert.notEqual((await once((await launch({...baseConfig,port:await port(),'poi-receipts?':true},'poi-true')).p,'close'))[0],0);
 assert.notEqual((await once((await launch({...baseConfig,port:await port(),'compat-port':await port(),'compat-bind':'127.0.0.1'},'compat-refuse')).p,'close'))[0],0);
 assert.notEqual((await once((await launch({...baseConfig,port:await port(),'replay-ledger-mode':'kagishi-ram','receipt-mode':'durable'},'conflict-mode')).p,'close'))[0],0);
 {
  const noKeys={...baseConfig,port:await port()};
  delete noKeys['admit-public-keys'];
  assert.notEqual((await once((await launch(noKeys,'no-admit-keys')).p,'close'))[0],0,'missing admit-public-keys must fail closed');
  const symKeys={...baseConfig,port:await port(),'admit-public-keys':{'k1':{type:'hmac-sha256','secret-file':tokenFile}}};
  assert.notEqual((await once((await launch(symKeys,'sym-admit-keys')).p,'close'))[0],0,'symmetric admit key must fail closed in v1.0.3');
  const allRevoked={...baseConfig,port:await port(),'admit-public-keys':{'k1':{'public-key':kp.publicKey,state:'revoked'}}};
  assert.notEqual((await once((await launch(allRevoked,'revoked-only')).p,'close'))[0],0,'all-revoked admit-public-keys must fail closed');
  // Strict mode requires BOTH active AND staged pinned public keys at startup
  const activeOnly={...baseConfig,port:await port(),'admit-public-keys':{'k-active':{'public-key':kp.publicKey,state:'active'}}};
  assert.notEqual((await once((await launch(activeOnly,'active-only')).p,'close'))[0],0,'active-only admit-public-keys (missing staged) must fail closed at startup');
  const stagedOnly={...baseConfig,port:await port(),'admit-public-keys':{'k-staged':{'public-key':kp.publicKey,state:'staged'}}};
  assert.notEqual((await once((await launch(stagedOnly,'staged-only')).p,'close'))[0],0,'staged-only admit-public-keys (missing active) must fail closed at startup');
  const revokedStaged={...baseConfig,port:await port(),'revoked-admit-kids':['k-staged']};
  assert.notEqual((await once((await launch(revokedStaged,'revoked-staged')).p,'close'))[0],0,'revoking only staged key must fail closed at startup');
  const revokedActive={...baseConfig,port:await port(),'revoked-admit-kids':['k-active']};
  assert.notEqual((await once((await launch(revokedActive,'revoked-active')).p,'close'))[0],0,'revoking only active key must fail closed at startup');
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

 assert.equal((await fetch(u1+'/poi/receipts')).status,401);
 const poiAuth=await fetch(u1+'/poi/receipts',{headers:auth});
 assert.equal(poiAuth.status,404);
 assert.equal((await poiAuth.json()).error,'poi_receipts_disabled');

 const okRes=await fetch(u1+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'priv01',lane:'text',boot:h1.boot_id,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(okRes.status,200);
 await okRes.json();

 const failBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'BREAK_ME'}]});
 const failRes=await fetch(u1+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'priv01',lane:'text',boot:h1.boot_id,body:failBody,privateKey:kp.privateKey}).headers,body:failBody});
 assert.equal(failRes.status,502);
 await failRes.json();

 r1.p.kill('SIGTERM');
 await once(r1.p,'exit');

 // 1f. Allowed sidecars preserved; mismatched .bound-owner fails closed; overload 503 writes 0 files
 const ownerId=randomUUID();
 await writeFile(stateFile+'.owner-id',ownerId,{mode:0o600});
 await writeFile(stateFile+'.bound-owner',randomUUID(),{mode:0o600});
 assert.notEqual((await once((await launch({...baseConfig,port:await port()},'owner-mismatch')).p,'close'))[0],0);

 await writeFile(stateFile+'.bound-owner',ownerId,{mode:0o600});
 await writeFile(stateFile+'.node-key','dummy-node-key',{mode:0o600});
 await writeFile(stateFile+'.observation-pins.json','{}',{mode:0o600});
 const p2=await port();
 await launch({...baseConfig,port:p2,'replay-max-entries':1},'with-allowed-sidecars');
 const u2=`http://127.0.0.1:${p2}`;
 await wait(async()=>(await(await fetch(u2+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const h2=await(await fetch(u2+'/health',{headers:auth})).json();
 assert.equal(h2.replay_mode,'kagishi-ram');
 assert.equal(h2['owner-id'],ownerId);

 const ok2=await fetch(u2+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'priv01',lane:'text',boot:h2.boot_id,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(ok2.status,200);await ok2.json();
 const ov=await fetch(u2+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'priv01',lane:'text',boot:h2.boot_id,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(ov.status,503);
 assert.deepEqual(await ov.json(),{error:'replay_capacity',executed:false});

 assert.deepEqual((await readdir(stateDir)).sort(),[
  'receipts.json.bound-owner',
  'receipts.json.node-key',
  'receipts.json.observation-pins.json',
  'receipts.json.owner-id'
 ]);
});

test('TEST-INFER-PRIV-02: v1.0.1 unified defaults (T_tick=10000<=30000), monotonic rotation + >=2*T_rot double-wipe, wall-clock rollback negative test, Option A 409, 503 replay_capacity no-LRU, and single-owner non-failover',async t=>{
 // Part A: Direct v1.0.1 + v1.0.3 unit checks (defaults, monotonic non-early rotation, >=2*T_rot double-wipe, wall-clock rollback immunity with Ed25519 PureEdDSA, stuck inflight sweep)
 const verifyClj=`
 (require '[murakumo.infer.resident :as r] '["node:crypto" :as crypto])
 (let [def-ledger (r/make-replay-ledger {:node "n0"} "00112233445566778899aabbccddeeff" 1000000 5000)
       d @def-ledger
       _ (when-not (= 180000 (:job-timeout-ms d)) (throw (js/Error. (str "default job-timeout-ms != 180000: " (:job-timeout-ms d)))))
       _ (when-not (= 300000 (:window-ms d)) (throw (js/Error. (str "default W != 300000: " (:window-ms d)))))
       _ (when-not (= 30000 (:clock-skew-ms d)) (throw (js/Error. (str "default delta != 30000: " (:clock-skew-ms d)))))
       _ (when-not (= 360000 (:rotation-ms d)) (throw (js/Error. (str "default T_rot != 360000: " (:rotation-ms d)))))
       _ (when-not (= 10000 (:sweep-interval-ms d)) (throw (js/Error. (str "default T_tick != 10000: " (:sweep-interval-ms d)))))
       _ (when-not (= 10000 (:max-entries d)) (throw (js/Error. (str "default max-entries != 10000: " (:max-entries d)))))
       _ (when-not (<= (+ (* 2 (:rotation-ms d)) (:sweep-interval-ms d)) 900000) (throw (js/Error. "2*T_rot+T_tick > 900000")))
       _ (r/destroy-replay-ledger! def-ledger)
       ;; Exact HMAC-SHA256 replay tag vector
       key (.from js/Buffer "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" "hex")
       boot-id "00112233445566778899aabbccddeeff"
       nonce "fedcba9876543210fedcba9876543210"
       tag (r/replay-tag key boot-id nonce)
       b-boot (js/Buffer.from boot-id "utf8") b-nonce (js/Buffer.from nonce "utf8")
       len1 (.alloc js/Buffer 4) len2 (.alloc js/Buffer 4)
       _ (.writeUInt32BE len1 (.-length b-boot) 0) _ (.writeUInt32BE len2 (.-length b-nonce) 0)
       prefix (.concat js/Buffer #js [(js/Buffer.from "awai/v1/murakumo-replay" "utf8") (js/Buffer.from #js [0])])
       expected (.digest (.update (.createHmac crypto "sha256" key) (.concat js/Buffer #js [prefix len1 b-boot len2 b-nonce])) "hex")
       _ (when-not (= tag expected) (throw (js/Error. "hmac mismatch")))
       ;; Monotonic rotation + >=2*T_rot double wipe + wall-clock rollback immunity with PureEdDSA Ed25519
       kp (.generateKeyPairSync crypto "ed25519")
       pub-key (.-publicKey kp)
       priv-key (.-privateKey kp)
       admit-keys {"k1" {:type :ed25519 :key pub-key :state "active"}}
       start-wall 1000000000000
       start-mono 50000
       ledger (r/make-replay-ledger {:node "n0" :job-timeout-ms 300 :replay-window-ms 400 :replay-clock-skew-ms 100 :replay-rotation-ms 600 :replay-sweep-interval-ms 100 :replay-max-entries 1}
                                    boot-id start-wall start-mono)
       k0 (:k-curr @ledger) s0 (:curr-set @ledger)
       state (atom {:groups {}})
       lane {:group "gpu" :ready true}
       body-buf (js/Buffer.from (js/JSON.stringify #js {:model "mishima"}) "utf8")
       make-hdrs (fn [iat-ms n-str]
                   (let [env-raw (js/Buffer.from (js/JSON.stringify #js {:v 1 :alg "ed25519" :kid "k1" :route "POST /v1/chat/completions" :owner "n0" :lane "text" :boot boot-id :iat iat-ms :nonce n-str}) "utf8")
                         sig (.toString (.sign crypto nil (r/admit-sig-message env-raw body-buf) priv-key) "base64url")]
                     #js {"x-murakumo-admit-envelope" (.toString env-raw "base64url")
                          "x-murakumo-admit-sig" (str "k1." sig)}))
       ;; Admit R1 at mono=50100, wall=start-wall+100
       h-r1 (make-hdrs (+ start-wall 100) nonce)
       v1 (r/validate-admit-request ledger h-r1 "text" "POST /v1/chat/completions" nil (+ start-wall 100) (+ start-mono 100) admit-keys body-buf 2097152)
       _ (when-not (:ok v1) (throw (js/Error. (str "R1 validate failed: " (pr-str v1)))))
       a1 (r/admit-ram-replay! state ledger {} lane "priv-1" nonce (+ start-mono 100))
       _ (when-not (= 200 (:status a1)) (throw (js/Error. "R1 admit failed")))
       _ (swap! state assoc :groups {}) _ (swap! ledger update :inflight dissoc "priv-1")
       ;; Monotonic non-early check: mono elapsed = 599 (< 600 T_rot) MUST NOT rotate
       _ (r/maybe-rotate-kagishi-replay! ledger (+ start-mono 599))
       _ (when-not (identical? k0 (:k-curr @ledger)) (throw (js/Error. "rotated early before monotonic T_rot!")))
       ;; Normal 1-step rotation at mono = start-mono + 650
       _ (r/maybe-rotate-kagishi-replay! ledger (+ start-mono 650))
       _ (when-not (identical? k0 (:k-prev @ledger)) (throw (js/Error. "k0 must move to k-prev on 1-step rotation")))
       k1 (:k-curr @ledger) s1 (:curr-set @ledger)
       ;; >= 2*T_rot double wipe directly from mono = start-mono + 650 + 1300
       _ (r/maybe-rotate-kagishi-replay! ledger (+ start-mono 650 1300))
       _ (when-not (and (.every k0 #(= 0 %)) (.every k1 #(= 0 %)))
           (throw (js/Error. ">=2*T_rot double-wipe did NOT zeroize BOTH k-prev and k-curr")))
       _ (when-not (and (= 0 (.-size s0)) (= 0 (.-size s1)) (nil? (:k-prev @ledger)) (= 0 (.-size (:curr-set @ledger))) (= 0 (.-size (:prev-set @ledger))))
           (throw (js/Error. ">=2*T_rot double-wipe did NOT clear BOTH sets")))
       ;; SYNTHETIC NEGATIVE TEST: Wall-clock rollback!
       ;; Step wall_now BACKWARD to start-wall + 150 after purge -> MUST STILL reject R1 403 replay_issued_at_expired!
       v-rollback (r/validate-admit-request ledger h-r1 "text" "POST /v1/chat/completions" nil (+ start-wall 150) (+ start-mono 1950) admit-keys body-buf 2097152)
       _ (when-not (and (false? (:ok v-rollback)) (= 403 (:status v-rollback)) (= "replay_issued_at_expired" (:error v-rollback)))
           (throw (js/Error. (str "wall-clock rollback allowed expired R1! got: " (pr-str v-rollback)))))
       ;; Recovered capacity after purge MUST accept a brand-new valid envelope R2 (iat = effective-now)
       n2 "11112222333344445555666677778888"
       h-r2 (make-hdrs (+ start-wall 1950) n2)
       v2 (r/validate-admit-request ledger h-r2 "text" "POST /v1/chat/completions" nil (+ start-wall 150) (+ start-mono 1950) admit-keys body-buf 2097152)
       _ (when-not (:ok v2) (throw (js/Error. (str "fresh R2 failed after purge: " (pr-str v2)))))
       a2 (r/admit-ram-replay! state ledger {} lane "priv-2" n2 (+ start-mono 1950))
       _ (when-not (= 200 (:status a2)) (throw (js/Error. "fresh R2 admit failed after purge")))
       ;; Stuck inflight sweep using monotonic clock
       _ (swap! ledger assoc-in [:inflight "stuck-id"] {:admitted-mono-ms (+ start-mono 100) :group "gpu"})
       _ (swap! state assoc-in [:groups "gpu"] "stuck-id")
       _ (r/maybe-rotate-kagishi-replay! ledger (+ start-mono 1950) state nil)
       _ (when (or (get-in @ledger [:inflight "stuck-id"]) (get-in @state [:groups "gpu"]))
           (throw (js/Error. "stuck inflight was not swept")))
       k-final (:k-curr @ledger)
       _ (r/destroy-replay-ledger! ledger)
       _ (when-not (.every k-final #(= 0 %)) (throw (js/Error. "destroy did not zeroize k-curr")))]
   (println "PRIV02_V103_OK"))
 `;
 const cljDir=await mkdtemp(join(tmpdir(),'murakumo-priv02-clj-'));
 t.after(()=>rm(cljDir,{recursive:true,force:true}));
 await writeFile(join(cljDir,'nbb.edn'),'{:paths []}\n');
 const cljOut=execFileSync('kbb',['--backend','sci','--classpath',join(root,'src'),'-e',verifyClj],{cwd:cljDir,encoding:'utf8'});
 assert.match(cljOut,/PRIV02_V103_OK/);

 // Part B: Startup invariant refusals + live 2*T_rot purge + Option A 409 + 503 replay_capacity no-LRU + Wire v1 single-owner non-failover
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv02-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const kp=makeEd25519Keypair();
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
 const invBase={node:'inv0',bind:'127.0.0.1','token-file':tokenFile,'state-file':join(dir,'r.json'),'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),'observations?':false,'probe-interval-ms':30000,lanes:{text:lane}};

 assert.notEqual((await launchFail({...invBase,port:await port(),'replay-window-ms':60000,'replay-clock-skew-ms':1000,'replay-rotation-ms':70000},'w-lt-default-jt')).code,0);
 assert.notEqual((await launchFail({...invBase,port:await port(),'replay-window-ms':10000,'replay-clock-skew-ms':1000,'replay-rotation-ms':12000,lanes:{text:{...lane,'job-timeout-ms':15000}}},'w-lt-lane-jt')).code,0);
 assert.notEqual((await launchFail({...invBase,port:await port(),'job-timeout-ms':2000,'replay-window-ms':5000,'replay-clock-skew-ms':1000,'replay-rotation-ms':6500},'trot-lt-w2d')).code,0);
 const badTick=await launchFail({...invBase,port:await port(),'replay-sweep-interval-ms':30001},'tick-gt-30s');
 assert.notEqual(badTick.code,0);assert.match(badTick.err,/30000/);
 const badSum=await launchFail({...invBase,port:await port(),'job-timeout-ms':180000,'replay-window-ms':300000,'replay-clock-skew-ms':30000,'replay-rotation-ms':440000,'replay-sweep-interval-ms':20001},'sum-gt-900s');
 assert.notEqual(badSum.code,0);assert.match(badSum.err,/900000/);

 // Live rotation across 2*T_rot + Option A 409 + 503 replay_capacity no-LRU
 const rp=await port();
 const rotPath=join(dir,'rot.json');
 await writeFile(rotPath,JSON.stringify({
  ...invBase,port:rp,'state-file':join(dir,'rot-receipts.json'),
  'job-timeout-ms':400,'replay-window-ms':500,'replay-clock-skew-ms':100,'replay-rotation-ms':700,
  'replay-sweep-interval-ms':150,'replay-max-entries':2
 }));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rotPath],{cwd:dir,stdio:'ignore'});
 procs.push(rProc);
 const rUrl=`http://127.0.0.1:${rp}`;
 await wait(async()=>(await(await fetch(rUrl+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const readyAt=Date.now();
 const {boot_id:bootId}=await(await fetch(rUrl+'/health',{headers:auth})).json();

 const n1=randomBytes(16).toString('hex');
 const iat1=Date.now();
 const w1=wireV1Headers(token,{owner:'inv0',lane:'text',boot:bootId,body:sampleBody,nonce:n1,iat:iat1,privateKey:kp.privateKey});
 const first=await fetch(rUrl+'/execute/text',{method:'POST',headers:w1.headers,body:sampleBody});
 assert.equal(first.status,200);await first.json();
 assert.equal(backend.state.calls,1);

 // Option A strict single-use: same replay_nonce with DIFFERENT validly-signed body -> 409 already-seen (NEVER 422)
 const diffBody=sampleBody.replace('hello kagishi privacy','completely different prompt');
 const dupDiff=await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'inv0',lane:'text',boot:bootId,body:diffBody,nonce:n1,iat:iat1,privateKey:kp.privateKey}).headers,body:diffBody});
 assert.equal(dupDiff.status,409);
 assert.deepEqual(await dupDiff.json(),{error:'already-seen',executed:'unknown',retryable:false});
 assert.equal(backend.state.calls,1);

 // Wait past 2*T_rot so n1 is purged -> exact same R1 STILL rejected 403 replay_issued_at_expired
 const waitPurge=Math.max(0,readyAt+1750-Date.now());
 if(waitPurge>0)await sleep(waitPurge);
 const afterPurge=await fetch(rUrl+'/execute/text',{method:'POST',headers:w1.headers,body:sampleBody});
 assert.equal(afterPurge.status,403);
 assert.deepEqual(await afterPurge.json(),{error:'replay_issued_at_expired',executed:false});
 assert.equal(backend.state.calls,1);

 // Capacity overflow (max-entries=2) + no LRU eviction -> 503 replay_capacity
 const capN1=randomBytes(16).toString('hex'),capN2=randomBytes(16).toString('hex'),capN3=randomBytes(16).toString('hex');
 const c1=await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'inv0',lane:'text',boot:bootId,body:sampleBody,nonce:capN1,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(c1.status,200);await c1.json();
 const c2=await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'inv0',lane:'text',boot:bootId,body:sampleBody,nonce:capN2,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(c2.status,200);await c2.json();
 const full=await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'inv0',lane:'text',boot:bootId,body:sampleBody,nonce:capN3,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(full.status,503);
 assert.deepEqual(await full.json(),{error:'replay_capacity',executed:false});
 assert.equal((await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'inv0',lane:'text',boot:bootId,body:sampleBody,nonce:capN1,privateKey:kp.privateKey}).headers,body:sampleBody})).status,409);
 assert.equal((await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'inv0',lane:'text',boot:bootId,body:sampleBody,nonce:capN2,privateKey:kp.privateKey}).headers,body:sampleBody})).status,409);
 assert.equal(backend.state.calls,3);

 // Wire v1 single (owner, lane) routing: owner1 at replay_capacity MUST NOT fail over to owner2
 const b2=makeBackend();b2.server.listen(0,'127.0.0.1');await once(b2.server,'listening');
 t.after(()=>{b2.server.closeAllConnections();b2.server.close();});
 const fp1=await port(),fp2=await port(),fgp=await port();
 const fUrl1=`http://127.0.0.1:${fp1}`,fUrl2=`http://127.0.0.1:${fp2}`,fgUrl=`http://127.0.0.1:${fgp}`;
 for(const [idx,fp,n,bPort] of [[1,fp1,'owner1',backend.server.address().port],[2,fp2,'owner2',b2.server.address().port]]){
  const pDir=join(dir,`cf-${idx}`);await mkdir(pDir,{recursive:true});
  const pf=join(dir,`cf-${idx}.json`);
  await writeFile(pf,JSON.stringify({
   node:n,bind:'127.0.0.1',port:fp,'token-file':tokenFile,'state-file':join(pDir,'receipts.json'),
   'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),'observations?':false,
   'replay-max-entries':1,'probe-interval-ms':30000,
   lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',backend:`http://127.0.0.1:${bPort}`,path:'/v1/chat/completions'}}
  }));
  procs.push(spawn(process.execPath,[cli,'resident','serve','--config',pf],{cwd:dir,stdio:'ignore'}));
 }
 const fgf=join(dir,'cf-gw.json');
 await writeFile(fgf,JSON.stringify({node:'cf-gw',bind:'127.0.0.1',port:fgp,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':40,peers:[{node:'owner1',url:fUrl1},{node:'owner2',url:fUrl2}]}));
 procs.push(spawn(process.execPath,[cli,'gateway','serve','--config',fgf],{cwd:dir,stdio:'ignore'}));
 await wait(async()=>{const h=await(await fetch(fgUrl+'/health',{headers:auth})).json();return h.peers?.owner1?.lanes?.text?.ready&&h.peers?.owner2?.lanes?.text?.ready;});
 const fgHealth=await(await fetch(fgUrl+'/health',{headers:auth})).json();
 const o1Boot=fgHealth.peers.owner1.boot_id,o2Boot=fgHealth.peers.owner2.boot_id;

 const rO1=await fetch(fgUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'owner1',lane:'text',boot:o1Boot,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(rO1.status,200);await rO1.json();
 await wait(async()=>(await(await fetch(fgUrl+'/health',{headers:auth})).json()).peers?.owner1?.lanes?.text?.busy===false);
 const rO1Full=await fetch(fgUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'owner1',lane:'text',boot:o1Boot,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(rO1Full.status,503);
 assert.deepEqual(await rO1Full.json(),{error:'replay_capacity',executed:false});
 assert.equal(b2.state.calls,0,'owner1-bound envelope must NEVER fail over to owner2');
 const rO2=await fetch(fgUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'owner2',lane:'text',boot:o2Boot,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(rO2.status,200);await rO2.json();
 assert.equal(b2.state.calls,1);
});

test('TEST-INFER-PRIV-03: 0 unsalted SHA-256(body) anywhere; POST /estimate/:lane uses x-murakumo-estimate-nonce echo and rejects missing/duplicate/mismatched nonce',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv03-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const kp=makeEd25519Keypair();
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
  'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),
  'observations?':false,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={node:'gw-est0',bind:'127.0.0.1',port:gp,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':50,peers:[{node:'est0',url:rUrl}]};
 const gPinPort=await port();
 const gPinUrl=`http://127.0.0.1:${gPinPort}`;
 const gPinCfg={node:'gw-est0-pin',bind:'127.0.0.1',port:gPinPort,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),'probe-interval-ms':50,peers:[{node:'est0',url:rUrl}]};
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json'),gPinPath=join(dir,'g-pin.json');
 await writeFile(rPath,JSON.stringify(rCfg));await writeFile(gPath,JSON.stringify(gCfg));await writeFile(gPinPath,JSON.stringify(gPinCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 const gPinProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPinPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');gPinProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.est0?.lanes?.text?.ready);
 await wait(async()=>(await(await fetch(gPinUrl+'/health',{headers:auth})).json()).peers?.est0?.lanes?.text?.ready);
 const {boot_id:bootId}=await(await fetch(rUrl+'/health',{headers:auth})).json();

 const largeBody=JSON.stringify({model:'mishima',max_tokens:32,messages:[{role:'user',content:'Private prompt text. '.repeat(2000)}]});
 assert.ok(Buffer.byteLength(largeBody)>32768);
 const unsaltedSha=createHash('sha256').update(largeBody).digest('hex');

 const tokBefore=backend.state.tokenizes;
 const estNoNonce=await fetch(rUrl+'/estimate/text',{method:'POST',headers:auth,body:largeBody});
 assert.equal(estNoNonce.status,400);
 assert.ok(!(await estNoNonce.text()).includes(unsaltedSha));
 assert.equal(backend.state.tokenizes,tokBefore);

 // Duplicate x-murakumo-estimate-nonce header rejected 400
 const dupEst=await rawHttpRequest(rp,'POST','/estimate/text',[
  `Authorization: Bearer ${token}`,
  'Content-Type: application/json',
  `x-murakumo-estimate-nonce: ${randomBytes(16).toString('hex')}`,
  `x-murakumo-estimate-nonce: ${randomBytes(16).toString('hex')}`
 ],largeBody);
 assert.equal(dupEst.status,400);
 assert.equal(backend.state.tokenizes,tokBefore);

 // Negative tests (Fix 1): unsigned / mismatched x-murakumo-admit-sig / invalid envelope MUST result in 0 tokenizer calls AND 0 model calls!
 const estNonce=randomBytes(16).toString('hex');
 const estUnsigned=await fetch(rUrl+'/estimate/text',{method:'POST',headers:{...auth,'x-murakumo-estimate-nonce':estNonce},body:largeBody});
 assert.equal(estUnsigned.status,401);
 assert.equal((await estUnsigned.json()).error,'admit_envelope_required');
 assert.equal(backend.state.tokenizes,tokBefore,'unsigned /estimate must produce 0 tokenizer calls');
 assert.equal(backend.state.calls,0);

 const validEstWire=wireV1Headers(token,{owner:'est0',lane:'text',boot:bootId,body:largeBody,privateKey:kp.privateKey});
 const tamperedLargeBody=largeBody.replace('Private prompt text.','TAMPERED prompt text!');
 // 1) Direct resident /estimate with body mismatching x-murakumo-admit-sig -> 403, 0 tokenizer calls
 const estBadSig=await fetch(rUrl+'/estimate/text',{method:'POST',headers:{...validEstWire.headers,'x-murakumo-estimate-nonce':estNonce},body:tamperedLargeBody});
 assert.equal(estBadSig.status,403);
 assert.equal((await estBadSig.json()).error,'invalid_admit_sig');
 assert.equal(backend.state.tokenizes,tokBefore,'mismatched sig on /estimate must produce 0 tokenizer calls');
 assert.equal(backend.state.calls,0);

 // 2) Direct resident /execute with body mismatching x-murakumo-admit-sig -> 403, 0 tokenizer calls, 0 model calls
 const execBadSig=await fetch(rUrl+'/execute/text',{method:'POST',headers:validEstWire.headers,body:tamperedLargeBody});
 assert.equal(execBadSig.status,403);
 assert.equal((await execBadSig.json()).error,'invalid_admit_sig');
 assert.equal(backend.state.tokenizes,tokBefore,'mismatched sig on /execute must produce 0 tokenizer calls');
 assert.equal(backend.state.calls,0);

 // 3) Gateway (both unpinned-keys and gateway-pinned-keys) with body mismatching x-murakumo-admit-sig -> 403, 0 tokenizer calls, 0 model calls
 for(const [label,targetGw] of [['gateway',gUrl],['pinned-gateway',gPinUrl]]){
  const gwBadSig=await fetch(targetGw+'/v1/chat/completions',{method:'POST',headers:validEstWire.headers,body:tamperedLargeBody});
  assert.equal(gwBadSig.status,403,`${label} mismatched sig must return 403`);
  assert.equal((await gwBadSig.json()).error,'invalid_admit_sig');
  assert.equal(backend.state.tokenizes,tokBefore,`${label} mismatched sig must produce 0 tokenizer calls`);
  assert.equal(backend.state.calls,0,`${label} mismatched sig must produce 0 model calls`);

  const gwBadEnv=await fetch(targetGw+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'est0',lane:'text',boot:bootId,body:largeBody,privateKey:kp.privateKey,extraFields:{bad_extra:1}}).headers,body:largeBody});
  assert.equal(gwBadEnv.status,400,`${label} invalid envelope must return 400`);
  assert.equal(backend.state.tokenizes,tokBefore,`${label} invalid envelope must produce 0 tokenizer calls`);
  assert.equal(backend.state.calls,0,`${label} invalid envelope must produce 0 model calls`);
 }

 // Positive: valid Wire v1 envelope + body signature + x-murakumo-estimate-nonce succeeds and echoes estimate_nonce
 const estOk=await fetch(rUrl+'/estimate/text',{method:'POST',headers:{...validEstWire.headers,'x-murakumo-estimate-nonce':estNonce},body:largeBody});
 assert.equal(estOk.status,200);
 const estJson=await estOk.json();
 assert.equal(estJson.estimate_nonce,estNonce);
 assert.equal(estJson.source,'backend-tokenizer');
 assert.equal(estJson['input-tokens'],132);
 assert.equal('digest' in estJson,false);
 assert.ok(!JSON.stringify(estJson).includes(unsaltedSha));
 assert.ok(backend.state.tokenizes>tokBefore,'valid signed /estimate invokes tokenizer');

 const gwOk=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'est0',lane:'text',boot:bootId,body:largeBody,privateKey:kp.privateKey}).headers,body:largeBody});
 assert.equal(gwOk.status,200);
 assert.ok(!(await gwOk.text()).includes(unsaltedSha));
 assert.equal(backend.state.calls,1);

 // Rogue resident returning mismatched estimate_nonce fails closed
 const rogueBoot=randomBytes(16).toString('hex');
 let rogueExecCalls=0;
 const rogueResident=createServer((req,res)=>{
  if(req.url==='/health')return res.end(JSON.stringify({node:'rogue0',boot_id:rogueBoot,replay_mode:'kagishi-ram',lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',ready:true,busy:false}}}));
  if(req.url==='/estimate/text'){req.resume();return req.on('end',()=>res.end(JSON.stringify({model:'mishima','input-tokens':10,source:'backend-tokenizer',estimate_nonce:'0'.repeat(32)})));}
  rogueExecCalls++;req.resume();req.on('end',()=>res.end('{}'));
 });
 rogueResident.listen(0,'127.0.0.1');await once(rogueResident,'listening');
 t.after(()=>{rogueResident.closeAllConnections();rogueResident.close();});

 const gp2=await port(),g2Path=join(dir,'g2.json');
 await writeFile(g2Path,JSON.stringify({node:'gw-rogue',bind:'127.0.0.1',port:gp2,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':40,peers:[{node:'rogue0',url:`http://127.0.0.1:${rogueResident.address().port}`}]}));
 const g2Proc=spawn(process.execPath,[cli,'gateway','serve','--config',g2Path],{cwd:dir,stdio:'ignore'});
 t.after(()=>g2Proc.kill('SIGTERM'));
 const g2Url=`http://127.0.0.1:${gp2}`;
 await wait(async()=>(await(await fetch(g2Url+'/health',{headers:auth})).json()).peers?.rogue0?.lanes?.text?.ready);

 const rogueRes=await fetch(g2Url+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'rogue0',lane:'text',boot:rogueBoot,body:largeBody,privateKey:kp.privateKey}).headers,body:largeBody});
 assert.equal(rogueRes.status,503);
 assert.equal(rogueExecCalls,0);
});

test('TEST-INFER-PRIV-04: 4-layer job-id suppression, chatcmpl-private JSON & SSE heartbeat/chunk ID replacement, correlation header stripping, and zero logging of admit-envelope/sig/nonce',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv04-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const kp=makeEd25519Keypair();
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
  'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),
  'observations?':false,'heartbeat-ms':50,'replay-max-entries':3,'probe-interval-ms':400,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={node:'priv04-gw',bind:'127.0.0.1',port:gp,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':50,peers:[{node:'priv04-node',url:rUrl}]};
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));await writeFile(gPath,JSON.stringify(gCfg));
 let combinedLogs='';
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:['ignore','pipe','pipe']});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:['ignore','pipe','pipe']});
 for(const p of [rProc,gProc]){
  p.stdout.on('data',b=>combinedLogs+=b.toString('utf8'));
  p.stderr.on('data',b=>combinedLogs+=b.toString('utf8'));
 }
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

 const secretsToNeverLog=['attacker-job-corr-9999'];

 // 4a. Normal JSON response through gateway: id === "chatcmpl-private"
 const wOk=wireV1Headers(token,{owner:'priv04-node',lane:'text',boot:bootId,body:sampleBody,privateKey:kp.privateKey,extra:injectedHeaders});
 secretsToNeverLog.push(wOk.envelopeB64u,wOk.sig,wOk.envelope.nonce);
 const okRes=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wOk.headers,body:sampleBody});
 assert.equal(okRes.status,200);
 assertNoLeakHeaders(okRes,'normal 200');
 const okJson=await okRes.json();
 assert.equal(okJson.id,'chatcmpl-private');
 assert.ok(!JSON.stringify(okJson).includes('backend-upstream-json-id-888'));
 const bHdrs=backend.state.seenHeaders.at(-1);
 for(const h of ['x-murakumo-job-id','cf-ray','traceparent','tracestate','x-request-id','x-correlation-id']){
  assert.equal(bHdrs[h],undefined);
 }

 // 4b. Streaming SSE response: BOTH prefill-heartbeat! :id AND streaming chunk :id === "chatcmpl-private"
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv04-node']?.lanes?.text?.busy===false);
 const streamBody=JSON.stringify({model:'mishima',max_tokens:16,stream:true,messages:[{role:'user',content:'stream test'}]});
 const wSse=wireV1Headers(token,{owner:'priv04-node',lane:'text',boot:bootId,body:streamBody,privateKey:kp.privateKey,extra:injectedHeaders});
 secretsToNeverLog.push(wSse.envelopeB64u,wSse.sig,wSse.envelope.nonce);
 const sseRes=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wSse.headers,body:streamBody});
 assert.equal(sseRes.status,200);
 assertNoLeakHeaders(sseRes,'SSE 200');
 const sseText=await sseRes.text();
 const sseEvents=sseText.split('\n').filter(l=>l.startsWith('data: ')&&l!=='data: [DONE]').map(l=>JSON.parse(l.slice(6)));
 const heartbeats=sseEvents.filter(e=>Array.isArray(e.choices)&&e.choices.length===0);
 const contentChunks=sseEvents.filter(e=>e.choices?.[0]?.delta?.content==='OK');
 assert.ok(heartbeats.length>=1);
 assert.equal(contentChunks.length,1);
 for(const ev of [...heartbeats,...contentChunks]){
  assert.equal(ev.id,'chatcmpl-private');
 }
 assert.ok(!sseText.includes('backend-upstream-stream-id-999'));

 // 4c. 502 execution_unknown response (no job-id in JSON body or headers)
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv04-node']?.lanes?.text?.busy===false);
 const discBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'DISCONNECT_502'}]});
 const w502=wireV1Headers(token,{owner:'priv04-node',lane:'text',boot:bootId,body:discBody,privateKey:kp.privateKey,extra:injectedHeaders});
 secretsToNeverLog.push(w502.envelopeB64u,w502.sig,w502.envelope.nonce);
 const r502=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:w502.headers,body:discBody});
 assert.equal(r502.status,502);
 assertNoLeakHeaders(r502,'502 execution_unknown');
 assert.deepEqual(await r502.json(),{error:'execution_unknown',retryable:false});

 // 4d. Wait for lane to re-probe ready after 502 disconnect, then hit 503 replay_capacity
 await wait(async()=>(await(await fetch(rUrl+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const w503=wireV1Headers(token,{owner:'priv04-node',lane:'text',boot:bootId,body:sampleBody,privateKey:kp.privateKey,extra:injectedHeaders});
 secretsToNeverLog.push(w503.envelopeB64u,w503.sig,w503.envelope.nonce);
 const r503=await fetch(rUrl+'/execute/text',{method:'POST',headers:w503.headers,body:sampleBody});
 assert.equal(r503.status,503);
 assertNoLeakHeaders(r503,'503 replay_capacity');
 assert.deepEqual(await r503.json(),{error:'replay_capacity',executed:false});

 // 4e. Verify 0 logging of x-murakumo-admit-envelope, x-murakumo-admit-sig, replay_nonce, or external job-id
 for(const secret of secretsToNeverLog){
  assert.ok(!combinedLogs.includes(secret),`resident/gateway logs must NEVER contain ${secret.slice(0,16)}...`);
 }
});

test('TEST-INFER-PRIV-05: combines PR #715 inbound-token-file + inbound-allowed-routes with kagishi-ram privacy lane (403 route_not_allowed on /jobs/*, /images, /topology)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv05-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const stateDir=join(dir,'state');
 await mkdir(stateDir,{recursive:true});
 const fleetToken=randomBytes(32).toString('hex');
 const inboundToken=randomBytes(32).toString('hex');
 const fleetTokenFile=join(dir,'fleet.token');
 const inboundTokenFile=join(dir,'inbound.token');
 await writeFile(fleetTokenFile,fleetToken,{mode:0o600});
 await writeFile(inboundTokenFile,inboundToken,{mode:0o600});
 const kp=makeEd25519Keypair();

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'priv05-node',bind:'127.0.0.1',port:rp,
  'token-file':fleetTokenFile,'state-file':join(stateDir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),
  'observations?':false,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={
  node:'priv05-gw',bind:'127.0.0.1',port:gp,
  'token-file':fleetTokenFile,'inbound-token-file':inboundTokenFile,
  'inbound-allowed-routes':['GET /health','POST /v1/chat/completions'],
  'replay-ledger-mode':'kagishi-ram','probe-interval-ms':50,
  peers:[{node:'priv05-node',url:rUrl}]
 };
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 const inboundAuth={authorization:`Bearer ${inboundToken}`,'content-type':'application/json'};
 const fleetAuth={authorization:`Bearer ${fleetToken}`,'content-type':'application/json'};

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:inboundAuth})).json()).peers?.['priv05-node']?.lanes?.text?.ready);
 const gwHealth=await(await fetch(gUrl+'/health',{headers:inboundAuth})).json();
 const bootId=gwHealth.peers['priv05-node'].boot_id;
 assert.equal(gwHealth.replay_mode,'kagishi-ram');

 assert.equal((await fetch(gUrl+'/health')).status,401);
 assert.equal((await fetch(gUrl+'/health',{headers:fleetAuth})).status,401);
 assert.equal((await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:fleetAuth,body:sampleBody})).status,401);

 for(const [method,path] of [['GET','/jobs/job-12345678'],['POST','/jobs/job-12345678'],['POST','/v1/images/generations'],['GET','/topology']]){
  const resp=await fetch(gUrl+path,{method,headers:inboundAuth,body:method==='POST'?'{}':undefined});
  assert.equal(resp.status,403,`${method} ${path} must return 403 route_not_allowed`);
  assert.deepEqual(await resp.json(),{error:'route_not_allowed'});
 }

 const ok=await fetch(gUrl+'/v1/chat/completions',{
  method:'POST',
  headers:wireV1Headers(inboundToken,{owner:'priv05-node',lane:'text',boot:bootId,body:sampleBody,privateKey:kp.privateKey,extra:{'x-murakumo-job-id':'ext-id','cf-ray':'ray-05'}}).headers,
  body:sampleBody
 });
 assert.equal(ok.status,200);
 assert.equal(ok.headers.get('x-murakumo-job-id'),null);
 assert.equal(ok.headers.get('x-murakumo-node'),null);
 assert.equal(ok.headers.get('cf-ray'),null);
 assert.equal((await ok.json()).id,'chatcmpl-private');
 assert.equal(backend.state.calls,1);
 assert.deepEqual(await readdir(stateDir),[]);
});

test('TEST-INFER-PRIV-06: B1 disconnect (502 execution_unknown) -> restart B2 -> old request returns 410 executed:unknown retryable:false with 0 gateway auto-resign (+ negative mutant & provisional 409 check)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv06-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const kp=makeEd25519Keypair();
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
  'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),
  'observations?':false,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={node:'priv06-gw',bind:'127.0.0.1',port:gp,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':40,peers:[{node:'priv06-node',url:rUrl}]};
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));await writeFile(gPath,JSON.stringify(gCfg));

 const badGwPath=join(dir,'g-mint.json');
 await writeFile(badGwPath,JSON.stringify({...gCfg,port:await port(),'mint-replay-headers?':true}));
 const badGw=spawn(process.execPath,[cli,'gateway','serve','--config',badGwPath],{cwd:dir,stdio:'ignore'});
 assert.notEqual((await once(badGw,'close'))[0],0);

 let rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv06-node']?.lanes?.text?.ready);
 const boot1=(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers['priv06-node'].boot_id;

 const dropBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'DROP_ONCE'}]});
 const b1Nonce=randomBytes(16).toString('hex');
 const b1Hdrs=wireV1Headers(token,{owner:'priv06-node',lane:'text',boot:boot1,body:dropBody,nonce:b1Nonce,privateKey:kp.privateKey}).headers;
 const res502=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:b1Hdrs,body:dropBody});
 assert.equal(res502.status,502);
 assert.deepEqual(await res502.json(),{error:'execution_unknown',retryable:false});
 assert.equal(backend.state.calls,1);

 rProc.kill('SIGTERM');await once(rProc,'exit');
 rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 await wait(async()=>{
  const h=await(await fetch(gUrl+'/health',{headers:auth})).json();
  const p=h.peers?.['priv06-node'];
  return p?.lanes?.text?.ready&&p?.boot_id&&p.boot_id!==boot1;
 });
 const boot2=(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers['priv06-node'].boot_id;
 assert.notEqual(boot2,boot1);

 const replayOld=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:b1Hdrs,body:dropBody});
 assert.equal(replayOld.status,410);
 assert.deepEqual(await replayOld.json(),{error:'boot_id_mismatch',executed:'unknown',retryable:false});
 assert.equal(backend.state.calls,1);

 // Negative mutant check proving auto-resign onto B2 would cause double execution
 const mutantHdrs=wireV1Headers(token,{owner:'priv06-node',lane:'text',boot:boot2,body:sampleBody,nonce:b1Nonce,privateKey:kp.privateKey}).headers;
 const mutantRes=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:mutantHdrs,body:sampleBody});
 assert.equal(mutantRes.status,200);
 assert.equal(backend.state.calls,2);

 // Provisional omitted boot_id pre-boot window -> 409 {error:"provisional_pre_boot_window", executed:"unknown", retryable:false}
 const pp=await port();
 const provCfg={
  node:'priv06-prov',bind:'127.0.0.1',port:pp,
  'token-file':tokenFile,'state-file':join(dir,'prov-receipts.json'),
  'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),
  'observations?':false,'allow-provisional-no-boot-id?':true,
  'job-timeout-ms':1000,'replay-window-ms':2000,'replay-clock-skew-ms':500,'replay-rotation-ms':3000,
  'probe-interval-ms':30000,lanes:rCfg.lanes
 };
 const provPath=join(dir,'prov.json');await writeFile(provPath,JSON.stringify(provCfg));
 const provStart=Date.now();
 const provProc=spawn(process.execPath,[cli,'resident','serve','--config',provPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>provProc.kill('SIGTERM'));
 const provUrl=`http://127.0.0.1:${pp}`;
 await wait(async()=>(await(await fetch(provUrl+'/health',{headers:auth})).json()).lanes?.text?.ready);

 const preBoot=await fetch(provUrl+'/execute/text',{method:'POST',headers:wireV1Headers(token,{owner:'priv06-prov',lane:'text',boot:null,body:sampleBody,iat:provStart,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(preBoot.status,409);
 assert.deepEqual(await preBoot.json(),{error:'provisional_pre_boot_window',executed:'unknown',retryable:false});
 assert.equal(backend.state.calls,2);
});

test('TEST-INFER-PRIV-07: AWAI-4154 v1.0.3 PureEdDSA Wire v1 strict parsing (padding, dup headers, dup JSON keys, >2048B, forbidden op, unknown field, alg/kid/route/owner/lane binding, active+staged+revoked pinning)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv07-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const fleetToken=randomBytes(32).toString('hex');
 const fleetTokenFile=join(dir,'fleet.token');
 await writeFile(fleetTokenFile,fleetToken,{mode:0o600});
 const kpActive=makeEd25519Keypair();
 const kpStaged=makeEd25519Keypair();
 const kpRevoked=makeEd25519Keypair();
 const stagedPubFile=join(dir,'staged.pub.pem');
 await writeFile(stagedPubFile,kpStaged.publicKey,{mode:0o600});
 const auth={authorization:`Bearer ${fleetToken}`};

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'priv07-node',bind:'127.0.0.1',port:rp,
  'token-file':fleetTokenFile,'state-file':join(dir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','observations?':false,'probe-interval-ms':30000,
  'admit-public-keys':{
   'k-active':{'public-key':kpActive.publicKey,state:'active'},
   'k-staged':{'public-key-file':stagedPubFile,state:'staged'},
   'k-revoked':{'public-key':kpRevoked.publicKey,state:'revoked'}
  },
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${backend.server.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={node:'priv07-gw',bind:'127.0.0.1',port:gp,'token-file':fleetTokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':50,peers:[{node:'priv07-node',url:rUrl}]};
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv07-node']?.lanes?.text?.ready);
 const bootId=(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers['priv07-node'].boot_id;

 // 1. Plaintext headers only (zero fallback!) -> 401
 const rPlain=await fetch(rUrl+'/execute/text',{method:'POST',headers:{authorization:`Bearer ${fleetToken}`,'content-type':'application/json','x-murakumo-boot-id':bootId,'x-murakumo-issued-at':String(Date.now()),'x-murakumo-replay-nonce':randomBytes(16).toString('hex')},body:sampleBody});
 assert.equal(rPlain.status,401);
 assert.equal((await rPlain.json()).error,'admit_envelope_required');

 // 2. Missing x-murakumo-admit-sig -> 401
 assert.equal((await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,sig:null}).headers,body:sampleBody})).status,401);

 // 3. Padded Base64url ('=') in envelope OR sig -> 400
 const orig=wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,kid:'k-active',privateKey:kpActive.privateKey});
 const rPadEnv=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...orig.headers,'x-murakumo-admit-envelope':orig.envelopeB64u+'='},body:sampleBody});
 assert.equal(rPadEnv.status,400,'padded base64url envelope must be rejected');
 const rPadSig=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...orig.headers,'x-murakumo-admit-sig':orig.sig+'=='},body:sampleBody});
 assert.equal(rPadSig.status,400,'padded base64url sig must be rejected');

 // 4. Duplicate HTTP headers (raw TCP sending 2x envelope or 2x sig) -> 400 duplicate_header on both gateway & resident
 const dupEnvGw=await rawHttpRequest(gp,'POST','/v1/chat/completions',[
  `Authorization: Bearer ${fleetToken}`,'Content-Type: application/json',
  `x-murakumo-admit-envelope: ${orig.envelopeB64u}`,`x-murakumo-admit-envelope: ${orig.envelopeB64u}`,
  `x-murakumo-admit-sig: ${orig.sig}`
 ],sampleBody);
 assert.equal(dupEnvGw.status,400);
 const dupSigRes=await rawHttpRequest(rp,'POST','/execute/text',[
  `Authorization: Bearer ${fleetToken}`,'Content-Type: application/json',
  `x-murakumo-admit-envelope: ${orig.envelopeB64u}`,
  `x-murakumo-admit-sig: ${orig.sig}`,`x-murakumo-admit-sig: ${orig.sig}`
 ],sampleBody);
 assert.equal(dupSigRes.status,400);

 // 5. Duplicate JSON keys (literal AND Unicode-escaped \u0069at) -> 400
 for(const dupJson of [
  `{"v":1,"alg":"ed25519","kid":"k-active","route":"POST /v1/chat/completions","owner":"priv07-node","lane":"text","boot":"${bootId}","iat":${Date.now()},"iat":${Date.now()+1},"nonce":"${randomBytes(16).toString('hex')}"}`,
  `{"v":1,"alg":"ed25519","kid":"k-active","route":"POST /v1/chat/completions","owner":"priv07-node","lane":"text","boot":"${bootId}","iat":${Date.now()},"\\u0069at":${Date.now()+1},"nonce":"${randomBytes(16).toString('hex')}"}`
 ]){
  const raw=Buffer.from(dupJson,'utf8');
  const sig=signWireV1Ed25519('k-active',kpActive.privateKey,raw,sampleBody);
  const rDupJson=await fetch(rUrl+'/execute/text',{method:'POST',headers:{authorization:`Bearer ${fleetToken}`,'content-type':'application/json','x-murakumo-admit-envelope':raw.toString('base64url'),'x-murakumo-admit-sig':sig},body:sampleBody});
  assert.equal(rDupJson.status,400,'duplicate JSON key must be rejected');
 }

 // 6. Envelope > 2048 bytes -> 400
 const bigEnvRaw=Buffer.from(JSON.stringify({...orig.envelope,nonce:'a'.repeat(2100)}),'utf8');
 assert.ok(bigEnvRaw.length>2048);
 const rBig=await fetch(rUrl+'/execute/text',{method:'POST',headers:{authorization:`Bearer ${fleetToken}`,'content-type':'application/json','x-murakumo-admit-envelope':bigEnvRaw.toString('base64url'),'x-murakumo-admit-sig':signWireV1Ed25519('k-active',kpActive.privateKey,bigEnvRaw,sampleBody)},body:sampleBody});
 assert.equal(rBig.status,400);

 // 7. Forbidden downstream "op" field AND unknown extra field -> 400
 const rOp=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,privateKey:kpActive.privateKey,extraFields:{op:randomBytes(16).toString('hex')}}).headers,body:sampleBody});
 assert.equal(rOp.status,400);
 assert.equal((await rOp.json()).error,'forbidden_admit_field_op');

 const rExtra=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,privateKey:kpActive.privateKey,extraFields:{extra_junk:1}}).headers,body:sampleBody});
 assert.equal(rExtra.status,400);
 assert.equal((await rExtra.json()).error,'unknown_admit_envelope_field');

 // 8. Unsupported alg (hmac-sha256 & reserved ed25519+ml-dsa-65) -> 403
 for(const badAlg of ['hmac-sha256','ed25519+ml-dsa-65']){
  const rAlg=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,alg:badAlg,privateKey:kpActive.privateKey}).headers,body:sampleBody});
  assert.equal(rAlg.status,403,`alg ${badAlg} must fail closed 403`);
  assert.equal((await rAlg.json()).error,'unsupported_admit_alg');
 }

 // 9. Non-integer iat -> 400
 const rFloatIat=await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,iat:Date.now()+0.5,privateKey:kpActive.privateKey}).headers,body:sampleBody});
 assert.equal(rFloatIat.status,400);

 // 10. header_kid !== envelope.kid, unknown kid, and revoked kid -> 403
 const rKidMismatch=await fetch(rUrl+'/execute/text',{method:'POST',headers:{...orig.headers,'x-murakumo-admit-sig':'k-staged.'+orig.sig.split('.')[1]},body:sampleBody});
 assert.equal(rKidMismatch.status,403);
 assert.equal((await rKidMismatch.json()).error,'admit_kid_mismatch');

 const rUnknownKid=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,kid:'k-unknown',privateKey:kpActive.privateKey}).headers,body:sampleBody});
 assert.equal(rUnknownKid.status,403);
 assert.equal((await rUnknownKid.json()).error,'unknown_admit_kid');

 const rRevoked=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,kid:'k-revoked',privateKey:kpRevoked.privateKey}).headers,body:sampleBody});
 assert.equal(rRevoked.status,403);
 assert.equal((await rRevoked.json()).error,'revoked_admit_kid');

 // 11. Single-field tampering on iat, nonce, boot -> 403 invalid_admit_sig
 for(const [label,mutate] of [
  ['iat',e=>({...e,iat:e.iat+1})],
  ['nonce',e=>({...e,nonce:(e.nonce[0]==='a'?'b':'a')+e.nonce.slice(1)})],
  ['boot',e=>({...e,boot:(e.boot[0]==='a'?'b':'a')+e.boot.slice(1)})]
 ]){
  const tamperedB64u=Buffer.from(JSON.stringify(mutate(orig.envelope)),'utf8').toString('base64url');
  const rTamper=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:{...orig.headers,'x-murakumo-admit-envelope':tamperedB64u},body:sampleBody});
  assert.equal(rTamper.status,403,`tampering ${label} must return 403`);
  assert.equal((await rTamper.json()).error,'invalid_admit_sig');
 }

 // 12. Payload substitution -> 403 invalid_admit_sig
 const rSub=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:orig.headers,body:sampleBody.replace('hello kagishi privacy','evil substituted prompt')});
 assert.equal(rSub.status,403);
 assert.equal((await rSub.json()).error,'invalid_admit_sig');

 // 13. Transitional header mismatch -> 403 envelope_header_mismatch
 const rTrans=await fetch(rUrl+'/execute/text',{method:'POST',headers:{...orig.headers,'x-murakumo-issued-at':String(orig.envelope.iat+5)},body:sampleBody});
 assert.equal(rTrans.status,403);
 assert.equal((await rTrans.json()).error,'envelope_header_mismatch');

 // 14. Route mismatch (gateway AND resident), owner mismatch, lane mismatch -> 403
 const badRouteHdrs=wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',route:'POST /v1/images/generations',boot:bootId,body:sampleBody,privateKey:kpActive.privateKey}).headers;
 const rRouteGw=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:badRouteHdrs,body:sampleBody});
 assert.equal(rRouteGw.status,403);assert.equal((await rRouteGw.json()).error,'route_mismatch');
 const rRouteRes=await fetch(rUrl+'/execute/text',{method:'POST',headers:badRouteHdrs,body:sampleBody});
 assert.equal(rRouteRes.status,403);assert.equal((await rRouteRes.json()).error,'route_mismatch');

 const rOwner=await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'other-node',lane:'text',boot:bootId,body:sampleBody,privateKey:kpActive.privateKey}).headers,body:sampleBody});
 assert.equal(rOwner.status,403);assert.equal((await rOwner.json()).error,'owner_mismatch');

 const rLane=await fetch(rUrl+'/execute/text',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'otherlane',boot:bootId,body:sampleBody,privateKey:kpActive.privateKey}).headers,body:sampleBody});
 assert.equal(rLane.status,403);assert.equal((await rLane.json()).error,'lane_mismatch');

 assert.equal(backend.state.calls,0,'all rejected Wire v1.0.3 requests must produce 0 backend calls');

 // 15. Positive key pinning: active key (<kid>.<sig> + matching transitional headers) AND staged key (bare <sig> format) both succeed!
 const activeOk=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,kid:'k-active',privateKey:kpActive.privateKey,includeLegacy:true}).headers,body:sampleBody});
 assert.equal(activeOk.status,200);await activeOk.json();
 assert.equal(backend.state.calls,1);

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv07-node']?.lanes?.text?.busy===false);
 const stagedOk=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(fleetToken,{owner:'priv07-node',lane:'text',boot:bootId,body:sampleBody,kid:'k-staged',privateKey:kpStaged.privateKey,bareSig:true}).headers,body:sampleBody});
 assert.equal(stagedOk.status,200);await stagedOk.json();
 assert.equal(backend.state.calls,2);
});

test('TEST-INFER-PRIV-08: privacy-lane gateway forbids fallback to durable peers (0 durable calls, 0 receipts.json created when durable-only or privacy peer unready)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv08-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const privStateDir=join(dir,'priv-state');
 const durStateDir=join(dir,'dur-state');
 await mkdir(privStateDir,{recursive:true});
 await mkdir(durStateDir,{recursive:true});
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const kp=makeEd25519Keypair();
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

 const durPort=await port(),durUrl=`http://127.0.0.1:${durPort}`;
 const durPath=join(dir,'dur.json');
 await writeFile(durPath,JSON.stringify({
  node:'dur0',bind:'127.0.0.1',port:durPort,'token-file':tokenFile,'state-file':join(durStateDir,'receipts.json'),
  'observations?':false,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${durBackend.server.address().port}`,path:'/v1/chat/completions'}}
 }));
 const durProc=spawn(process.execPath,[cli,'resident','serve','--config',durPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>durProc.kill('SIGTERM'));
 await wait(async()=>(await(await fetch(durUrl+'/health',{headers:auth})).json()).lanes?.text?.ready);
 const durHealth=await(await fetch(durUrl+'/health',{headers:auth})).json();
 assert.equal(durHealth.replay_mode,'durable');

 let badBootCalls=0;
 const badBootPeer=createServer((req,res)=>{
  if(req.url==='/health')return res.end(JSON.stringify({node:'badboot0',replay_mode:'kagishi-ram',boot_id:'not-128-bit',lanes:{text:{kind:'text',model:'mishima',context:32768,ready:true,busy:false}}}));
  badBootCalls++;req.resume();req.on('end',()=>res.end('{}'));
 });
 badBootPeer.listen(0,'127.0.0.1');await once(badBootPeer,'listening');
 t.after(()=>{badBootPeer.closeAllConnections();badBootPeer.close();});

 const privPort=await port(),privUrl=`http://127.0.0.1:${privPort}`;
 const gp=await port(),gUrl=`http://127.0.0.1:${gp}`;
 const gPath=join(dir,'gw.json');
 await writeFile(gPath,JSON.stringify({
  node:'priv08-gw',bind:'127.0.0.1',port:gp,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':40,
  peers:[{node:'dur0',url:durUrl},{node:'badboot0',url:`http://127.0.0.1:${badBootPeer.address().port}`},{node:'priv0',url:privUrl}]
 }));
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>gProc.kill('SIGTERM'));

 await wait(async()=>(await fetch(gUrl+'/health',{headers:auth})).status===503);
 assert.deepEqual((await(await fetch(gUrl+'/health',{headers:auth})).json()).peers,{});

 const resDur=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'dur0',lane:'text',boot:durHealth.boot_id,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(resDur.status,503);
 assert.deepEqual(await resDur.json(),{error:'no_privacy_peer_available',executed:false});
 const resBad=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'badboot0',lane:'text',boot:'not-128-bit',body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(resBad.status,503);
 assert.equal(durBackend.state.calls,0);
 assert.equal(durBackend.state.tokenizes,0);
 assert.equal(badBootCalls,0);

 privBackend.state.busy=true;
 const privPath=join(dir,'priv.json');
 await writeFile(privPath,JSON.stringify({
  node:'priv0',bind:'127.0.0.1',port:privPort,'token-file':tokenFile,'state-file':join(privStateDir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),
  'observations?':false,'probe-interval-ms':400,
  lanes:{text:{kind:'text',model:'mishima',context:32768,'input-tokenizer':'llama-template',group:'gpu',
    backend:`http://127.0.0.1:${privBackend.server.address().port}`,path:'/v1/chat/completions'}}
 }));
 const privProc=spawn(process.execPath,[cli,'resident','serve','--config',privPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>privProc.kill('SIGTERM'));
 await wait(async()=>(await(await fetch(privUrl+'/health',{headers:auth})).json()).boot_id);
 await sleep(150);

 const privBoot=(await(await fetch(privUrl+'/health',{headers:auth})).json()).boot_id;
 const resUnready=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'priv0',lane:'text',boot:privBoot,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(resUnready.status,503);
 assert.deepEqual(await resUnready.json(),{error:'no_privacy_peer_available',executed:false});
 assert.equal(durBackend.state.calls,0);

 privBackend.state.busy=false;
 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.priv0?.lanes?.text?.ready);
 const resOk=await fetch(gUrl+'/v1/chat/completions',{method:'POST',headers:wireV1Headers(token,{owner:'priv0',lane:'text',boot:privBoot,body:sampleBody,privateKey:kp.privateKey}).headers,body:sampleBody});
 assert.equal(resOk.status,200);await resOk.json();
 assert.equal(privBackend.state.calls,1);
 assert.equal(durBackend.state.calls,0);
 assert.deepEqual(await readdir(privStateDir),[]);
});

test('TEST-INFER-PRIV-09: strict allowlist reconstruction of success JSON, SSE chunks, and error JSON strips unknown/signature-shaped backend metadata and drops unparsed SSE lines',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv09-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});
 const kp=makeEd25519Keypair();
 const auth={authorization:`Bearer ${token}`};

 // Adversarial backend injecting synthetic signature-shaped metadata & unparsed SSE lines across success, SSE, and error paths
 const leakyBackend=createServer((req,res)=>{
  if(req.url==='/slots')return res.end(JSON.stringify([{is_processing:false}]));
  let data='';
  req.on('data',b=>data+=b);
  req.on('end',()=>{
   const body=JSON.parse(data);
   const content=body.messages?.[0]?.content??'';
   if(content.startsWith('2+3'))return res.end(JSON.stringify({choices:[{message:{content:'5'}}]}));
   if(content==='ERR_JSON'){
    res.writeHead(400,{'content-type':'application/json','x-murakumo-node':'leak-hdr-node'});
    return res.end(JSON.stringify({
     error:{message:'bad prompt',type:'invalid_request_error',code:'bad_req',param:null,backend_sig:'LEAK_ERR_INNER_SIG',signature:'LEAK_ERR_INNER_SIG2'},
     backend_sig:'LEAK_ERR_TOP_SIG',
     signature:'LEAK_ERR_TOP_SIG2',
     'x-murakumo-job-id':'LEAK_ERR_JOB',
     'x-murakumo-node':'LEAK_ERR_NODE',
     unknown_meta:{trace:'LEAK_ERR_TRACE'}
    }));
   }
   if(content==='ERR_PLAIN'){
    res.writeHead(500,{'content-type':'text/plain'});
    return res.end('backend_sig=LEAK_PLAIN_ERROR_SIG internal_host=secret-box-01');
   }
   if(content==='BAD_200_JSON'){
    res.writeHead(200,{'content-type':'application/json'});
    return res.end('not-valid-json backend_sig=LEAK_BAD_200');
   }
   if(body.stream){
    res.writeHead(200,{'content-type':'text/event-stream','x-murakumo-node':'leak-sse-hdr'});
    res.write(': comment backend_sig=LEAK_SSE_COMMENT\n');
    res.write('id: LEAK_SSE_EVENT_ID\n');
    res.write('event: x-murakumo-meta\n');
    res.write('retry: 9999\n');
    res.write('unparsed_raw_line: backend_sig=LEAK_UNPARSED_LINE\n');
    res.write('data: not-valid-json-backend_sig=LEAK_BAD_DATA_LINE\n\n');
    res.write('data: ["array-not-object","LEAK_ARRAY_DATA"]\n\n');
    res.write('data: '+JSON.stringify({
     id:'backend-sse-leak-id',
     object:'chat.completion.chunk',
     created:1764240000,
     model:'mishima',
     backend_sig:'LEAK_SSE_TOP_SIG',
     signature:'LEAK_SSE_TOP_SIG2',
     'x-murakumo-node':'LEAK_SSE_NODE',
     'x-murakumo-job-id':'LEAK_SSE_JOB',
     system_fingerprint:'LEAK_FP',
     timings:{predicted_ms:42},
     choices:[{
      index:0,
      finish_reason:null,
      backend_sig:'LEAK_SSE_CHOICE_SIG',
      delta:{role:'assistant',content:'CLEAN_SSE',backend_sig:'LEAK_SSE_DELTA_SIG',unknown_delta:'NOPE'}
     }],
     usage:{prompt_tokens:7,completion_tokens:3,total_tokens:10,backend_sig:'LEAK_SSE_USAGE_SIG'}
    })+'\n\n');
    res.write('data: [DONE]\n\n');
    return res.end();
   }
   res.writeHead(200,{'content-type':'application/json','x-murakumo-node':'leak-json-hdr'});
   res.end(JSON.stringify({
    id:'backend-json-leak-id',
    object:'chat.completion',
    created:1764240001,
    model:'mishima',
    backend_sig:'LEAK_JSON_TOP_SIG',
    signature:'LEAK_JSON_TOP_SIG2',
    'x-murakumo-node':'LEAK_JSON_NODE',
    'x-murakumo-job-id':'LEAK_JSON_JOB',
    system_fingerprint:'LEAK_JSON_FP',
    timings:{prompt_ms:12},
    slot_id:0,
    choices:[{
     index:0,
     finish_reason:'stop',
     backend_sig:'LEAK_JSON_CHOICE_SIG',
     message:{role:'assistant',content:'CLEAN_JSON',backend_sig:'LEAK_JSON_MSG_SIG',signature:'LEAK_JSON_MSG_SIG2'}
    }],
    usage:{prompt_tokens:5,completion_tokens:2,total_tokens:7,backend_sig:'LEAK_JSON_USAGE_SIG'}
   }));
  });
 });
 leakyBackend.listen(0,'127.0.0.1');
 await once(leakyBackend,'listening');
 t.after(()=>{leakyBackend.closeAllConnections();leakyBackend.close();});

 const rp=await port(),gp=await port();
 const rUrl=`http://127.0.0.1:${rp}`,gUrl=`http://127.0.0.1:${gp}`;
 const rCfg={
  node:'priv09-node',bind:'127.0.0.1',port:rp,
  'token-file':tokenFile,'state-file':join(dir,'receipts.json'),
  'replay-ledger-mode':'kagishi-ram','admit-public-keys':makeDualAdmitKeys(kp),
  'observations?':false,'probe-interval-ms':30000,
  lanes:{text:{kind:'text',model:'mishima',context:32768,group:'gpu',
    backend:`http://127.0.0.1:${leakyBackend.address().port}`,path:'/v1/chat/completions'}}
 };
 const gCfg={node:'priv09-gw',bind:'127.0.0.1',port:gp,'token-file':tokenFile,'replay-ledger-mode':'kagishi-ram','probe-interval-ms':40,peers:[{node:'priv09-node',url:rUrl}]};
 const rPath=join(dir,'r.json'),gPath=join(dir,'g.json');
 await writeFile(rPath,JSON.stringify(rCfg));await writeFile(gPath,JSON.stringify(gCfg));
 const rProc=spawn(process.execPath,[cli,'resident','serve','--config',rPath],{cwd:dir,stdio:'ignore'});
 const gProc=spawn(process.execPath,[cli,'gateway','serve','--config',gPath],{cwd:dir,stdio:'ignore'});
 t.after(()=>{rProc.kill('SIGTERM');gProc.kill('SIGTERM');});

 await wait(async()=>(await(await fetch(gUrl+'/health',{headers:auth})).json()).peers?.['priv09-node']?.lanes?.text?.ready);
 const {boot_id:bootId}=await(await fetch(rUrl+'/health',{headers:auth})).json();

 const waitGwFree=()=>wait(async()=>{const h=await(await fetch(gUrl+'/health',{headers:auth})).json();return h.peers?.['priv09-node']?.lanes?.text?.ready&&h.peers?.['priv09-node']?.lanes?.text?.busy===false;});
 for(const [via,endpoint] of [['resident',rUrl+'/execute/text'],['gateway',gUrl+'/v1/chat/completions']]){
  if(via==='gateway')await waitGwFree();
  // 1) Success JSON: only allowlisted fields survive, zero LEAK_* / backend_sig / signature / x-murakumo-*
  const okBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:`ok-${via}`}]});
  const okRes=await fetch(endpoint,{method:'POST',headers:wireV1Headers(token,{owner:'priv09-node',lane:'text',boot:bootId,body:okBody,privateKey:kp.privateKey}).headers,body:okBody});
  assert.equal(okRes.status,200,`${via} success JSON status`);
  const okRaw=await okRes.text();
  for(const forbidden of ['LEAK_','backend_sig','signature','x-murakumo-','system_fingerprint','timings','slot_id','backend-json-leak-id']){
   assert.ok(!okRaw.includes(forbidden),`${via} success JSON must strip ${forbidden}; got: ${okRaw}`);
  }
  assert.deepEqual(JSON.parse(okRaw),{
   id:'chatcmpl-private',
   object:'chat.completion',
   created:1764240001,
   model:'mishima',
   choices:[{index:0,message:{role:'assistant',content:'CLEAN_JSON'},finish_reason:'stop'}],
   usage:{prompt_tokens:5,completion_tokens:2,total_tokens:7}
  });

  if(via==='gateway')await waitGwFree();
  // 2) Streaming SSE: unparsed lines dropped, unknown/signature metadata stripped
  const sseBody=JSON.stringify({model:'mishima',max_tokens:16,stream:true,messages:[{role:'user',content:`sse-${via}`}]});
  const sseRes=await fetch(endpoint,{method:'POST',headers:wireV1Headers(token,{owner:'priv09-node',lane:'text',boot:bootId,body:sseBody,privateKey:kp.privateKey}).headers,body:sseBody});
  assert.equal(sseRes.status,200,`${via} SSE status`);
  const sseRaw=await sseRes.text();
  for(const forbidden of ['LEAK_','backend_sig','signature','x-murakumo-','system_fingerprint','timings','backend-sse-leak-id','event:','retry:','unparsed_raw_line','unknown_delta']){
   assert.ok(!sseRaw.includes(forbidden),`${via} SSE must strip/drop ${forbidden}; got: ${sseRaw}`);
  }
  const nonEmptyLines=sseRaw.split('\n').filter(Boolean);
  assert.ok(nonEmptyLines.every(l=>l.startsWith('data: ')),`${via} SSE must emit ONLY parsed data: lines; got: ${JSON.stringify(nonEmptyLines)}`);
  const parsedEvents=nonEmptyLines.filter(l=>l!=='data: [DONE]').map(l=>JSON.parse(l.slice(6)));
  assert.deepEqual(parsedEvents,[{
   id:'chatcmpl-private',
   object:'chat.completion.chunk',
   created:1764240000,
   model:'mishima',
   choices:[{index:0,delta:{role:'assistant',content:'CLEAN_SSE'},finish_reason:null}],
   usage:{prompt_tokens:7,completion_tokens:3,total_tokens:10}
  }]);
  assert.equal(nonEmptyLines.at(-1),'data: [DONE]');

  if(via==='gateway')await waitGwFree();
  // 3) Error JSON: only allowlisted error.{message,type,code,param} survive
  const errJsonBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'ERR_JSON'}]});
  const errJsonRes=await fetch(endpoint,{method:'POST',headers:wireV1Headers(token,{owner:'priv09-node',lane:'text',boot:bootId,body:errJsonBody,privateKey:kp.privateKey}).headers,body:errJsonBody});
  assert.equal(errJsonRes.status,400,`${via} error JSON status`);
  const errJsonRaw=await errJsonRes.text();
  for(const forbidden of ['LEAK_','backend_sig','signature','x-murakumo-','unknown_meta']){
   assert.ok(!errJsonRaw.includes(forbidden),`${via} error JSON must strip ${forbidden}; got: ${errJsonRaw}`);
  }
  assert.deepEqual(JSON.parse(errJsonRaw),{
   error:{message:'bad prompt',type:'invalid_request_error',code:'bad_req',param:null}
  });

  if(via==='gateway')await waitGwFree();
  // 4) Non-JSON plain error: never passed through raw -> reconstructed to {error:"backend_error"}
  const errPlainBody=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'ERR_PLAIN'}]});
  const errPlainRes=await fetch(endpoint,{method:'POST',headers:wireV1Headers(token,{owner:'priv09-node',lane:'text',boot:bootId,body:errPlainBody,privateKey:kp.privateKey}).headers,body:errPlainBody});
  assert.equal(errPlainRes.status,500,`${via} plain error status`);
  const errPlainRaw=await errPlainRes.text();
  assert.ok(!errPlainRaw.includes('LEAK_')&&!errPlainRaw.includes('secret-box-01'));
  assert.deepEqual(JSON.parse(errPlainRaw),{error:'backend_error'});

  if(via==='gateway')await waitGwFree();
  // 5) Unparseable 200 JSON from backend: fails closed 502 {error:"invalid_backend_response"} without leaking raw text
  const bad200Body=JSON.stringify({model:'mishima',max_tokens:16,messages:[{role:'user',content:'BAD_200_JSON'}]});
  const bad200Res=await fetch(endpoint,{method:'POST',headers:wireV1Headers(token,{owner:'priv09-node',lane:'text',boot:bootId,body:bad200Body,privateKey:kp.privateKey}).headers,body:bad200Body});
  assert.equal(bad200Res.status,502,`${via} unparseable 200 JSON must fail closed 502`);
  const bad200Raw=await bad200Res.text();
  assert.ok(!bad200Raw.includes('LEAK_'));
  assert.deepEqual(JSON.parse(bad200Raw),{error:'invalid_backend_response'});
 }
});

test('TEST-INFER-PRIV-10: buffer zeroization unit & live HTTP behavior (read-body! chunks, signing msg/env-raw, and resident/gateway request body buffers filled with 0 in finally)',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'murakumo-priv10-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const token=randomBytes(32).toString('hex');
 const tokenFile=join(dir,'token');
 await writeFile(tokenFile,token,{mode:0o600});

 const backend=makeBackend();
 backend.server.listen(0,'127.0.0.1');
 await once(backend.server,'listening');
 t.after(()=>{backend.server.closeAllConnections();backend.server.close();});

 const rp=await port(),gp=await port();
 const verifyZeroClj=`
 (require '[murakumo.infer.resident :as r]
          '[murakumo.infer.fleet-gateway :as gw]
          '["node:crypto" :as crypto]
          '["node:events" :as events])
 (let [all-zero? (fn [b] (and (js/Buffer.isBuffer b) (pos? (.-length b)) (.every b #(= 0 %))))
       kp1 (.generateKeyPairSync crypto "ed25519" #js {:publicKeyEncoding #js {:type "spki" :format "pem"} :privateKeyEncoding #js {:type "pkcs8" :format "pem"}})
       kp2 (.generateKeyPairSync crypto "ed25519" #js {:publicKeyEncoding #js {:type "spki" :format "pem"} :privateKeyEncoding #js {:type "pkcs8" :format "pem"}})
       admit-cfg {"k-active" {:public-key (.-publicKey kp1) :state "active"}
                  "k-staged" {:public-key (.-publicKey kp2) :state "staged"}}
       ;; 1) Unit test: read-body! zeroizes raw socket chunks on end AND on body-too-large
       ee1 (events/EventEmitter.)
       c1 (js/Buffer.from "secret-chunk-1-" "utf8")
       c2 (js/Buffer.from "secret-chunk-2" "utf8")
       p1 (r/read-body! ee1 1024)
       _ (.emit ee1 "data" c1)
       _ (.emit ee1 "data" c2)
       _ (.emit ee1 "end")
       ee2 (events/EventEmitter.)
       _ (set! (.-destroy ee2) (fn [] nil))
       c3 (js/Buffer.from "0123456789ABCDEF" "utf8")
       p2 (.catch (r/read-body! ee2 8) (fn [_] :rejected))
       _ (.emit ee2 "data" c3)
       ;; 2) Unit test: verify-admit-sig zeroizes msg buffer AND validate-admit-request zeroizes env-raw buffer
       captured-msgs (atom [])
       captured-envs (atom [])
       orig-msg r/admit-sig-message
       orig-parse r/parse-admit-envelope
       boot-id "00112233445566778899aabbccddeeff"
       ledger (r/make-replay-ledger {:node "znode"} boot-id (js/Date.now) (r/mono-ms))
       admit-keys (r/load-admit-keys! {:admit-public-keys admit-cfg})
       body-str (js/JSON.stringify #js {:model "mishima" :max_tokens 16 :messages #js [#js {:role "user" :content "secret zeroize prompt"}]})
       body-buf (js/Buffer.from body-str "utf8")
       make-wire (fn [b-str bad-sig?]
                   (let [env-raw (js/Buffer.from (js/JSON.stringify #js {:v 1 :alg "ed25519" :kid "k-active" :route "POST /v1/chat/completions" :owner "znode" :lane "text" :boot boot-id :iat (js/Date.now) :nonce (.toString (.randomBytes crypto 16) "hex")}) "utf8")
                         sig-input (orig-msg env-raw (js/Buffer.from (if bad-sig? (str b-str "X") b-str) "utf8"))
                         sig (.toString (.sign crypto nil sig-input (.-privateKey kp1)) "base64url")]
                     #js {"authorization" (str "Bearer " ${JSON.stringify(token)})
                          "content-type" "application/json"
                          "x-murakumo-admit-envelope" (.toString env-raw "base64url")
                          "x-murakumo-admit-sig" (str "k-active." sig)}))]
   (with-redefs [r/admit-sig-message (fn [e b] (let [m (orig-msg e b)] (swap! captured-msgs conj m) m))
                 r/parse-admit-envelope (fn [s] (let [res (orig-parse s)] (when (:envelope-raw res) (swap! captured-envs conj (:envelope-raw res))) res))]
     (let [v-ok (r/validate-admit-request ledger (make-wire body-str false) "text" "POST /v1/chat/completions" nil (js/Date.now) (r/mono-ms) admit-keys body-buf 2097152)
           v-bad (r/validate-admit-request ledger (make-wire body-str true) "text" "POST /v1/chat/completions" nil (js/Date.now) (r/mono-ms) admit-keys body-buf 2097152)]
       (when-not (and (:ok v-ok) (false? (:ok v-bad)))
         (throw (js/Error. "unexpected validate-admit-request outcomes")))
       (when-not (and (= 2 (count @captured-msgs)) (every? all-zero? @captured-msgs))
         (throw (js/Error. "verify-admit-sig did not zeroize msg buffers in finally")))
       (when-not (and (= 2 (count @captured-envs)) (every? all-zero? @captured-envs))
         (throw (js/Error. "validate-admit-request did not zeroize env-raw buffers in finally")))))
   (r/destroy-replay-ledger! ledger)
   ;; 3) Live HTTP behavior test: capture exact read-body! Buffer for resident /estimate, /execute, and gateway /v1/chat/completions
   (let [captured-bodies (atom [])
         orig-read-body r/read-body!]
     (alter-var-root #'r/read-body!
                     (constantly (fn [req lim]
                                   (.then (orig-read-body req lim)
                                          (fn [b] (swap! captured-bodies conj b) b)))))
     (let [res-svc (r/start! {:node "znode" :bind "127.0.0.1" :port ${rp}
                                :token-file ${JSON.stringify(tokenFile)}
                                :state-file ${JSON.stringify(join(dir,'receipts.json'))}
                                :replay-ledger-mode "kagishi-ram"
                                :admit-public-keys admit-cfg
                                :observations? false :probe-interval-ms 30000
                                :lanes {:text {:kind "text" :model "mishima" :context 32768
                                               :input-tokenizer "llama-template" :group "gpu"
                                               :backend ${JSON.stringify(`http://127.0.0.1:${backend.server.address().port}`)}
                                               :path "/v1/chat/completions"}}})
             gw-svc (gw/start! {:node "zgw" :bind "127.0.0.1" :port ${gp}
                                :token-file ${JSON.stringify(tokenFile)}
                                :replay-ledger-mode "kagishi-ram"
                                :admit-public-keys admit-cfg
                                :probe-interval-ms 40
                                :peers [{:node "znode" :url ${JSON.stringify(`http://127.0.0.1:${rp}`)}}]})
             live-boot (:boot-id res-svc)
             make-live-wire (fn [b-str bad-sig?]
                              (let [env-raw (js/Buffer.from (js/JSON.stringify #js {:v 1 :alg "ed25519" :kid "k-active" :route "POST /v1/chat/completions" :owner "znode" :lane "text" :boot live-boot :iat (js/Date.now) :nonce (.toString (.randomBytes crypto 16) "hex")}) "utf8")
                                    sig-input (orig-msg env-raw (js/Buffer.from (if bad-sig? (str b-str "X") b-str) "utf8"))
                                    sig (.toString (.sign crypto nil sig-input (.-privateKey kp1)) "base64url")]
                                #js {"authorization" (str "Bearer " ${JSON.stringify(token)})
                                     "content-type" "application/json"
                                     "x-murakumo-admit-envelope" (.toString env-raw "base64url")
                                     "x-murakumo-admit-sig" (str "k-active." sig)}))
             wait-ready (fn wait-ready [tries]
                          (-> (js/fetch ${JSON.stringify(`http://127.0.0.1:${gp}/health`)}
                                        #js {:headers #js {"authorization" (str "Bearer " ${JSON.stringify(token)})}})
                              (.then #(.json %))
                              (.then (fn [h]
                                       (if (some-> h .-peers .-znode .-lanes .-text .-ready)
                                         true
                                         (if (pos? tries)
                                           (js/Promise. (fn [r _] (js/setTimeout #(r (wait-ready (dec tries))) 50)))
                                           (throw (js/Error. "timeout waiting for ready"))))))))]
         (-> (js/Promise.all #js [p1 p2 (wait-ready 100)])
             (.then (fn [arr]
                      (let [joined (aget arr 0) r2 (aget arr 1)]
                        (when-not (and (= "secret-chunk-1-secret-chunk-2" (.toString joined "utf8"))
                                       (all-zero? c1) (all-zero? c2) (= :rejected r2) (all-zero? c3))
                          (throw (js/Error. "read-body! chunk zeroization failed"))))
                      ;; a) Resident /estimate with bad sig (403)
                      (let [h (make-live-wire body-str true)]
                        (aset h "x-murakumo-estimate-nonce" (.toString (.randomBytes crypto 16) "hex"))
                        (js/fetch ${JSON.stringify(`http://127.0.0.1:${rp}/estimate/text`)} #js {:method "POST" :headers h :body body-str}))))
             (.then (fn [r] (when-not (= 403 (.-status r)) (throw (js/Error. "expected 403 on bad /estimate"))) (.text r)))
             ;; b) Resident /estimate with valid sig (200)
             (.then (fn [_]
                      (let [h (make-live-wire body-str false)]
                        (aset h "x-murakumo-estimate-nonce" (.toString (.randomBytes crypto 16) "hex"))
                        (js/fetch ${JSON.stringify(`http://127.0.0.1:${rp}/estimate/text`)} #js {:method "POST" :headers h :body body-str}))))
             (.then (fn [r] (when-not (= 200 (.-status r)) (throw (js/Error. "expected 200 on valid /estimate"))) (.text r)))
             ;; c) Resident /execute with bad sig (403)
             (.then (fn [_]
                      (js/fetch ${JSON.stringify(`http://127.0.0.1:${rp}/execute/text`)} #js {:method "POST" :headers (make-live-wire body-str true) :body body-str})))
             (.then (fn [r] (when-not (= 403 (.-status r)) (throw (js/Error. "expected 403 on bad /execute"))) (.text r)))
             ;; d) Resident /execute with valid sig (200)
             (.then (fn [_]
                      (js/fetch ${JSON.stringify(`http://127.0.0.1:${rp}/execute/text`)} #js {:method "POST" :headers (make-live-wire body-str false) :body body-str})))
             (.then (fn [r] (when-not (= 200 (.-status r)) (throw (js/Error. "expected 200 on valid /execute"))) (.text r)))
             ;; e) Gateway /v1/chat/completions with bad sig (403)
             (.then (fn [_]
                      (js/fetch ${JSON.stringify(`http://127.0.0.1:${gp}/v1/chat/completions`)} #js {:method "POST" :headers (make-live-wire body-str true) :body body-str})))
             (.then (fn [r] (when-not (= 403 (.-status r)) (throw (js/Error. "expected 403 on bad gateway"))) (.text r)))
             ;; f) Gateway /v1/chat/completions with valid sig (200)
             (.then (fn [_]
                      (js/fetch ${JSON.stringify(`http://127.0.0.1:${gp}/v1/chat/completions`)} #js {:method "POST" :headers (make-live-wire body-str false) :body body-str})))
             (.then (fn [r] (when-not (= 200 (.-status r)) (throw (js/Error. "expected 200 on valid gateway"))) (.text r)))
             (.then (fn [_] (js/Promise. (fn [r _] (js/setTimeout #(r true) 50)))))
             (.then (fn [_]
                      (when-not (and (>= (count @captured-bodies) 7) (every? all-zero? @captured-bodies))
                        (throw (js/Error. (str "captured HTTP request body buffers not all zeroized! count=" (count @captured-bodies)
                                               " nonzero=" (count (remove all-zero? @captured-bodies))))))
                      (println (str "PRIV10_ZEROIZE_OK buffers=" (count @captured-bodies)))))
             (.finally (fn []
                         (alter-var-root #'r/read-body! (constantly orig-read-body))
                         ((:close gw-svc))
                         ((:close res-svc))))))))
 `;
 const cljDir=await mkdtemp(join(tmpdir(),'murakumo-priv10-clj-'));
 t.after(()=>rm(cljDir,{recursive:true,force:true}));
 await writeFile(join(cljDir,'nbb.edn'),'{:paths []}\n');
 const {stdout:cljOut}=await execFileAsync('kbb',['--backend','sci','--classpath',join(root,'src'),'-e',verifyZeroClj],{cwd:cljDir,encoding:'utf8'});
 assert.match(cljOut,/PRIV10_ZEROIZE_OK/);
});
