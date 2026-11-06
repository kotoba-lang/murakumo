"""One-shot fleet archive protocol; all binary bytes stay on trusted hosts or pipe."""
from study_control import *
import threading
GIB=1024**3
CATALOG=HERE/'archive-catalog.json'
LOCK=threading.Lock()

def free(): return parallel(lambda r:int(remote(r,PYTHON+' -c '+shlex.quote("import shutil; print(shutil.disk_usage('.').free)"))))
def catalog(): return json.loads(CATALOG.read_text()) if CATALOG.exists() else []
def remember(entry):
    with LOCK:
        a=catalog(); a.append(entry); write(CATALOG,a)

def receipt_on(rank,name,value):
    remote(rank,PYTHON+' -c '+shlex.quote("import pathlib,sys; p=pathlib.Path(sys.argv[1]); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(sys.stdin.read())")+' '+shlex.quote(name),data=json.dumps(value))

def transfer(source,target,path,archive,raw=True):
    # stdout is binary only, stderr is a bounded stream receipt; no model tensor on manager.
    sourcecmd=(PYTHON+' code/repeat_archive.py stream '+shlex.quote(path)) if raw else ('cat '+shlex.quote(path))
    stderrpath=SCRATCH/('archive-stream-'+hashlib.sha256((str(source)+path+str(target)).encode()).hexdigest()[:16]+'.txt')
    if source==target:
        assert raw
        streamlog='repeat-receipts-v1/stream-'+hashlib.sha256(path.encode()).hexdigest()[:16]+'.txt'
        pipe=sourcecmd+' 2>'+shlex.quote(streamlog)+' | '+PYTHON+' code/repeat_archive.py receive '+shlex.quote(archive)+' --reserve '+str(GIB)
        out=remote(source,'/bin/bash -o pipefail -c '+shlex.quote(pipe),600)
        r=json.loads(out); stream=json.loads(remote(source,'cat '+shlex.quote(streamlog)).splitlines()[-1])
        assert r['pass'] and stream['stream_pass'] and stream['archive_sha256']==r['archive_sha256'] and stream['archive_bytes']==r['archive_bytes']
        r.update({'source_stream_receipt':stream,'archive_host':target,'source_host':source,'transport':'same-host-pipe; binary never crosses manager'})
        return r
    with open(stderrpath,'wb') as err:
        producer=subprocess.Popen(SSH[source]+['cd '+ROOT+' && '+sourcecmd],stdout=subprocess.PIPE,stderr=err)
        consumer=subprocess.Popen(SSH[target]+['cd '+ROOT+' && '+PYTHON+' code/repeat_archive.py receive '+shlex.quote(archive)+' --reserve '+str(GIB)],stdin=producer.stdout,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        producer.stdout.close()
        try:
            # Drain receipts while waiting: a large manifest must not fill stdout.
            out,error=consumer.communicate(timeout=600)
            pc=producer.wait(timeout=15)
        except subprocess.TimeoutExpired:
            consumer.terminate(); producer.terminate()
            consumer.communicate(timeout=10); producer.wait(timeout=10)
            raise RuntimeError('archive transfer deadline')
    if consumer.returncode or pc: raise RuntimeError(f'archive transfer {pc}/{consumer.returncode}: '+error.decode()[-2000:]+stderrpath.read_text()[-1000:])
    r=json.loads(out); assert r['pass']
    if raw:
        stream=json.loads(stderrpath.read_text().splitlines()[-1])
        assert stream['stream_pass'] and stream['archive_sha256']==r['archive_sha256'] and stream['archive_bytes']==r['archive_bytes']
        r['source_stream_receipt']=stream
    r['archive_host']=target; r['source_host']=source
    return r

def archive_one(source,path,control=False,target_override=None):
    proof=json.loads(remote(source,PYTHON+' code/repeat_archive.py index '+shlex.quote(path),180))
    f=free(); candidates=[r for r in range(10) if f[r]>proof['raw_bytes']+4*GIB]
    assert candidates, ('no lossless archive destination',f,proof['raw_bytes'])
    # Greedy balance after-source-retirement free capacity, reserving future working space.
    target=target_override if target_override is not None else (source if source in candidates else max(candidates,key=lambda r:f[r]))
    assert target in candidates
    parts=path.split('/'); archive=f'repeat-archives-v1/{"control/" if control else ""}rank{source}/{parts[-4]}/{parts[-3]}/{parts[-1]}.tar.gz'
    r=transfer(source,target,path,archive)
    assert r['proof']==proof
    receipt='repeat-receipts-v1/archive-'+hashlib.sha256(path.encode()).hexdigest()[:16]+'.json'
    receipt_on(source,receipt,r)
    retired=json.loads(remote(source,PYTHON+' code/repeat_archive.py retire '+shlex.quote(path)+' --receipt '+receipt,180))
    assert retired['pass']
    entry={'source_host':source,'source':path,'archive_host':target,'archive_path':archive,'archive_sha256':r['archive_sha256'],'archive_bytes':r['archive_bytes'],'raw_bytes':proof['raw_bytes'],'global_manifest_sha256':proof['global_manifest_sha256'],'files':len(proof['files']),'receipt':r,'retired':retired}
    remember(entry); print(json.dumps({k:v for k,v in entry.items() if k not in ['receipt']}),flush=True)
    return entry

def move(entry,target):
    old=entry['archive_host']; path=entry['archive_path']
    r=transfer(old,target,path,path,False)
    assert r['archive_sha256']==entry['archive_sha256'] and r['archive_bytes']==entry['archive_bytes'] and r['proof']==entry['receipt']['proof']
    r['retired_host']=old
    receipt='repeat-receipts-v1/move-'+hashlib.sha256((str(old)+str(target)+path).encode()).hexdigest()[:16]+'.json'
    receipt_on(old,receipt,r)
    retired=json.loads(remote(old,PYTHON+' code/repeat_archive.py retire-archive '+shlex.quote(path)+' --receipt '+receipt,180))
    assert retired['pass']
    entries=catalog()
    for e in entries:
        if e['source']==entry['source'] and e['source_host']==entry['source_host']:
            e.setdefault('moves',[]).append({'from':old,'to':target,'receipt':r,'retired':retired}); e['archive_host']=target
    write(CATALOG,entries)

def admit(mode):
    qualify_relocation()
    inputs=json.loads((SCRATCH/'storage-plan-input.json').read_text())
    needed=[2*x+(y if mode=='nvme' else 0)+GIB for x,y in zip(inputs['checkpoint_bytes'],inputs['nvme_working_bytes'])]
    for _ in range(100):
        f=free(); tight=next((r for r in range(10) if f[r]<needed[r]),None)
        if tight is None:
            record={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mode':mode,'free_bytes':f,'required_bytes':needed,'pass':True}; write(HERE/'last-disk-admission.json',record); return record
        candidates=[e for e in catalog() if e['archive_host']==tight and '/control/' not in e['archive_path']]
        assert candidates, ('insufficient local transient space without touching older data',tight,f,needed)
        e=max(candidates,key=lambda e:e['archive_bytes'])
        destinations=[r for r in range(10) if r!=tight and f[r]>needed[r]+e['archive_bytes']+GIB]
        assert destinations, ('no safe relocation target',f,needed)
        move(e,max(destinations,key=lambda r:f[r]-needed[r]))
    raise RuntimeError('disk admission relocation bound')

def qualify_relocation():
    if not (HERE/'qualification-relocation.json').exists():
        path='repeat-archives-v1/control/rank0/s937/ram/checkpoint-32.tar.gz'
        r=transfer(0,1,path,path,False)
        original=json.loads((HERE/'qualification-archives.json').read_text())[0]['receipt']
        assert r['archive_sha256']==original['archive_sha256'] and r['proof']==original['proof']
        r['retired_host']=0
        receipt='repeat-receipts-v1/qual-relocation.json'; receipt_on(0,receipt,r)
        retired=json.loads(remote(0,PYTHON+' code/repeat_archive.py retire-archive '+path+' --receipt '+receipt))
        assert retired['pass']
        write(HERE/'qualification-relocation.json',{'pass':True,'from_host':0,'to_host':1,'receipt':r,'retired_redundant_archive':retired})

def condition(seed,mode):
    qualify_relocation()
    for step,phase_ in [(32,'prefix'),(64,'suffix')]:
        f=free(); assignments=[]
        for r in range(10):
            path=f'repeat-v1/s{seed}/{mode}/{phase_}/checkpoint-{step}'
            existing=[e for e in catalog() if e['source']==path and e['source_host']==r]
            if existing:
                assert len(existing)==1
                e=existing[0]
                checked=json.loads(remote(e['archive_host'],PYTHON+' code/repeat_archive.py verify '+e['archive_path'],180))
                assert checked['archive_sha256']==e['archive_sha256'] and checked['proof']==e['receipt']['proof']
                continue
            bound=json.loads((SCRATCH/'storage-plan-input.json').read_text())['checkpoint_bytes'][r]+2*1024*1024
            eligible=[x for x in range(10) if f[x]>bound+4*GIB]
            assert eligible
            target=r if r in eligible else max(eligible,key=lambda x:f[x])
            f[target]-=bound; assignments.append((r,path,target))
        with cf.ThreadPoolExecutor(max_workers=10) as ex:
            futures=[ex.submit(archive_one,r,path,False,target) for r,path,target in assignments]
            while not all(p.done() for p in futures):
                for p in futures:
                    if p.done() and p.exception(): raise p.exception()
                print(json.dumps({'archiving_seed':seed,'mode':mode,'step':step,'completed':sum(p.done() for p in futures)}),flush=True)
                time.sleep(20)
            for p in futures:p.result()

if __name__=='__main__':
    if sys.argv[1]=='condition':
        seed=int(sys.argv[2]); mode=sys.argv[3]
        condition(seed,mode)
    elif sys.argv[1]=='admit': print(json.dumps(admit(sys.argv[2])))
