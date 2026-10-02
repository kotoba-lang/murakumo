// `murakumo node onboard` (the box side of ADR-0243) against the packaged CLI, with the AIR
// simulated at the PCM level: the speaker command is `tee -a spk.raw`, the microphone command is
// `cat air.fifo`. This harness decodes the beacon from the speaker stream with ggwave, seals a
// profile with an INDEPENDENT node:crypto implementation, encodes it with ggwave and feeds it to
// the microphone. So ggwave, the framing, the sealing, the window rules, the keyfile and the
// acknowledgement all run for real; only the sound card is replaced.
import {spawn,execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const cli=path.join(here,'..','release','node.mjs');
const require=createRequire(path.join(here,'..','release','package.json'));
const g=await require('ggwave')();
const params=g.getDefaultParameters();params.sampleRateInp=48000;params.sampleRateOut=48000;
const dec=g.init(params),enc=g.init(params);
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'murakumo-onboard-'));
const home=path.join(tmp,'home'),state=path.join(tmp,'state'),keys=path.join(tmp,'nm'),air=path.join(tmp,'air.fifo'),spk=path.join(tmp,'spk.raw');
fs.mkdirSync(state,{mode:0o700});fs.writeFileSync(spk,'');execFileSync('mkfifo',[air]);
const env={...process.env,MURAKUMO_NODE_HOME:home};
execFileSync('node',[cli,'node','init'],{env,stdio:'ignore'});
const did=JSON.parse(fs.readFileSync(path.join(home,'identity.json'),'utf8')).did;
const fp=crypto.createHash('sha256').update(did).digest('hex').slice(0,8);
const labelSecret='AAECAwQFBgcICQoLDA0ODw';
fs.writeFileSync(path.join(state,'label-secret'),labelSecret,{mode:0o600});
const passphrase='demo-pass-1234';

// independent sealing (ADR-0243 wire format), not murakumo.onboard
const SPKI=Buffer.from('302a300506032b656e032100','hex');
function seal({key,code,ls,ssid,pass}){
  const peer=crypto.createPublicKey({key:Buffer.concat([SPKI,Buffer.from(key,'base64url')]),format:'der',type:'spki'});
  const eph=crypto.generateKeyPairSync('x25519');
  const shared=crypto.diffieHellman({privateKey:eph.privateKey,publicKey:peer});
  const aad=Buffer.from(`aiueos-wifi-profile-v1-acoustic\n${code}\n${fp}`);
  const okm=Buffer.from(crypto.hkdfSync('sha256',shared,Buffer.from(ls),aad,44));
  const c=crypto.createCipheriv('aes-256-gcm',okm.subarray(0,32),okm.subarray(32));c.setAAD(aad);
  const ct=Buffer.concat([c.update(Buffer.concat([Buffer.from([1]),Buffer.from(ssid),Buffer.from([0]),Buffer.from(pass)])),c.final(),c.getAuthTag()]);
  return Buffer.concat([Buffer.from([0x57]),eph.publicKey.export({format:'der',type:'spki'}).subarray(-32),ct]);
}

const heard=[];let offset=0,carry=Buffer.alloc(0),lastGrowth=Date.now(),silenceFed=true;
function feed(buf){
  carry=Buffer.concat([carry,buf]);const frame=4096*4;
  while(carry.length>=frame){
    const f=Buffer.from(carry.subarray(0,frame));carry=carry.subarray(frame);
    const out=g.decode(dec,new Int8Array(f.buffer,f.byteOffset,f.byteLength));
    if(out&&out.length)heard.push(Buffer.from(out).toString());
  }
}
// A real microphone never stops delivering frames, and the decoder only settles on a message when
// frames follow it. So when the speaker stream has been quiet for a moment, feed a second of silence.
function listenToSpeaker(){
  const size=fs.statSync(spk).size;
  if(size>offset){
    const fd=fs.openSync(spk,'r');const buf=Buffer.alloc(size-offset);fs.readSync(fd,buf,0,buf.length,offset);fs.closeSync(fd);
    offset=size;lastGrowth=Date.now();silenceFed=false;feed(buf);return;
  }
  if(!silenceFed&&Date.now()-lastGrowth>500){silenceFed=true;feed(Buffer.alloc(48000*4));}
}
const airStream=fs.createWriteStream(air);
function sendToMicrophone(bytes){
  const w=g.encode(enc,Uint8Array.from(bytes),g.ProtocolId.GGWAVE_PROTOCOL_ULTRASOUND_FAST,55);
  airStream.write(Buffer.from(w.buffer,w.byteOffset,w.byteLength));
  airStream.write(Buffer.alloc(48000*4));                 // a second of silence so the decoder finishes the frame
}
const until=async(what,test,ms=60000)=>{const t=Date.now();for(;;){listenToSpeaker();const v=test();if(v)return v;if(Date.now()-t>ms)throw new Error('timeout waiting for '+what+'; heard='+JSON.stringify(heard)+' cli-out='+out+' cli-err='+err);await new Promise(r=>setTimeout(r,150));}};
const beaconOf=()=>{for(const h of heard){const m=/^mk1\|([A-Z0-9]{6})\|([0-9a-f]{8})\|([\w-]{43})$/.exec(h);if(m)return {code:m[1],fp:m[2],key:m[3],text:h};}};
const lastBeacon=()=>{let b;for(const h of heard){const m=/^mk1\|([A-Z0-9]{6})\|([0-9a-f]{8})\|([\w-]{43})$/.exec(h);if(m)b={code:m[1],fp:m[2],key:m[3]};}return b;};

