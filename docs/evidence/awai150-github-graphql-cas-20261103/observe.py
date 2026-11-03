# One-off synthetic GitHub test. Uses existing gh login, never reads a token.
import base64,concurrent.futures,json,subprocess,uuid
repo='cloud-itonami/itonami-bot-state';tag=uuid.uuid4().hex;branch='verification/graphql-cas-'+tag;ref='refs/heads/'+branch;path='__codex_verification__/'+tag+'.json';created=False
mutation='mutation($input:CreateCommitOnBranchInput!){createCommitOnBranch(input:$input){clientMutationId commit{oid} ref{name target{oid}}}}'
def gh(args,body=None,timeout=30):
 r=subprocess.run(['gh','api',*args],input=None if body is None else json.dumps(body),capture_output=True,text=True,timeout=timeout)
 try:data=json.loads(r.stdout) if r.stdout.strip() else None
 except json.JSONDecodeError:data=None
 return {'exit':r.returncode,'data':data,'err':r.stderr[-500:]}
def getref():return gh(['repos/'+repo+'/git/ref/heads/'+branch])
def change(label,expected):
 content=json.dumps({'kind':'synthetic-compare','label':label,'nonce':tag},sort_keys=True,separators=(',',':')).encode()
 inp={'branch':{'repositoryNameWithOwner':repo,'branchName':branch},'expectedHeadOid':expected,'message':{'headline':'Synthetic checkpoint CAS test'},'fileChanges':{'additions':[{'path':path,'contents':base64.b64encode(content).decode()}]},'clientMutationId':label+'-'+tag}
 result=gh(['graphql','--input','-'],{'query':mutation,'variables':{'input':inp}})
 return label,content,result
try:
 main=gh(['repos/'+repo+'/git/ref/heads/main']);assert main['exit']==0,main;base=main['data']['object']['sha']
 r=gh(['--method','POST','repos/'+repo+'/git/refs','--input','-'],{'ref':ref,'sha':base});assert r['exit']==0,r;created=True
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:rows=list(pool.map(lambda x:change(x,base),('a','b')))
 winners=[];fails=[]
 for label,content,res in rows:
  obj=(res['data'] or {}).get('data',{}).get('createCommitOnBranch') if isinstance(res['data'],dict) else None
  if obj and obj.get('commit',{}).get('oid'):winners.append((label,content,obj['commit']['oid']))
  else:fails.append((label,res))
 print(json.dumps({'created':True,'ref':ref,'successes':len(winners),'failures':len(fails),'failure':fails[0][1]['err'] if fails else ''}),flush=True)
 assert len(winners)==1 and len(fails)==1,(rows,)
 label,content,tip=winners[0];live=getref();assert live['exit']==0 and live['data']['object']['sha']==tip
 blob=gh(['repos/'+repo+'/contents/'+path+'?ref='+tip]);assert blob['exit']==0 and base64.b64decode(blob['data']['content'])==content
 _,_,stale=change('stale',base);assert stale['exit']!=0 or (isinstance(stale['data'],dict) and stale['data'].get('errors'))
 after=getref();assert after['exit']==0 and after['data']['object']['sha']==tip
 print(json.dumps({'winner':label,'independent_tip_matches':True,'independent_content_matches':True,'stale_refused':True,'tip_unchanged':True}),flush=True)
finally:
 if created:
  current=getref();oid=current['data']['object']['sha'] if current['exit']==0 else None
  if ('tip' in globals() and oid==tip) or (oid==base and 'rows' in globals() and all(x[2]['exit']!=0 and isinstance(x[2]['data'],dict) and x[2]['data'].get('errors') for x in rows)):
   deletion=gh(['--method','DELETE','repos/'+repo+'/git/refs/heads/'+branch]);check=getref()
   print(json.dumps({'cleanup_delete_exit':deletion['exit'],'ref_absent':check['exit']!=0 and '404' in check['err'],'ref':ref}),flush=True)
  else:print(json.dumps({'cleanup':'not-at-owned-known-tip','ref':ref,'current':oid}),flush=True)
