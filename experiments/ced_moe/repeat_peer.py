"""Bounded one-connection fleet archive transport; no service installation or model code.

A fresh secret is supplied through SSH stdin, never argv/disk/logs. The only
payload operation is the existing restricted archive receiver. All bytes/hashes
are independently verified by that receiver and compared through SSH receipts.
"""
import argparse,hashlib,json,os,pathlib,secrets,signal,socket,subprocess,sys,time
PYTHON='./python/bin/python3.12'
ACTIVE=[None]

def cancel(signum,frame):
    p=ACTIVE[0]
    if p is not None and p.poll() is None:
        try:os.killpg(p.pid,signal.SIGTERM)
        except ProcessLookupError:pass
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid,signal.SIGKILL);p.wait()
    raise SystemExit(128+signum)

def write(path,obj):
    p=pathlib.Path(path);p.parent.mkdir(parents=True,exist_ok=True)
    with open(p,'x') as f:json.dump(obj,f);f.flush();os.fsync(f.fileno())

def receive(a,settings):
    assert settings['bind_ip'].startswith('169.254.') and len(bytes.fromhex(settings['token']))==32
    child=None
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        listener.bind((settings['bind_ip'],25712));listener.listen(1);listener.settimeout(30)
        write('repeat-peer-v1/'+a.run_id+'.ready.json',{'ready':True,'bind_ip':settings['bind_ip'],'port':25712,'one_connection':True})
        conn,addr=listener.accept()
        with conn:
            conn.settimeout(15);key=b''
            while len(key)<32:
                part=conn.recv(32-len(key))
                if not part:break
                key+=part
            if not secrets.compare_digest(key,bytes.fromhex(settings['token'])):
                print(json.dumps({'pass':False,'authentication_refused':True,'child_started':False,'run_id':a.run_id}),flush=True)
                return 3
            conn.settimeout(None)
            cmd=[PYTHON,'code/repeat_archive.py','receive',settings['archive_path'],'--reserve',str(1024**3)]
            child=subprocess.Popen(cmd,stdin=conn,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
            ACTIVE[0]=child
            try:out,err=child.communicate(timeout=600)
            except subprocess.TimeoutExpired:
                child.terminate();out,err=child.communicate(timeout=10)
                raise RuntimeError('peer receiver deadline; partial remains preserved')
            if child.returncode:raise RuntimeError('archive receiver failed: '+err.decode()[-1000:])
            receipt=json.loads(out);assert receipt['pass']
    receipt['transport']='direct-fleet-tcp-v1';receipt['peer_ip']=addr[0];receipt['run_id']=a.run_id
    print(json.dumps(receipt),flush=True)
    return 0

def send(a,settings):
    assert settings['peer_ip'].startswith('169.254.') and len(bytes.fromhex(settings['token']))==32
    with socket.create_connection((settings['peer_ip'],25712),timeout=20) as conn:
        conn.sendall(bytes.fromhex(settings['token']));conn.settimeout(None)
        if settings.get('probe_auth'):
            print(json.dumps({'auth_probe_sent':True,'run_id':a.run_id}));return 0
        action=settings['action'];path=settings['path']
        assert action in ['stream','copy']
        if action=='stream':cmd=[PYTHON,'code/repeat_archive.py','stream',path]
        else:
            import repeat_archive as archive
            p=archive.archive_path(path);assert p.is_file()
            cmd=['/bin/cat',str(p)]
        proc=subprocess.Popen(cmd,stdout=conn,stderr=subprocess.PIPE,start_new_session=True)
        ACTIVE[0]=proc
        try:_,err=proc.communicate(timeout=600)
        except subprocess.TimeoutExpired:
            proc.terminate();proc.communicate(timeout=10)
            raise RuntimeError('peer sender deadline')
        if proc.returncode:raise RuntimeError('peer source failed: '+err.decode()[-1000:])
        conn.shutdown(socket.SHUT_WR)
    r=json.loads(err.decode().splitlines()[-1]) if action=='stream' else {'copy_source_completed':True}
    r['run_id']=a.run_id;print(json.dumps(r),flush=True);return 0

if __name__=='__main__':
    signal.signal(signal.SIGTERM,cancel);signal.signal(signal.SIGHUP,cancel);signal.signal(signal.SIGINT,cancel)
    p=argparse.ArgumentParser();p.add_argument('role',choices=['receive','send']);p.add_argument('--run-id',required=True);a=p.parse_args()
    import re
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,100}',a.run_id)
    settings=json.load(sys.stdin)
    sys.exit(receive(a,settings) if a.role=='receive' else send(a,settings))
