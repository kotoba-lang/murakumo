// Read-only peer telemetry. A peer signature never grants human ownership or actions.
import {createHash,createPublicKey,verify,sign} from 'node:crypto';
export const hash=x=>createHash('sha256').update(x).digest('hex');
const b58='123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
export function didOf(key){const pub=key?.type==='public'?key:createPublicKey(key);if(pub.asymmetricKeyType!=='ed25519')throw Error('Ed25519 identity required');const der=pub.export({type:'spki',format:'der'});let n=BigInt('0x'+Buffer.concat([Buffer.from([0xed,1]),der.subarray(-32)]).toString('hex')),out='';while(n){out=b58[Number(n%58n)]+out;n/=58n;}return 'did:key:z'+out;}
export function validateRoster(r){
 if(r?.schema!=='murakumo.private-cluster.v1'||!Array.isArray(r.nodes)||r.nodes.length<2||r.nodes.length>32)throw Error('Invalid roster');
 const seen=new Set();for(const p of r.nodes){const u=new URL(p.endpoint);if(!/^100\.(?:[0-9]{1,3}\.){2}[0-9]{1,3}$/.test(u.hostname)||(Number(u.hostname.split('.')[1])<64||Number(u.hostname.split('.')[1])>127)||u.protocol!=='http:'||u.port!=='19842'||u.pathname!=='/'||u.username||u.password||u.search||u.hash||seen.has(p.did)||didOf(p.publicKey)!==p.did||!/^[-A-Za-z0-9._]{1,64}$/.test(p.name))throw Error('Invalid peer');seen.add(p.did);}
 if(r.clusterId!==hash(JSON.stringify(r.nodes.map(p=>p.did).sort())))throw Error('Roster identity mismatch');return r;
}
export function validateFacts(f){
 const bool=k=>typeof f?.[k]==='boolean',num=k=>Number.isFinite(f?.[k])&&f[k]>=0;
 if(!f||!['nativeAvailable','gpuVerified','engineReady','busy'].every(bool)||!['uptimeSeconds','load1','freeMiB','totalMiB'].every(num)||f.freeMiB>f.totalMiB||f.totalMiB>2147483647||!(f.clientMiB===null||num('clientMiB'))||!(f.backend===null||['mlx-metal','llama-metal'].includes(f.backend))||!(f.model===null||typeof f.model==='string'&&f.model.length<=256)||!(f.error===null||typeof f.error==='string'&&f.error.length<=100))throw Error('Invalid peer facts');
 const r=f.registration;if(!r||typeof r.registered!=='boolean'||!(r.accountDid===null||typeof r.accountDid==='string'&&r.accountDid.length<=256)||r.approvalUri!==undefined&&!/^https:\/\/auth\.kotoba\.cloud\/node\?code=[A-Z0-9_-]{10}$/.test(r.approvalUri)||r.expiresAt!==undefined&&!Number.isSafeInteger(r.expiresAt))throw Error('Invalid registration observation');return f;
}
export const signed=(body,key)=>{const bytes=JSON.stringify(body);return {body,signature:sign(null,Buffer.from(bytes),key).toString('base64url')};};
export function verifyEnvelope(envelope,roster,{kind,audience,now=Date.now(),historical=false}={}){
 const b=envelope?.body,p=roster.nodes.find(p=>p.did===b?.did);if(!p||b.schema!==kind||b.clusterId!==roster.clusterId||!Number.isSafeInteger(b.at)||(!historical&&Math.abs(now-b.at)>90000)||audience&&b.audience!==audience||!Number.isSafeInteger(b.sequence)||b.sequence<1||! /^[A-Za-z0-9_-]{86}$/.test(envelope.signature||'')||!verify(null,Buffer.from(JSON.stringify(b)),createPublicKey(p.publicKey),Buffer.from(envelope.signature,'base64url')))throw Error('Peer proof refused');if(kind==='itonami.cluster-monitor-receipt.v1')validateFacts(b.facts);return b;
}

const scope=b=>JSON.stringify([b.schema,b.clusterId,b.did,b.audience||'']);
export function authenticateRequest(envelope,roster,options){if(!options?.marks)throw Error('Durable replay state required');const b=verifyEnvelope(envelope,roster,options),key=scope(b),prior=options.marks[key];if(prior&&b.sequence<=prior.sequence)throw Error('Request replay refused');options.marks[key]={sequence:b.sequence,digest:hash(JSON.stringify(envelope))};return b;}
export function acceptObservation(envelope,roster,options){if(!options?.marks)throw Error('Durable observation state required');const b=verifyEnvelope(envelope,roster,options),key=scope(b),digest=hash(JSON.stringify(envelope)),prior=options.marks[key];if(prior&&(b.sequence<prior.sequence||b.sequence===prior.sequence&&digest!==prior.digest))throw Error('Observation rollback/equivocation refused');options.marks[key]={sequence:b.sequence,digest};return b;}
