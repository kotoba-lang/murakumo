#!/usr/bin/env node
import {readFileSync,writeFileSync,mkdirSync,chmodSync,existsSync,rmSync,cpSync,renameSync,lstatSync,realpathSync} from 'node:fs';
import {join,resolve,dirname,basename} from 'node:path';import {homedir,platform,arch} from 'node:os';import {spawnSync} from 'node:child_process';import {createHash,randomBytes} from 'node:crypto';import {fileURLToPath} from 'node:url';
import {validateConfig,ROOT} from './client.mjs';
const label='com.murakumo.native-client';
export function plist(program,args,log){const escape=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');return '<?xml version="1.0"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>'+escape(log.label)+'</string><key>ProgramArguments</key><array>'+[program,...args].map(a=>'<string>'+escape(a)+'</string>').join('')+'</array><key>LimitLoadToSessionType</key><array><string>Aqua</string><string>Background</string></array><key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>ThrottleInterval</key><integer>15</integer><key>StandardOutPath</key><string>'+escape(log.path)+'</string><key>StandardErrorPath</key><string>'+escape(log.path)+'</string></dict></plist>';}
export function install({source,root=ROOT,engineURL,backend,model,python,mlxModel,activate=true}){
 if(Number(process.versions.node.split('.')[0])<22)throw Error('Node.js 22+ required');
 if(platform()!=='darwin'||arch()!=='arm64')throw Error('Apple Silicon macOS required');
 const uid=process.getuid();
 const domainProbe=spawnSync('/bin/launchctl',['print','gui/'+uid],{stdio:'ignore'});
 const domain=(domainProbe.status===0?'gui/':'user/')+uid;
 if(backend==='mlx-metal'&&(model!==basename(mlxModel||'')||new URL(engineURL).hostname!=='127.0.0.1'))throw Error('Managed MLX requires matching checkpoint name and IPv4 loopback');
 if(!activate){
  const m=JSON.parse(readFileSync(join(source,'manifest.json')));
  if(m.schema!=='murakumo.native-package.v1'||!/^[a-f0-9]{16}$/.test(m.version))throw Error('Invalid package');
  for(const [name,hash] of Object.entries(m.files)){if(name.includes('..')||name.startsWith('/')||createHash('sha256').update(readFileSync(join(source,name))).digest('hex')!==hash)throw Error('Package verification failed');}
  validateConfig({engineURL,backend,model,port:19840});
  if(backend==='mlx-metal'&&(!python||!mlxModel||!existsSync(python)||!existsSync(mlxModel)))throw Error('MLX environment not ready');
  return {dryRun:true,noWrites:true,root,version:m.version,launchDomain:domain,backend,model};
 }
 if(existsSync(join(root,'config.json'))&&JSON.parse(readFileSync(join(root,'config.json'))).schema!=='murakumo.native-config.v1')throw Error('Unknown existing configuration preserved');
 mkdirSync(root,{recursive:true,mode:0o700});if(lstatSync(root).isSymbolicLink()||lstatSync(root).uid!==uid)throw Error('Unsafe install root');chmodSync(root,0o700);
 const manifest=JSON.parse(readFileSync(join(source,'manifest.json')));
 if(manifest.schema!=='murakumo.native-package.v1'||!/^[a-f0-9]{16}$/.test(manifest.version))throw Error('Invalid native package manifest');
 for(const [name,hash] of Object.entries(manifest.files)){if(name.includes('..')||name.startsWith('/'))throw Error('Unsafe package path');if(createHash('sha256').update(readFileSync(join(source,name))).digest('hex')!==hash)throw Error('Package digest mismatch: '+name);}
 const release=join(root,'releases',manifest.version);mkdirSync(release,{recursive:true,mode:0o700});
 for(const name of Object.keys(manifest.files)){mkdirSync(dirname(join(release,name)),{recursive:true});cpSync(join(source,name),join(release,name));}
 const credentials=join(homedir(),'.local/share/murakumo-native');mkdirSync(credentials,{recursive:true,mode:0o700});const tokenFile=join(credentials,'client.token');
 if(!existsSync(tokenFile))writeFileSync(tokenFile,randomBytes(32).toString('base64url')+'\n',{mode:0o600,flag:'wx'});
 if(!/^[A-Za-z0-9_-]{43}$/.test(readFileSync(tokenFile,'utf8').trim()))throw Error('Malformed existing token preserved; repair credentials explicitly');
 const ts=lstatSync(tokenFile);if(ts.isSymbolicLink()||ts.uid!==uid||(ts.mode&0o077))throw Error('Unsafe existing token preserved');
 const c=validateConfig({schema:'murakumo.native-config.v1',version:manifest.version,backend,engineURL,model,port:19840,tokenFile,runtime:join(release,'runtime'),...(backend==='mlx-metal'?{engineTokenFile:tokenFile}:{})});
 if(backend==='mlx-metal'&&(!python||!mlxModel||!existsSync(python)||!existsSync(mlxModel)))throw Error('Existing MLX Python and local model required');
 // Frozen Kotoba Node CLI uses pinned dependencies; no identity or account rewrite.
 const runtime=join(release,'runtime');const npm=join(dirname(process.execPath),'npm');
 const deps=spawnSync(existsSync(npm)?npm:'npm',['ci','--ignore-scripts','--omit=dev','--prefix',runtime],{encoding:'utf8',timeout:120000});if(deps.status!==0)throw Error('Pinned CLI dependency installation failed');
 const config=join(root,'config.json');writeFileSync(config+'.part',JSON.stringify(c)+'\n',{mode:0o600});renameSync(config+'.part',config);
 const logs=join(root,'logs'),agents=join(homedir(),'Library/LaunchAgents');mkdirSync(logs,{recursive:true});mkdirSync(agents,{recursive:true});
 const run=(args,required=true)=>{const r=spawnSync('/bin/launchctl',args,{encoding:'utf8'});if(required&&r.status!==0)throw Error('launchctl '+args[0]+' failed: '+r.stderr.trim());};
 if(backend!=='mlx-metal'){run(['bootout',domain+'/'+label+'.mlx'],false);rmSync(join(agents,label+'.mlx.plist'),{force:true});}
 const jobs=[];
 if(backend==='mlx-metal')jobs.push({label:label+'.mlx',program:python,args:[join(release,'mlx-engine.py'),'--model',mlxModel,'--port',new URL(engineURL).port,'--token-file',tokenFile]});
 jobs.push({label,program:process.execPath,args:[join(release,'client.mjs'),'serve','--root',root]});
 for(const job of jobs){const file=join(agents,job.label+'.plist');writeFileSync(file,plist(job.program,job.args,{label:job.label,path:join(logs,job.label+'.log')}));if(activate){run(['bootout',domain+'/'+job.label],false);run(['bootstrap',domain,file]);}}
 if(existsSync(join(release,'app/MurakumoClient'))){
  const app=join(homedir(),'Applications/Murakumo Client.app/Contents');
  if(existsSync(join(app,'Info.plist'))&&!readFileSync(join(app,'Info.plist'),'utf8').includes('cloud.murakumo.native-client'))throw Error('Unrelated existing application preserved');
  mkdirSync(join(app,'MacOS'),{recursive:true});
  cpSync(join(release,'app/MurakumoClient'),join(app,'MacOS/MurakumoClient'));chmodSync(join(app,'MacOS/MurakumoClient'),0o755);
  writeFileSync(join(app,'Info.plist'),'<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>cloud.murakumo.native-client</string><key>CFBundleExecutable</key><string>MurakumoClient</string><key>CFBundleName</key><string>Murakumo Client</string><key>CFBundlePackageType</key><string>APPL</string><key>LSUIElement</key><true/></dict></plist>');
 }
 const bin=join(homedir(),'.local/bin');mkdirSync(bin,{recursive:true});
 const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
 writeFileSync(join(bin,'murakumo-native'),'#!/bin/sh\nexec '+quote(process.execPath)+' '+quote(join(release,'client.mjs'))+' "$@"\n',{mode:0o755});chmodSync(join(bin,'murakumo-native'),0o755);
 // The existing murakumo CLI remains untouched. Use native node ... for shared identity flow.
 return {root,version:manifest.version,launchDomain:domain,labels:jobs.map(j=>j.label),model,backend};
}
if(process.argv[1]&&realpathSync(process.argv[1])===realpathSync(fileURLToPath(import.meta.url))){
 try{const args=process.argv.slice(2);const get=k=>{const i=args.indexOf(k);return i<0?undefined:args[i+1];};console.log(JSON.stringify(install({source:dirname(fileURLToPath(import.meta.url)),engineURL:get('--engine-url'),backend:get('--backend'),model:get('--model'),python:get('--python'),mlxModel:get('--mlx-model'),activate:!args.includes('--dry-run')})));}catch(e){console.error(e.message);process.exitCode=1;}
}
