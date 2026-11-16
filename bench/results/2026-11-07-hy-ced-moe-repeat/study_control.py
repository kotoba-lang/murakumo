"""Stdlib SSH measurement coordinator. This Mac never imports a model or MLX."""
import concurrent.futures as cf
import datetime, hashlib, json, os, pathlib, shlex, subprocess, sys, time
HERE = pathlib.Path(__file__).resolve().parent
SCRATCH = HERE.parents[2] / '.agent-output/repeat-study'
ROOT = 'github/workspaces/codex/jun-936-hy-moe'
NODES = [('joseph','josephnoMac-mini.local.','100.82.123.35'),('issachar','issacharnoMac-mini.local.','192.168.1.23'),('levi','levinoMac-mini.local.','192.168.1.26'),('simeon','simeonnoMac-mini.local.','192.168.1.24'),('dan','dannoMac-mini.local.','192.168.1.18'),('judah','judahnoMac-mini.local.','192.168.1.21'),('naphtali','naphtalinoMac-mini.local.','naphtali'),('benjamin','benjamin.local.','192.168.1.20'),('zebulun','zebulunnoMac-mini.local.','100.66.28.79'),('asher','asher.local.','100.96.122.69')]
SSH = [['ssh','-B','en8','-o','BatchMode=yes','-o','ConnectTimeout=5','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3','-o','HostName='+h,'-o','HostKeyAlias='+a,u] for u,h,a in NODES]
ENV = 'env PYTHONPATH="$PWD/site-packages:$PWD/code"'
PYTHON = './python/bin/python3.12'

def write(p,v):
    p=pathlib.Path(p); p.parent.mkdir(parents=True,exist_ok=True)
    tmp=p.with_suffix('.tmp'); tmp.write_text(json.dumps(v,indent=2)); tmp.replace(p)

def remote(rank, command, timeout=120, data=None, binary=False):
    r=subprocess.run(SSH[rank]+['cd '+ROOT+' && '+command],input=data,capture_output=True,text=not binary,timeout=timeout)
    if r.returncode: raise RuntimeError(f'rank{rank} rc{r.returncode}: {r.stderr[-2000:]} {r.stdout[-500:]}')
    return r.stdout

def parallel(fn):
    with cf.ThreadPoolExecutor(max_workers=10) as ex: return list(ex.map(fn,range(10)))

def snapshot(label):
    results=parallel(lambda r:json.loads(remote(r,ENV+' '+PYTHON+' code/repeat_snapshot.py')))
    write(HERE/'snapshots'/f'{label}.json',results)
    return results

def fetch(rank, name, destination):
    s=remote(rank,'cat '+shlex.quote(name))
    write(destination,json.loads(s)); return json.loads(s)

def stop_all(run_id):
    def stop(r):
        try:return remote(r,PYTHON+' code/repeat_supervisor.py --stop --run-id '+run_id,30)
        except Exception as e:return str(e)
    return parallel(stop)

