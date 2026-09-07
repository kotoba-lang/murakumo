import assert from 'node:assert/strict';
import {BrowserTeamBase,publicUrl} from '../src/browser.js';
import {chromium} from 'playwright';
for(const url of ['http://example.com','https://127.0.0.1','https://[::1]','https://localhost','https://x.internal','https://a:b@example.com'])assert.throws(()=>publicUrl(url));
const html=`<html><title>Session fixture</title><body><input aria-label="Name" style="position:absolute;left:10px;top:10px;width:200px;height:40px"><button style="position:absolute;left:10px;top:80px" onclick="document.cookie='login=fixture;Secure;SameSite=Lax';localStorage.setItem('login','fixture');document.getElementById('result').textContent='Logged in '+document.querySelector('input').value">Login</button><p id="result" style="margin-top:150px"></p><script>document.getElementById('result').textContent=localStorage.getItem('login')?'Restored login':'Signed out'</script></body></html>`;
const launch=async()=>{const b=await chromium.launch({...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{}),headless:true});const original=b.newContext.bind(b);b.newContext=async options=>{const c=await original(options);await c.route('https://fixture.example/**',r=>r.fulfill({contentType:'text/html',body:html}));return c;};return b;};
function instance(){const data=new Map();const storage={get:async k=>data.get(k),put:async(k,v)=>data.set(k,v),delete:async k=>data.delete(k),deleteAll:async()=>data.clear(),setAlarm:async()=>{},deleteAlarm:async()=>{}};return {storage,team:new BrowserTeamBase({storage},{},launch)};}
const a=instance(),b=instance();let revision=0;
async function call(team,input,status=200){const r=await team.fetch(new Request('https://private/action',{method:'POST',body:JSON.stringify(input)}));const d=await r.json();assert.equal(r.status,status,JSON.stringify(d));return d;}
try{
 let d=await call(a.team,{action:'navigate',url:'https://fixture.example',revision:0});revision=d.revision;assert.match(d.text,/Signed out/);assert.match(d.image,/^data:image\/jpeg;base64,/);
 d=await call(a.team,{action:'click',x:40,y:30,revision});revision=d.revision;
 d=await call(a.team,{action:'type',text:'日本語テスト',revision});revision=d.revision;
 d=await call(a.team,{action:'click',x:35,y:90,revision});revision=d.revision;assert.match(d.text,/Logged in 日本語テスト/);
 await call(a.team,{action:'click',x:35,y:90,revision:0},409);
 const shared=await call(a.team,{action:'snapshot'});assert.match(shared.text,/Logged in/);
 const other=await call(b.team,{action:'navigate',url:'https://fixture.example',revision:0});assert.match(other.text,/Signed out/);
 d=await call(a.team,{action:'close',revision});revision=d.revision;
 a.team=new BrowserTeamBase({storage:a.storage},{},launch);d=await call(a.team,{action:'snapshot'});assert.match(d.text,/Restored login/);
 assert.equal((await a.team.context.cookies()).find(c=>c.name==='login').value,'fixture');
 d=await call(a.team,{action:'reset',revision});assert.equal(d.reset,true);
 console.log('PASS: real Chrome screenshot, Japanese typing, click, same-owner session, cross-owner isolation, stale action, persisted cookies/localStorage and reset');
}finally{await a.team.browser?.close();await b.team.browser?.close();}
