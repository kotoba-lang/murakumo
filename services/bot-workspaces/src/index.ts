import {Sandbox as BaseSandbox, getSandbox} from '@cloudflare/sandbox';
export class Sandbox extends BaseSandbox { enableInternet = false; }
export default {
 async fetch(request:Request, env:any) {
  // Private service binding only: no routes, previews or workers.dev endpoint.
  if(request.method!=='POST'||new URL(request.url).pathname!=='/execute')return new Response('Not found',{status:404});
  const input:any=await request.json();
  if(typeof input.owner!=='string'||!input.owner||input.owner.length>256||typeof input.command!=='string'||input.command.length>8192)return new Response('Invalid request',{status:400});
  const hash=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(input.owner));
  const id=Array.from(new Uint8Array(hash),b=>b.toString(16).padStart(2,'0')).join('').slice(0,63);
  const sandbox=getSandbox(env.Sandbox,id,{sleepAfter:'10m',enableDefaultSession:true});
  const result=await sandbox.exec(input.command,{timeout:20000});
  return Response.json({workspace:id,success:result.success,exitCode:result.exitCode,stdout:result.stdout.slice(0,16000),stderr:result.stderr.slice(0,4000),storage:'Ephemeral compute; save deliverables with workspace_file.'});
 }
};
