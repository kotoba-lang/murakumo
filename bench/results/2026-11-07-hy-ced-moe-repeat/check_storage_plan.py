"""Capacity simulation of the real coordinator with virtual I/O, never a performance claim."""
import json, pathlib, threading, types
import archive_control as a
root=a.HERE;GiB=1024**3
inputs=json.loads((a.SCRATCH/'storage-plan-input.json').read_text())
space=[x for x in json.loads((root/'seed936-ram/disk-admission.json').read_text())['free_bytes']]
initial=sum(space);entries=[];events=[];lock=threading.Lock()
raw=inputs['checkpoint_bytes']
observed=[e for e in json.loads((root/'archive-catalog.json').read_text()) if e['source'].startswith('repeat-v1/s936/ram/')]
assert len(observed)==20
compressed=[round(sum(e['archive_bytes'] for e in observed if e['source_host']==r)/2) for r in range(10)]

def no_network(*args,**kwargs):raise AssertionError('simulation cannot access SSH')
def move(e,target):
    with lock:
        source=e['archive_host'];size=e['archive_bytes'];assert target!=source and space[target]>size+4*GiB
        space[target]-=size;space[source]+=size;e['archive_host']=target
        events.append({'kind':'relocate','source':source,'target':target,'bytes':size})

def archive(source,path,control=False,target_override=None):
    target=target_override
    with lock:
        assert space[target]>raw[source]+4*GiB
        space[target]-=compressed[source];space[source]+=raw[source]
        e={'source_host':source,'source':path,'archive_host':target,'archive_path':path.replace('repeat-v1/','repeat-archives-v1/rank'+str(source)+'/')+'.tar.gz','archive_bytes':compressed[source]}
        entries.append(e);events.append({'kind':'archive','source':source,'target':target,'bytes':compressed[source]})
        assert min(space)>=GiB
        return e

a.free=lambda:list(space);a.catalog=lambda:entries;a.move=move;a.archive_one=archive;a.remote=no_network;a.write=lambda *args,**kwargs:None;a.time=types.SimpleNamespace(sleep=lambda n:None)
rounds=[]
for seed,mode in [(936,'ram'),(936,'nvme'),(937,'nvme'),(937,'ram'),(938,'nvme'),(938,'ram')]:
    a.admit(mode)
    # Three fresh learner processes never overlap. Only both retained raw snapshots remain at archival.
    for r in range(10):space[r]-=2*raw[r]
    assert min(space)>=GiB
    a.condition(seed,mode)
    rounds.append({'seed':seed,'mode':mode,'after_archives_free_bytes':list(space),'minimum_free_bytes':min(space)})
assert len(entries)==120 and sum(space)==initial-12*sum(compressed)
# Deliberately shrink an archive-empty host: insufficient preserved capacity must refuse.
saved_space=list(space);saved_entries=list(entries)
entries.clear();space[7]=2*raw[7]+inputs['nvme_working_bytes'][7]+GiB-1
refused=False;before_refusal_space=list(space);before_refusal_events=len(events)
try:a.admit('nvme')
except AssertionError:refused=True
assert refused and space==before_refusal_space and len(events)==before_refusal_events
space[:]=saved_space;entries[:]=saved_entries
result={'capacity_deficit_refused_without_mutation':refused,'pass':True,'scope':'virtual storage admission/coordinator with live first-condition sizes; observed first-condition freecapacity only; not runtime/performance/actual future retention proof','owner_archives':len(entries),'relocations':sum(x['kind']=='relocate' for x in events),'rounds':rounds,'final_total_free_bytes':sum(space),'events':events}
(root/'qualification-storage-plan.json').write_text(json.dumps(result,indent=2))
print(json.dumps({k:v for k,v in result.items() if k not in ['rounds','events']}))
