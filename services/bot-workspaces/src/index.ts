import {launch} from '@cloudflare/playwright';
import {BrowserTeamBase} from './browser.js';
export class BrowserTeam extends BrowserTeamBase {constructor(ctx:any,env:any){super(ctx,env,launch);this.runCommand=async(input:any)=>{const sandbox=getSandbox(env.Sandbox,input.workspace,{sleepAfter:'10m',enableDefaultSession:true});const result=await sandbox.exec(input.command,{timeout:20000});return {workspace:input.workspace,success:result.success,exitCode:result.exitCode,stdout:result.stdout.slice(0,16000),stderr:result.stderr.slice(0,4000),storage:'Ephemeral compute; save deliverables with workspace_file.'};};}}
import {Sandbox as BaseSandbox, getSandbox} from '@cloudflare/sandbox';
export class Sandbox extends BaseSandbox { enableInternet = false; }
export default {
 async fetch(request:Request, env:any) {
  // Private service binding only: no routes, previews or workers.dev endpoint.
  if(request.method!=='POST'||!['/execute','/browser'].includes(new URL(request.url).pathname))return new Response('Not found',{status:404});
  const input:any=await request.json();
  if(typeof input.owner!=='string'||!input.owner||input.owner.length>256)return new Response('Invalid request',{status:400});
  const hash=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(input.owner));
  const id=Array.from(new Uint8Array(hash),b=>b.toString(16).padStart(2,'0')).join('').slice(0,63);
  const command=new URL(request.url).pathname==='/execute';
  if(!command&&input.action==='command')return new Response('Unknown browser action',{status:400});
  if(command&&(typeof input.command!=='string'||input.command.length>8192))return new Response('Invalid command',{status:400});
  return env.BROWSER_TEAMS.get(env.BROWSER_TEAMS.idFromName(id)).fetch('https://browser/action',{method:'POST',body:JSON.stringify({...input,workspace:id,...(command?{action:'command'}:{})})});
 }
};
