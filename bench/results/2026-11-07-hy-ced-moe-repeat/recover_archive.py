"""Reconcile only this study's timed-out archive; never rerun learners."""
from archive_control import *
seed=int(sys.argv[1]);mode=sys.argv[2];base=f'repeat-v1/s{seed}/{mode}'
assert json.loads((HERE/'qualification-partial-recovery.json').read_text())['pass']
# Confirm every original transfer process has exited before opening any partial.
code=r"import json,pathlib,subprocess,re; rows=subprocess.check_output(['ps','-axo','pid,args'],text=True).splitlines(); active=[l for l in rows if re.match(r'^\s*\d+\s+(?:\S*/)?python3\.12\s+code/repeat_archive\.py\s+(?:stream|receive)\s',l)]; print(json.dumps({'active':active,'partials':[str(p) for p in pathlib.Path('repeat-archives-v1').glob('rank*/s%s/%s/*.partial')]}))"%(seed,mode)
audit=parallel(lambda r:json.loads(remote(r,PYTHON+' -c '+shlex.quote(code))))
assert all(not x['active'] for x in audit),audit
write(HERE/f'seed{seed}-{mode}/archive-interruption-audit.json',audit)
prepared=[]
for host,row in enumerate(audit):
    for partial in row['partials']:
        archive=partial[:-len('.partial')];rank=int(archive.split('/')[1][4:]); step=int(archive.split('/')[-1].split('.')[0].split('-')[-1]);phase_='prefix' if step==32 else 'suffix';source=f'{base}/{phase_}/checkpoint-{step}'
        proof=json.loads(remote(rank,PYTHON+' code/repeat_archive.py index '+source,180))
        receipt='repeat-receipts-v1/retry-'+hashlib.sha256(archive.encode()).hexdigest()[:16]+'.json'
        receipt_on(host,receipt,{'proof':proof})
        r=json.loads(remote(host,PYTHON+' code/repeat_archive.py prepare-retry '+archive+' --receipt '+receipt,180))
        if r.get('complete_partial_recovered'):
            full=r['receipt'];full['archive_host']=host
            receipt_on(rank,receipt,full)
            retired=json.loads(remote(rank,PYTHON+' code/repeat_archive.py retire '+source+' --receipt '+receipt,180))
            remember({'source_host':rank,'source':source,'archive_host':host,'archive_path':archive,'archive_sha256':full['archive_sha256'],'archive_bytes':full['archive_bytes'],'raw_bytes':proof['raw_bytes'],'global_manifest_sha256':proof['global_manifest_sha256'],'files':len(proof['files']),'receipt':full,'retired':retired})
        prepared.append({'host':host,'archive':archive,'source_host':rank,'source':source,'result':r})
write(HERE/f'seed{seed}-{mode}/archive-recovery-prepared.json',prepared)
condition(seed,mode)
retired=[]
for p in prepared:
    if not p['result'].get('incomplete_preserved'): continue
    e=next(e for e in catalog() if e['source']==p['source'] and e['source_host']==p['source_host'])
    full=json.loads(remote(e['archive_host'],PYTHON+' code/repeat_archive.py verify '+e['archive_path'],180));assert full['archive_sha256']==e['archive_sha256']
    full['archive_path']=e['archive_path'];full['archive_host']=e['archive_host']
    receipt='repeat-receipts-v1/retire-partial-'+hashlib.sha256(p['archive'].encode()).hexdigest()[:16]+'.json'
    receipt_on(p['host'],receipt,full)
    retired.append(json.loads(remote(p['host'],PYTHON+' code/repeat_archive.py retire-partial '+p['archive']+' --receipt '+receipt,180)))
write(HERE/f'seed{seed}-{mode}/archive-interrupted-retired.json',retired)
entries=[e for e in catalog() if e['source'].startswith(base+'/')];assert len(entries)==20
write(HERE/f'seed{seed}-{mode}/COMPLETE.json',{'pass':True,'seed':seed,'state_placement':mode,'primary_tokens':4096,'replay_tokens':2048,'full_generations_retained':2,'owner_archives':20,'raw_checkpoint_bytes':sum(e['raw_bytes'] for e in entries),'compressed_archive_bytes':sum(e['archive_bytes'] for e in entries),'interrupted_transfer_reconciled':True,'learner_retries':0})
print('ARCHIVE_RECOVERED_CONDITION_COMPLETE',seed,mode,flush=True)
