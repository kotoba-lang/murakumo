"""Run one preregistered condition on fleet, preserve evidence and both checkpoints."""
from archive_control import *

def run(seed,mode):
    assert json.loads((HERE/'qualification.json').read_text())['pass']
    directory=HERE/f'seed{seed}-{mode}'; directory.mkdir(exist_ok=True)
    if (directory/'COMPLETE.json').exists(): return
    admission=admit(mode); write(directory/'disk-admission.json',admission)
    base=f'repeat-v1/s{seed}/{mode}'
    rows=[]
    storage=json.loads((SCRATCH/'storage-plan-input.json').read_text())
    for phase_,save in [('prefix',True),('suffix',True),('replay',False)]:
        available=free()
        snapshots_remaining=2 if phase_=='prefix' else 1 if phase_=='suffix' else 0
        required=[snapshots_remaining*c+(w if mode=='nvme' else 0)+GIB for c,w in zip(storage['checkpoint_bytes'],storage['nvme_working_bytes'])]
        assert all(a>b for a,b in zip(available,required)), ('phase disk admission',available,required)
        write(directory/f'{phase_}-disk-admission.json',{'free_bytes':available,'required_bytes':required,'pass':True})
        x=training(f'actual-s{seed}-{mode}-{phase_}',seed,mode,base+'/'+phase_,32,seq=64,preset='3b',resume=None if phase_=='prefix' else base+'/prefix/checkpoint-32',save=save,eval_batches=8,eval_every=16,diagnostic=True)
        rows.append(x)
    pre,suf,replay=rows
    for r in range(10):
        assert pre[r]['final_state_sha256']==suf[r]['initial_state_sha256']==replay[r]['initial_state_sha256']
        assert pre[r]['final_validation_ce']==suf[r]['initial_validation_ce']==replay[r]['initial_validation_ce']
        assert suf[r]['input_sha256']==replay[r]['input_sha256']
        assert pre[r]['end_step']==suf[r]['start_step']==replay[r]['start_step']==32
        assert suf[r]['end_step']==replay[r]['end_step']==64
        for x in [pre[r],suf[r],replay[r]]:
            assert x['memory_enforcer']['kernel_limit_bytes']==9*GIB
            assert x['process_memory']['lifetime_peak_phys_footprint_bytes']<9*GIB
            assert x['global_training_tokens_counted_once']==2048
    readback=f'repeat-readback-v1-s{seed}-{mode}.json'
    phase(f'actual-s{seed}-{mode}-readback',['code/verify_repeat_artifacts.hy','--world-size','10','--prefix',base+'/prefix/checkpoint-32','--final',base+'/suffix/checkpoint-64','--output',readback],readback,result_name=readback,train=False)
    condition(seed,mode)
    entries=[e for e in catalog() if e['source'].startswith(base+'/')]
    assert len(entries)==20
    write(directory/'COMPLETE.json',{'pass':True,'seed':seed,'state_placement':mode,'primary_tokens':4096,'replay_tokens':2048,'full_generations_retained':2,'owner_archives':20,'raw_checkpoint_bytes':sum(e['raw_bytes'] for e in entries),'compressed_archive_bytes':sum(e['archive_bytes'] for e in entries),'source_initialization_checkpoint':'source revision in all result/manifest hashes'})
    print('CONDITION_COMPLETE',seed,mode,flush=True)

if __name__=='__main__':run(int(sys.argv[1]),sys.argv[2])
