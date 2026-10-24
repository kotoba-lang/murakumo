#!/usr/bin/env python3
"""Native signer/enrollment diagnostics. Output includes private fixture keys.
Python orchestrates processes/hashes; no hosted cryptography or application runtime.
"""
from pathlib import Path
import argparse,os,subprocess,json,hashlib,platform
p=argparse.ArgumentParser()
p.add_argument('--signer',required=True,type=Path)
p.add_argument('--node',required=True,type=Path)
p.add_argument('--output',required=True,type=Path)
a=p.parse_args()
assert platform.system()=='Linux' 
base=a.output.resolve();base.mkdir(mode=0o700,parents=True,exist_ok=False)
private=base/'signer';private.mkdir(mode=0o700)
ledger=base/'node-ledger';ledger.mkdir(mode=0o700)
public=base/'public';public.mkdir()
signer=str(a.signer.resolve());node=str(a.node.resolve())
cases=[]
def invoke(exe,args,roots,expected=0):
 env=dict(os.environ,KEXE_ALLOW='7,23,35,37,38,39',KEXE_CAP_RESOURCES_35=':'.join(str(x) for x in roots))
 p=subprocess.run([exe,*map(str,args)],env=env,capture_output=True,text=True,timeout=60)
 assert p.returncode==expected,(args,p)
 return p.stdout
info=json.loads(invoke(signer,['init-local',private,'node','node:mac-local','tsukasa:local','node-statements','60000'],[private,public]))
(public/'keyset.export').write_text(invoke(signer,['export-local',private],[private,public]))
body=invoke(node,['declaration',info['author'],'mac-local','mac-linux-vm','always-on','2'],[public,ledger])
(public/'declaration.json').write_text(body)
(public/'request').write_text(invoke(signer,['permit-local',private,public/'declaration.json','node-declaration'],[private,public]))
(public/'envelope').write_text(invoke(signer,['sign-local-line',private,public/'request',public/'declaration.json'],[private,public]))
digest=hashlib.sha256(body.encode()).hexdigest()
args=['register-local',public/'keyset.export',info['genesis'],info['genesis'],public/'envelope','mac-local','mac-linux-vm','always-on','2',digest,ledger]
for name,index,value in [('wrong operator digest',9,'0'*64),('foreign genesis',2,'0'*64),('stale keyset head',3,'0'*64),('different node scope',5,'foreign-node'),('different failure domain',6,'foreign-vm'),('unknown runtime class',8,'4')]:
 bad=args.copy();bad[index]=value;invoke(node,bad,[public,ledger],2);assert not (ledger/'enrollment').exists();cases.append({'case':name,'passed':True,'effect':False})
envelope=(public/'envelope').read_text();parts=envelope.strip().split(' ');sig=parts[-1];parts[-1]=('0' if sig[0]!='0' else '1')+sig[1:];(public/'bad-envelope').write_text(' '.join(parts)+'\n')
bad=args.copy();bad[4]=public/'bad-envelope';invoke(node,bad,[public,ledger],2);assert not (ledger/'enrollment').exists();cases.append({'case':'changed author signature','passed':True,'effect':False})
(public/'unicode-envelope').write_text('日本語 '+envelope)
bad=args.copy();bad[4]=public/'unicode-envelope';invoke(node,bad,[public,ledger],2);assert not (ledger/'enrollment').exists();cases.append({'case':'Unicode envelope refused without trap','passed':True,'effect':False})
registered=json.loads(invoke(node,args,[public,ledger]));assert registered['signed'] and registered['enrollment']=='admitted' and not registered['active'];(public/'node-status.json').write_text(json.dumps(registered,separators=(',',':'))+'\n');cases.append({'case':'native signed registration durable commit','passed':True})
original=(ledger/'enrollment').read_bytes();invoke(node,args,[public,ledger],2);assert (ledger/'enrollment').read_bytes()==original;cases.append({'case':'replay does not overwrite registration','passed':True})
status=json.loads(invoke(node,['status-local',public/'keyset.export',info['genesis'],info['genesis'],ledger],[public,ledger]));assert status['did']==registered['did'] and not status['active'];cases.append({'case':'new process restores and verifies stored enrollment','passed':True})
(ledger/'enrollment').write_text('日本語\n'+envelope)
invoke(node,['status-local',public/'keyset.export',info['genesis'],info['genesis'],ledger],[public,ledger],2)
(ledger/'enrollment').write_bytes(original)
cases.append({'case':'malformed stored header refused without trap','passed':True})
invoke(node,['watch-local',public/'keyset.export',info['genesis'],info['genesis'],ledger,public,'1'],[public,ledger])
assert json.loads((public/'node-status.json').read_text())['did']==registered['did']
cases.append({'case':'bounded watcher publishes restored enrollment','passed':True})
report={'host':platform.machine(),'signerSha256':hashlib.sha256(Path(signer).read_bytes()).hexdigest(),'nodeSha256':hashlib.sha256(Path(node).read_bytes()).hexdigest(),'passed':True,'count':len(cases),'schema':'awai.native.kagi-node-integration.v1','cases':cases,'registered':registered,'restarted':status,'nativeNodeHasNoSeedScope':True,'hataCellExecution':False,'productionQualified':False,'orchestration':'Python/Podman diagnostics; native signer, cryptographic verification and durable registration execute in Amu-built static ELF'}
(base/'receipt.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
