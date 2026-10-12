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
export const signed=(body,key)=>{const bytes=JSON.stringify(body);return {body,signature:sign(null,Buffer.from(bytes),key).toString('base64url')};};
export function authenticated(envelope,roster,{kind,audience,now=Date.now(),historical=false}={}){
 const b=envelope?.body,p=roster.nodes.find(p=>p.did===b?.did);if(!p||b.schema!==kind||b.clusterId!==roster.clusterId||!Number.isSafeInteger(b.at)||(!historical&&Math.abs(now-b.at)>90000)||audience&&b.audience!==audience||!Number.isSafeInteger(b.sequence)||b.sequence<1||! /^[A-Za-z0-9_-]{86}$/.test(envelope.signature||'')||!verify(null,Buffer.from(JSON.stringify(b)),createPublicKey(p.publicKey),Buffer.from(envelope.signature,'base64url')))throw Error('Peer proof refused');return b;
}
