"""Preserve/recover interrupted derived transport using a genuine tiny archive."""
from archive_control import *
original=json.loads((HERE/'qualification-relocation.json').read_text())['receipt']
path=original['archive_path']
proofreceipt='repeat-receipts-v1/qual-partial-source.json'
results=[]
for target,truncate in [(0,True),(3,False)]:
    code="import pathlib,sys,os; p=pathlib.Path(sys.argv[1]+'.partial');p.parent.mkdir(parents=True,exist_ok=True); tail=b''; f=open(p,'xb'); trunc=sys.argv[2]=='1';\nwhile True:\n b=sys.stdin.buffer.read(1048576)\n if not b:break\n if trunc:\n  joined=tail+b;f.write(joined[:-8]);tail=joined[-8:]\n else:f.write(b)\nf.flush();os.fsync(f.fileno());f.close()"
    producer=subprocess.Popen(SSH[1]+['cd '+ROOT+' && cat '+path],stdout=subprocess.PIPE)
    consumer=subprocess.Popen(SSH[target]+['cd '+ROOT+' && '+PYTHON+' -c '+shlex.quote(code)+' '+path+' '+str(int(truncate))],stdin=producer.stdout,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    producer.stdout.close();out,err=consumer.communicate(timeout=120); assert consumer.returncode==0 and producer.wait(timeout=10)==0,err
    receipt_on(target,proofreceipt,original)
    prepared=json.loads(remote(target,PYTHON+' code/repeat_archive.py prepare-retry '+path+' --receipt '+proofreceipt))
    assert prepared['pass']
    if truncate:
        assert prepared['incomplete_preserved']
        retired=json.loads(remote(target,PYTHON+' code/repeat_archive.py retire-partial '+path+' --receipt '+proofreceipt))
        assert retired['pass'] and retired['redundant_partial_bytes']==original['archive_bytes']-8
    else:
        assert prepared['complete_partial_recovered'] and prepared['receipt']['archive_sha256']==original['archive_sha256']
        retired=None
    results.append({'target':target,'truncated':truncate,'prepare':prepared,'retire_redundant_after_full_original_verified':retired})
write(HERE/'qualification-partial-recovery.json',{'pass':True,'controls':results,'original_complete_archive_retained_host':1})
print('PARTIAL_RECOVERY_QUALIFIED',flush=True)
