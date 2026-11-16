"""Fleet-only archive restore, damaged archive refusal, and owned-process stop controls."""
from archive_control import *

proofs=[]
for r in range(10):
    source='repeat-control-v1/s937/ram/prefix/checkpoint-32'
    archive=f'repeat-archives-v1/control/rank{r}/s937/ram/checkpoint-32.tar.gz'
    receipt=transfer(r,r,source,archive)
    dest=f'repeat-restored-v1/control/rank{r}/s937/ram/checkpoint-32'
    restored=json.loads(remote(r,PYTHON+' code/repeat_archive.py restore '+archive+' --destination '+dest,120))
    assert restored['pass'] and restored['proof']==receipt['proof']
    # Full gzip CRC/footer refusal; primary valid archive remains unchanged.
    code="import pathlib,subprocess,sys; p=pathlib.Path(sys.argv[1]); bad=p.with_name('truncated-control.gz'); bad.write_bytes(p.read_bytes()[:-8]); r=subprocess.run([sys.executable,'code/repeat_archive.py','verify',str(bad)],capture_output=True,text=True); assert r.returncode!=0; print(r.stderr.splitlines()[-1])"
    refusal=remote(r,PYTHON+' -c '+shlex.quote(code)+' '+archive)
    localreceipt='repeat-receipts-v1/qual-archive.json'; receipt['archive_host']=r
    receipt_on(r,localreceipt,receipt)
    retired=json.loads(remote(r,PYTHON+' code/repeat_archive.py retire '+source+' --receipt '+localreceipt))
    assert retired['pass']
    proofs.append({'rank':r,'receipt':receipt,'restore':restored,'truncated_archive_refused':refusal.strip(),'retired_redundant_control_source':retired})
    print('archive roundtrip rank',r,flush=True)
write(HERE/'qualification-archives.json',proofs)
resumes=[f'repeat-restored-v1/control/rank{r}/s937/ram/checkpoint-32' for r in range(10)]
rows=training('qual-archive-resume',937,'ram','repeat-control-v1/s937/ram/roundtrip',2,resume=resumes,save=False,eval_every=16)
for r,row in enumerate(rows):
    old=json.loads((HERE/'phases/qual-archive-prefix'/f'rank{r}.json').read_text())
    assert row['initial_state_sha256']==old['final_state_sha256'] and row['initial_validation_ce']==old['final_validation_ce']

# Cancellation is intentionally one rank only: it blocks in mesh initialization before model allocation.
run_id='qual-owned-cancel'
cmd=ENV+' MLX_RANK=0 MLX_HOSTFILE="$PWD/ring-repeat.json" '+PYTHON+' code/repeat_supervisor.py --run-id '+run_id+' --seconds 60 --receipt repeat-receipts-v1/qual-owned-cancel.json -- '+PYTHON+' -m hy code/repeat_train.hy --world-size 10 --preset tiny --seed 937 --state-placement ram --data data --output repeat-control-v1/owned-cancel --steps 2 --seq 16 --memory-limit-gib 2 --no-save >repeat-log-qual-owned-cancel.txt 2>&1'
p=subprocess.Popen(SSH[0]+['cd '+ROOT+' && '+cmd],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
for _ in range(30):
    ready=remote(0,'test -f .repeat-controls-v1/qual-owned-cancel.json && cat .repeat-controls-v1/qual-owned-cancel.json || true')
    if ready: break
    time.sleep(.2)
assert ready and json.loads(ready)['live']
stop=json.loads(remote(0,PYTHON+' code/repeat_supervisor.py --stop --run-id '+run_id))
p.communicate(timeout=15)
receipt=fetch(0,'repeat-receipts-v1/qual-owned-cancel.json',HERE/'qualification-cancel-receipt.json')
assert receipt['exit_status']!=0 and receipt['child_reaped'] and not receipt['timed_out'] and stop['stop_requested']
snapshot('qualification-complete')
a=json.loads((HERE/'phases/qual-s936-ram/rank1.json').read_text()); b=json.loads((HERE/'phases/qual-s938-ram/rank1.json').read_text())
assert a['initial_state_sha256']!=b['initial_state_sha256'] and a['input_sha256']!=b['input_sha256']
write(HERE/'qualification.json',{'pass':True,'kernel_controls_all_ten':True,'seeds_change_initialization_and_inputs':True,'paired_placements_initial_state_and_inputs_exact':True,'checkpoint_refusal_cases_per_backend_per_rank':11,'tiny_archive_roundtrip_resume_exact':True,'truncated_archives_refused':10,'owned_worker_cancel_reaped':True,'developer_mac_model_execution':False})
print('QUALIFICATION_COMPLETE',flush=True)
