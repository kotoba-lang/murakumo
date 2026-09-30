import {spawn} from 'node:child_process';
import {mkdtempSync,readFileSync,statSync,rmSync,writeFileSync,symlinkSync,chmodSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import {createPublicKey,verify} from 'node:crypto';
const home=mkdtempSync(path.join(tmpdir(),'murakumo-node-test-'));
let denied=false,payoutDenied=false,authnDenied=false,deviceDid;
let pendingChallenge=null,attestAccepted=true;const calls=[];
const server=http.createServer(async(req,res)=>{
 let body='';for await(const c of req)body+=c;
 calls.push({url:req.url,auth:req.headers.authorization,
  ownerAuth:req.headers['x-murakumo-owner-payout'],body:body?JSON.parse(body):null});
 res.setHeader('content-type','application/json');
 if(req.url?.endsWith('/challenge')){
  res.statusCode=pendingChallenge?200:404;
  return res.end(JSON.stringify(pendingChallenge??{challenge:null}));
 }
 if(req.url?.endsWith('/attest')){
  res.statusCode=attestAccepted?200:400;
  return res.end(JSON.stringify({verified:attestAccepted}));
 }
 if(req.url==='/v1/models')return res.end(JSON.stringify({data:[{id:'test-model'}]}));
 if(req.url==='/v1/murakumo/node-token'){
  res.statusCode=authnDenied?401:201;
  return res.end(JSON.stringify(authnDenied?{error:'invalid_node_proof'}:{
   token:'A'.repeat(100),tokenType:'Biscuit',holder:deviceDid,
   expiresAt:new Date(Date.now()+15*60*1000).toISOString()}));
 }
 if(req.url==='/infer/nodes'){res.statusCode=denied?401:201;return res.end(JSON.stringify({'node/admission':'pending'}));}
 if(req.url.endsWith('/heartbeat')){res.statusCode=201;return res.end('{}');}
 if(req.url.startsWith('/infer/payout?did='))return res.end(JSON.stringify({policy:{'minimum-credits':5000},account:{earned:6000,payable:6000}}));
 if(req.url==='/infer/payout/request'){res.statusCode=payoutDenied?402:201;return res.end(JSON.stringify(payoutDenied?{error:'insufficient-earned-credits'}:{'payout-id':'po-test'}));}
 res.statusCode=404;res.end('{}');
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const base=`http://127.0.0.1:${server.address().port}`;
const runWithEnv=(extra,...args)=>new Promise(resolve=>{
 const p=spawn(process.execPath,['release/node.mjs','node',...args],{env:{...process.env,MURAKUMO_NODE_HOME:home,MURAKUMO_NODE_CACAO:'',MURAKUMO_NODE_DID:'',MURAKUMO_SERVICE_TOKEN:'',...extra}});
 let out='';p.stdout.on('data',d=>out+=d);p.stderr.on('data',d=>out+=d);
 p.on('close',code=>resolve({code,out}));
});
const run=(...args)=>runWithEnv({},...args);
try{
 assert.equal((await run('init')).code,0);const original=readFileSync(path.join(home,'identity.json'),'utf8');
 const current=JSON.parse(original);
 deviceDid=current.did;
 assert.equal(statSync(path.join(home,'identity.json')).mode&0o777,0o600);
 assert.equal((await run('init')).code,0);assert.equal(readFileSync(path.join(home,'identity.json'),'utf8'),original);
 const flags=['--model','test-model','--local-url',base+'/v1','--base',base,
  '--authn',base,'--name','test-node'];
 let doctor=await run('doctor',...flags);assert.equal(doctor.code,0,doctor.out);assert.equal(calls.filter(x=>x.url.startsWith('/infer/')).length,0);
 doctor=await run('doctor',...flags,'--idle-only');assert.equal(doctor.code,0,doctor.out);
 assert.notEqual((await run('doctor','--model','wrong','--local-url',base+'/v1')).code,0);
 assert.notEqual((await run('doctor','--model')).code,0);
 let result=await run('check',...flags);assert.equal(result.code,0,result.out);assert.match(result.out,/heartbeat HTTP 201/);assert.match(result.out,/pending/);
 const hb=calls.find(x=>x.url.endsWith('/heartbeat'));assert.match(hb.auth,/^Biscuit /);assert.equal(hb.body['node/ready?'],false);assert.equal(hb.body['node/capacity']['slots-free'],0);
 const exchange=calls.find(x=>x.url==='/v1/murakumo/node-token');
 assert(Buffer.from(exchange.body.cacao_b64,'base64').toString('utf8').includes('murakumo://node-token'));
 assert(!calls.some(x=>x.url.includes('/queue')));assert(!result.out.includes('PRIVATE KEY'));
 const factory=path.join(home,'factory-identity.json');
 writeFileSync(factory,JSON.stringify({version:1,did:current.did,model:'Murakumo 2609',
  origin:'https://murakumo.cloud',token:'factory-secret',privateKeyPem:current.privateKey}),{mode:0o600});
 const external={MURAKUMO_NODE_IDENTITY_FILE:factory};
 assert.notEqual((await runWithEnv(external,'init')).code,0);
 result=await runWithEnv(external,'claim-once','--site',base);
 assert.equal(result.code,0,result.out);
 assert.match(result.out,/Claim response: idle/);
 assert.equal(calls.filter(x=>x.url.endsWith('/attest')).length,0);
 pendingChallenge={challenge:'challenge-one',nonce:'nonce-one',expiresAtMs:Date.now()+60000};
 result=await runWithEnv(external,'claim-once','--site',base);
 assert.equal(result.code,0,result.out);
 assert.match(result.out,/Claim response: proved/);
 const attestation=calls.filter(x=>x.url.endsWith('/attest')).at(-1);
 assert.equal(attestation.body.challenge,'challenge-one');
 const message=`aiueos-device-attest-v1\n${current.did}\n${base}\nnonce-one\n`;
 assert(verify(null,Buffer.from(message),createPublicKey(current.privateKey),
  Buffer.from(attestation.body.signature,'base64url')));
 assert(!result.out.includes(current.privateKey));
 attestAccepted=false;
 pendingChallenge={challenge:'challenge-two',nonce:'nonce-two',expiresAtMs:Date.now()+60000};
 result=await runWithEnv(external,'claim-once','--site',base);
 assert.notEqual(result.code,0);
 assert.match(result.out,/Claim response: rejected/);
 attestAccepted=true;pendingChallenge=null;
 assert.notEqual((await runWithEnv(external,'claim-once','--site','https://attacker.invalid')).code,0);
 result=await runWithEnv(external,'check',...flags);
 assert.equal(result.code,0,result.out);
 const enrollment=calls.filter(x=>x.url==='/infer/nodes').at(-1);
 assert.equal(enrollment.body['node/did'],current.did);
 assert.match(enrollment.auth,/^Biscuit /);
 assert(!result.out.includes('factory-secret'));
 result=await runWithEnv(external,'earnings','--base',base);
 assert.equal(result.code,0,result.out);
 assert.match(result.out,/Earned credits: 6000/);
 assert.match(result.out,/Payable credits: 6000/);
 assert.match(result.out,/Minimum request: 5000/);
 assert(calls.some(x=>x.url==='/infer/payout?did='+encodeURIComponent(current.did)));
 const destination='0x1111111111111111111111111111111111111111';
 const ownerTokenFile=path.join(home,'buyer-authorization.txt');
 writeFileSync(ownerTokenFile,'mk1.e30.signature',{mode:0o600});
 const payoutArgs=['--base',base,'--credits','5000','--to',destination,
  '--owner-token-file',ownerTokenFile];
 assert.notEqual((await runWithEnv(external,'payout','--base',base,'--credits','5000',
  '--to',destination)).code,0);
 assert.equal(calls.filter(x=>x.url==='/infer/payout/request').length,0);
 result=await runWithEnv(external,'payout',...payoutArgs);
 assert.equal(result.code,0,result.out);
 assert.match(result.out,/Payout request: po-test/);
 assert.match(result.out,/Awaiting operator approval/);
 const request=calls.filter(x=>x.url==='/infer/payout/request').at(-1);
 assert.deepEqual(request.body,{did:current.did,credits:5000,to:destination});
 assert.match(request.auth,/^CACAO /);
 assert.equal(request.ownerAuth,'mk1.e30.signature');
 assert(!result.out.includes('mk1.e30.signature'));
 assert(Buffer.from(request.auth.slice(6),'base64').toString('utf8').includes(
  `murakumo:payout?to=${destination}&credits=5000`));
 assert.equal(calls.filter(x=>x.url==='/infer/payout/request').length,1);
 assert.notEqual((await runWithEnv(external,'payout','--base',base,'--credits','0','--to',destination,
  '--owner-token-file',ownerTokenFile)).code,0);
 assert.notEqual((await runWithEnv(external,'payout','--base',base,'--credits','5000','--to','not-a-wallet',
  '--owner-token-file',ownerTokenFile)).code,0);
 assert.equal(calls.filter(x=>x.url==='/infer/payout/request').length,1);
 payoutDenied=true;
 assert.notEqual((await runWithEnv(external,'payout',...payoutArgs)).code,0);
 payoutDenied=false;
 chmodSync(factory,0o644);
 assert.notEqual((await runWithEnv(external,'check',...flags)).code,0);
 chmodSync(factory,0o600);
 writeFileSync(factory,JSON.stringify({did:current.did+'wrong',privateKeyPem:current.privateKey}),{mode:0o600});
 assert.notEqual((await runWithEnv(external,'check',...flags)).code,0);
 const link=path.join(home,'identity-link.json');symlinkSync(path.join(home,'identity.json'),link);
 assert.notEqual((await runWithEnv({MURAKUMO_NODE_IDENTITY_FILE:link},'check',...flags)).code,0);
 assert.notEqual((await runWithEnv({MURAKUMO_NODE_IDENTITY_FILE:'identity.json'},'check',...flags)).code,0);
 authnDenied=true;assert.notEqual((await run('check',...flags)).code,0);
 authnDenied=false;
 denied=true;assert.notEqual((await run('check',...flags)).code,0);
 console.log('Packaged CLI: factory DID reuse, signed Community enrollment, earned-credit view, signed payout request, and refusal exits passed.');
}finally{server.close();rmSync(home,{recursive:true,force:true});}