const onlineServer=http.createServer((q,r)=>r.end('ok'));
const port=18300+Math.floor(Math.random()*500);
const child=spawn('node',[cli,'node','onboard','--state-dir',state,'--keyfile-dir',keys,'--no-reload','--site',`http://127.0.0.1:${port}`,
  '--play-cmd',`tee -a ${spk}`,'--record-cmd',`cat ${air}`,'--ttl-minutes','5'],{env,stdio:['ignore','pipe','pipe']});
let out='',err='';child.stdout.on('data',d=>out+=d);child.stderr.on('data',d=>err+=d);
const exited=new Promise(r=>child.on('exit',c=>r(c)));
try{
  const first=await until('the first beacon',beaconOf);
  assert.equal(first.fp,fp,'the beacon carries the fingerprint of the device DID');
  assert.equal(first.key.length,43);
  // 1) a bystander seals without the label secret: it must not open, and the code must rotate
  sendToMicrophone(seal({key:first.key,code:first.code,ls:'AAAAAAAAAAAAAAAAAAAAAA',ssid:'EvilNet',pass:'evil-pass-1234'}));
  const rotated=await until('a new beacon with a new code',()=>{const b=lastBeacon();return b&&b.code!==first.code?b:null;});
  assert.notEqual(rotated.code,first.code);
  assert.ok(!fs.existsSync(path.join(keys,'murakumo-onboard.nmconnection')),'a message that did not open applies nothing');
  // 2) the person with the label seals correctly for the NEW code
  sendToMicrophone(seal({key:rotated.key,code:rotated.code,ls:labelSecret,ssid:'DemoNet',pass:passphrase}));
  await until('the acknowledgement',()=>heard.includes('ack|'+rotated.code));
  const keyfile=path.join(keys,'murakumo-onboard.nmconnection');
  assert.ok(fs.existsSync(keyfile),'the profile reached NetworkManager as a keyfile');
  assert.equal(fs.statSync(keyfile).mode&0o777,0o600);
  const text=fs.readFileSync(keyfile,'utf8');
  assert.ok(text.includes('ssid=DemoNet')&&text.includes(`psk=${passphrase}`)&&text.includes('key-mgmt=wpa-psk'));
  assert.ok(!text.includes('EvilNet'));
  // only the encrypted message is kept in the state directory
  const envelope=fs.readFileSync(path.join(state,'envelope.json'),'utf8');
  assert.ok(!envelope.includes(passphrase)&&!envelope.includes('DemoNet'),'the stored envelope is encrypted');
  assert.equal(fs.statSync(path.join(state,'envelope.json')).mode&0o777,0o600);
  assert.ok(fs.existsSync(path.join(state,'device-key.pem'))&&(fs.statSync(path.join(state,'device-key.pem')).mode&0o777)===0o600);
  assert.ok(!out.includes(passphrase)&&!err.includes(passphrase),'the passphrase is never logged');
  // 3) it came online: the box finishes
  await new Promise(r=>onlineServer.listen(port,'127.0.0.1',r));
  const code=await Promise.race([exited,new Promise(r=>setTimeout(()=>r('timeout'),30000))]);
  assert.equal(code,0,'exit 0 once online. stdout='+out+' stderr='+err);
  assert.match(out,/Onboarding: online/);
  // 4) the claim retires the onboarding secrets and keeps the box from beaconing again
  console.log('onboard device ok');
}finally{child.kill();airStream.destroy();onlineServer.close();fs.rmSync(tmp,{recursive:true,force:true});}
