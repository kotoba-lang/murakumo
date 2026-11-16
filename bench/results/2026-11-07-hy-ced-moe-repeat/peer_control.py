"""SSH-authorized, bounded direct fleet transfer. Secrets never enter evidence."""
from study_control import *
import secrets,threading
IPS=['169.254.21.174','169.254.247.12','169.254.182.93','169.254.64.210','169.254.50.130','169.254.114.33','169.254.178.155','169.254.14.58','169.254.209.204','169.254.11.218']
TARGET_LOCKS=[threading.Lock() for _ in range(10)]

def transfer_peer(source,target,path,archive,raw=True,probe=False):
    # Several owners may archive to one host; its one-connection listener is serial.
    with TARGET_LOCKS[target]:
        return _transfer_peer(source,target,path,archive,raw,probe)

def _transfer_peer(source,target,path,archive,raw=True,probe=False):
    assert source!=target
    run_id='peer-'+secrets.token_hex(12);key=secrets.token_hex(32)
    receiver=subprocess.Popen(SSH[target]+['cd '+ROOT+' && '+PYTHON+' code/repeat_peer.py receive --run-id '+run_id],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    receiver.stdin.write(json.dumps({'bind_ip':IPS[target],'archive_path':archive,'token':key}).encode());receiver.stdin.close();receiver.stdin=None
    try:
        ready=False
        for _ in range(40):
            x=remote(target,'test -f repeat-peer-v1/'+run_id+'.ready.json && cat repeat-peer-v1/'+run_id+'.ready.json || true',10)
            if x:ready=json.loads(x)['ready'];break
            if receiver.poll() is not None:raise RuntimeError('peer listener failed before ready')
            time.sleep(.2)
        assert ready
        sender={'peer_ip':IPS[target],'token':secrets.token_hex(32) if probe else key,'action':'stream' if raw else 'copy','path':path,'probe_auth':probe}
        sent=json.loads(remote(source,PYTHON+' code/repeat_peer.py send --run-id '+run_id,620,data=json.dumps(sender)))
        out,err=receiver.communicate(timeout=620)
        result=json.loads(out)
        if probe:
            assert receiver.returncode==3 and result['authentication_refused'] and not result['child_started']
            return {'pass':True,'wrong_token_refused_before_archive_child':True,'run_id':run_id}
        if receiver.returncode:raise RuntimeError('peer receiver failed: '+err.decode()[-1000:])
        assert result['pass']
        assert result['peer_ip']==IPS[source], 'peer payload must originate on the expected wired fleet host'
        if raw:assert sent['stream_pass'] and sent['archive_sha256']==result['archive_sha256'] and sent['archive_bytes']==result['archive_bytes']
        result.update({'source_host':source,'archive_host':target,'source_stream_receipt':sent if raw else None})
        return result
    except BaseException:
        if receiver.poll() is None:receiver.terminate()
        try:receiver.communicate(timeout=15)
        except subprocess.TimeoutExpired:receiver.kill();receiver.communicate()
        raise
