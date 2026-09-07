// Browser credentials stay in this private owner-scoped Durable Object, never in the shell.
export function publicUrl(value) {
 const u=new URL(value);
 if(u.protocol!=='https:'||u.username||u.password||u.port&&u.port!=='443'||!u.hostname.includes('.')||/^[\d.]+$/.test(u.hostname)||u.hostname.includes(':')||/(^|\.)(localhost|local|internal|test|invalid)$/.test(u.hostname))throw new Error('Use a public HTTPS address');
 return u.href;
}
export class BrowserTeamBase {
 constructor(ctx,env,launch){this.ctx=ctx;this.env=env;this.launch=launch;this.queue=Promise.resolve();}
 fetch(request){const job=this.queue.then(()=>this.handle(request));this.queue=job.catch(()=>{});return job;}
 async ready(){
  if(this.browser?.isConnected())return;
  this.browser=await this.launch(this.env.BROWSER,{keep_alive:600000});
  const count=await this.ctx.storage.get('parts')||0;let state='';for(let i=0;i<count;i++)state+=await this.ctx.storage.get('state'+i)||'';
  this.context=await this.browser.newContext({viewport:{width:1280,height:800},...(state?{storageState:JSON.parse(state)}:{})});
  await this.context.route('**/*',async route=>{try{publicUrl(route.request().url());await route.fallback();}catch{await route.abort();}});
  this.page=await this.context.newPage();this.page.setDefaultTimeout(10000);
  const url=await this.ctx.storage.get('url');if(url)await this.page.goto(publicUrl(url),{waitUntil:'domcontentloaded',timeout:20000}).catch(()=>{});
 }
 async save(){
  const state=JSON.stringify(await this.context.storageState({indexedDB:true}));
  if(state.length>2000000)throw new Error('Browser session storage is full');
  const oldParts=await this.ctx.storage.get('parts')||0;const parts=Math.ceil(state.length/50000);for(let i=0;i<parts;i++)await this.ctx.storage.put('state'+i,state.slice(i*50000,(i+1)*50000));
  for(let i=parts;i<oldParts;i++)await this.ctx.storage.delete('state'+i);await this.ctx.storage.put('parts',parts);if(this.page.url().startsWith('https:'))await this.ctx.storage.put('url',this.page.url());
  await this.ctx.storage.setAlarm(Date.now()+600000);
 }
 async alarm(){await this.queue;try{if(this.browser?.isConnected())await this.save();}finally{await this.browser?.close();this.browser=null;await this.ctx.storage.deleteAlarm();}}
 async handle(request){
  let attempted=false;
  try{
   const a=await request.json();const hour=Math.floor(Date.now()/3600000);const usage=await this.ctx.storage.get('usage')||{hour,count:0};if(usage.hour!==hour){usage.hour=hour;usage.count=0;}if(usage.count>=120)return Response.json({error:'Hourly workspace action allowance reached'},{status:429});usage.count++;await this.ctx.storage.put('usage',usage);if(a.action==='command'&&this.runCommand)return Response.json(await this.runCommand(a));const revision=await this.ctx.storage.get('revision')||0;
   if(!['snapshot','navigate','click','type','press','scroll','close','reset'].includes(a.action))return Response.json({error:'Unknown browser action'},{status:400});
   if(a.action!=='snapshot'&&a.revision!==revision)return Response.json({error:'Browser changed. Refresh the screen before acting.'},{status:409});
   if(a.action==='reset'){await this.browser?.close();this.browser=null;await this.ctx.storage.deleteAll();await this.ctx.storage.put('usage',usage);await this.ctx.storage.put('revision',revision+1);return Response.json({revision:revision+1,reset:true});}
   if(a.action==='navigate')publicUrl(a.url);
   await this.ready();
   if(a.action!=='snapshot'){attempted=true;await this.ctx.storage.put('revision',revision+1);}
   if(a.action==='navigate')await this.page.goto(a.url,{waitUntil:'domcontentloaded',timeout:20000});
   if(a.action==='click'){if(!Number.isFinite(a.x)||!Number.isFinite(a.y)||a.x<0||a.x>1280||a.y<0||a.y>800)throw new Error('Invalid coordinates');await this.page.mouse.click(a.x,a.y);}
   if(a.action==='type'){if(typeof a.text!=='string'||a.text.length>8192)throw new Error('Text is too long');await this.page.keyboard.insertText(a.text);}
   if(a.action==='press'){if(!['Enter','Tab','Escape','Backspace','ArrowDown','ArrowUp'].includes(a.key))throw new Error('Unsupported key');await this.page.keyboard.press(a.key);}
   if(a.action==='scroll')await this.page.mouse.wheel(0,a.direction==='up'?-600:600);
   // New tabs share the same context; show the most recently opened page.
   const pages=this.context.pages();if(pages.length)this.page=pages.at(-1);
   await this.save();
   if(a.action==='close'){await this.browser.close();this.browser=null;return Response.json({revision:revision+1,closed:true});}
   const result={revision:attempted?revision+1:revision,url:this.page.url(),title:await this.page.title(),text:(await this.page.locator('body').innerText().catch(()=>'' )).slice(0,16000),image:'data:image/jpeg;base64,'+(await this.page.screenshot({type:'jpeg',quality:65})).toString('base64')};
   return Response.json(result,{headers:{'cache-control':'no-store'}});
  }catch(error){return Response.json({error:attempted?'Browser action did not complete reliably. Refresh and inspect before retrying.':'Browser could not open. Check the address or retry shortly.'},{status:502});}
 }
}