def phase(run_id,args,output,seconds=1800,result_name=None,train=True):
    # args is rank-specific only for an explicitly restored path.
    path=HERE/'phases'/run_id
    if (path/'DONE.json').exists():
        return [json.loads((path/f'rank{r}.json').read_text()) for r in range(10)]
    assert not (path/'STARTED.json').exists(), 'interrupted phase requires explicit receipt reconciliation'
    path.mkdir(parents=True,exist_ok=True)
    write(path/'STARTED.json',{'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'args':args,'output':output,'seconds':seconds})
    snapshot(run_id+'-before')
    processes=[]; handles=[]
    for r in range(10):
        command=args[r] if isinstance(args[0],list) else args
        cmd=ENV+f' MLX_RANK={r} MLX_HOSTFILE="$PWD/ring-repeat.json" '+PYTHON+' code/repeat_supervisor.py --run-id '+run_id+' --seconds '+str(seconds)+' --receipt '+shlex.quote('repeat-receipts-v1/'+run_id+'.json')+' -- '+PYTHON+' -m hy '+shlex.join(command)
        cmd+=' >'+shlex.quote('repeat-log-'+run_id+'.txt')+' 2>&1'
        h=open(SCRATCH/(run_id+f'-ssh-rank{r}.txt'),'w'); handles.append(h)
        processes.append(subprocess.Popen(SSH[r]+['cd '+ROOT+' && mkdir -p repeat-receipts-v1 && '+cmd],stdout=h,stderr=h))
    start=time.monotonic(); last=-100
    try:
        while any(p.poll() is None for p in processes):
            if any(p.returncode not in (None,0) for p in processes): raise RuntimeError('peer SSH/job failure')
            if time.monotonic()-start>seconds+45: raise RuntimeError('coordinator deadline')
            if time.monotonic()-last>=25:
                status={'phase':run_id,'elapsed_seconds':round(time.monotonic()-start,1),'completed':sum(p.poll()==0 for p in processes)}
                print(json.dumps(status),flush=True); write(SCRATCH/'progress.json',status); last=time.monotonic()
            time.sleep(1)
        assert all(p.returncode==0 for p in processes)
    except BaseException:
        write(path/'STOP.json',{'stop_results':stop_all(run_id)})
        for p in processes:
            try:p.wait(timeout=15)
            except subprocess.TimeoutExpired:p.terminate(); p.wait(timeout=5)
        raise
    finally:
        for h in handles:h.close()
    receipts=parallel(lambda r:fetch(r,'repeat-receipts-v1/'+run_id+'.json',path/f'rank{r}-supervisor.json'))
    assert all(x['exit_status']==0 and x['child_reaped'] and not x['timed_out'] for x in receipts)
    names=[result_name]*10 if result_name else [(output[r] if isinstance(output,list) else output)+'/result.json' for r in range(10)]
    results=parallel(lambda r:fetch(r,names[r],path/f'rank{r}.json'))
    snapshot(run_id+'-after')
    write(path/'DONE.json',{'pass':True,'max_supervisor_seconds':max(x['wall_seconds'] for x in receipts)})
    if train:
        assert all(x['status']=='complete' and x['rank']==r for r,x in enumerate(results))
    return results

def training(run_id,seed,mode,out,steps,seq=16,preset='tiny',resume=None,save=True,eval_batches=2,eval_every=2,diagnostic=False):
    def argv(r):
        a=['code/repeat_train.hy','--world-size','10','--preset',preset,'--seed',str(seed),'--state-placement',mode,'--data','data','--output',out,'--steps',str(steps),'--seq',str(seq),'--memory-limit-gib','10' if preset=='3b' else '2','--lr','0.0003' if preset=='3b' else '0.0001','--eval-batches',str(eval_batches),'--eval-every',str(eval_every)]
        if resume:a+=['--resume',resume[r] if isinstance(resume,list) else resume]
        if not save:a+=['--no-save']
        if diagnostic:a+=['--diagnostic-eval']
        return a
    return phase(run_id,[argv(r) for r in range(10)],out)

if __name__=='__main__':
    SCRATCH.mkdir(parents=True,exist_ok=True)
    if sys.argv[1]=='qualify':
        for seed in [936,938]:
            paired=[]
            for mode in ['ram','nvme']:
                paired.append(training(f'qual-s{seed}-{mode}',seed,mode,f'repeat-control-v1/s{seed}/{mode}/seed-probe',2,save=False))
            assert all(a['initial_state_sha256']==b['initial_state_sha256'] and a['input_sha256']==b['input_sha256'] for a,b in zip(*paired))
        for mode in ['ram','nvme']:
            base=f'repeat-control-v1/s937/{mode}'
            pre=training('qual-'+mode+'-prefix',937,mode,base+'/tiny-prefix',2)
            suf=training('qual-'+mode+'-suffix',937,mode,base+'/tiny-suffix',2,resume=base+'/tiny-prefix/checkpoint-2')
            full=training('qual-'+mode+'-continuous',937,mode,base+'/tiny-continuous',4)
            assert all(a['final_state_sha256']==b['initial_state_sha256'] for a,b in zip(pre,suf))
            phase('qual-'+mode+'-check',['code/check_repeat_checkpoint.hy','--state-placement',mode,'--output',base+'/checks'],base+'/checks',train=False)
        training('qual-archive-prefix',937,'ram','repeat-control-v1/s937/ram/prefix',32,eval_every=16)
        print('QUALIFIED_TRAINING_CONTROLS',flush=True)
