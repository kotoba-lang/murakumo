"""Qualify peer payload/authentication only after all model jobs of a condition end."""
from peer_control import *
marker=HERE/'phases/actual-s937-ram-readback/DONE.json'
started=time.monotonic()
while not marker.exists():
    if time.monotonic()-started>1800:raise RuntimeError('wait for model stop expired; no peer transfer started')
    time.sleep(5)
rows=json.loads((HERE/'snapshots/actual-s937-ram-readback-after.json').read_text())
assert all(not r['owned_model_processes'] and not r['listeners'] for r in rows)
# New helper only: unchanged numerical model files remain source-bound02a6ee4b.
repo=HERE.parents[2]
for r in range(10):
    data=(repo/'experiments/ced_moe/repeat_peer.py').read_text()
    remote(r,PYTHON+' -c '+shlex.quote("import pathlib,sys;pathlib.Path('code/repeat_peer.py').write_text(sys.stdin.read())"),data=data)
source='repeat-v1/s937/ram/suffix/checkpoint-64';archive='repeat-archives-v1/control/rank0/s937/ram/checkpoint-64.tar.gz'
assert remote(8,'test ! -e '+archive+' && echo absent').strip()=='absent'
negative=transfer_peer(0,8,source,archive,raw=True,probe=True)
assert remote(8,'test ! -e '+archive+' && test ! -e '+archive+'.partial && echo absent').strip()=='absent'
original=json.loads((HERE/'qualification-relocation.json').read_text())['receipt']
copy=transfer_peer(1,8,original['archive_path'],original['archive_path'],raw=False)
assert copy['archive_sha256']==original['archive_sha256'] and copy['proof']==original['proof']
raw=transfer_peer(0,8,source,archive,raw=True)
assert raw['pass'] and raw['proof']['rank']==0 and raw['proof']['step']==64
# Both ephemeral listeners and children must be gone before qualifying the route.
for rank in [0,1,8]:
    listeners=remote(rank,"lsof -nP -iTCP:25712 -sTCP:LISTEN || true")
    assert not listeners.strip()
write(HERE/'qualification-peer.json',{'pass':True,'scope':'actual3B owner0 fullraw archive and genuine tiny compressedarchive directtransfer; allmember/streamSHA checks; wrongsecret refused before child/no file; bounded singleconnection only, no service installation','wrong_token':negative,'copy':copy,'raw':raw,'binary_data_on_developer_mac':False,'model_sources_unchanged':True})
print('DIRECT_PEER_QUALIFIED',flush=True)
