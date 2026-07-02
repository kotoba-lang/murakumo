import gguf, sys, bisect, json, os
SNAP='/Users/junkawasaki/.cache/huggingface/hub/models--pipenetwork--GLM-5.2-REAP50-Q2_K-GGUF/snapshots/ea3c0f0123c6b5f746b3098cd38484b274f41a51'
URL='https://huggingface.co/pipenetwork/GLM-5.2-REAP50-Q2_K-GGUF/resolve/main'
PARTS=[f'GLM-5.2-REAP50-Q2_K-0000{i}-of-00004.gguf' for i in range(1,5)]
NODES=['naphtali','judah','simeon','zebulun','levi','joseph','issachar','dan','benjamin','asher','jacob','HEAD']
TS=[int(x) for x in sys.argv[1].split(',')]  # 12 entries, order above
assert len(TS)==12
tensors=[]  # (name, part_idx, offset, size, layer)
for pi,p in enumerate(PARTS):
    r=gguf.GGUFReader(os.path.join(SNAP,p))
    for t in r.tensors:
        nm=t.name
        layer=int(nm.split('.')[1]) if nm.startswith('blk.') else None
        tensors.append((nm,pi,int(t.data_offset),int(t.n_bytes),layer))
n_layer=78; act=n_layer+1
tot=sum(TS); splits=[]; c=0
for w in TS: c+=w; splits.append(c/tot)
def dev(il): return bisect.bisect_right(splits, il/act)
# note upper_bound(first > f); f=il/act
asg={}
for il in range(n_layer): asg[il]=dev(il)
out_dev=dev(n_layer)  # output layer
per={n:{'bytes':0,'fetch':0,'layers':[],'nfetch':0} for n in NODES}
for nm,pi,off,sz,il in tensors:
    if il is None:
        node='HEAD'  # token_embd->CPU(head), output/output_norm->out_dev(head expected)
    else:
        node=NODES[asg[il]]
    per[node]['bytes'] += sz
    if il is not None and sz > 10*1024*1024 and node!='HEAD':
        per[node]['fetch'] += sz; per[node]['nfetch'] += 1
for il in range(n_layer): per[NODES[asg[il]]]['layers'].append(il)
print(f"output-layer device: {NODES[out_dev]}")
for n in NODES:
    L=per[n]['layers']
    rng=f"{L[0]}-{L[-1]}({len(L)})" if L else "-"
    print(f"{n:10s} layers {rng:12s} total {per[n]['bytes']/1e9:6.2f}GB fetch {per[n]['fetch']/1e9:6.2f}GB in {per[n]['nfetch']} tensors")
if sys.argv[2:] and sys.argv[2]=='emit':
    os.makedirs('/tmp/prewarm',exist_ok=True)
    reqs={n:[] for n in NODES}
    for nm,pi,off,sz,il in tensors:
        if il is None or sz<=10*1024*1024: continue
        node=NODES[asg[il]]
        if node=='HEAD': continue
        reqs[node].append((pi,off,sz,nm))
    json.dump({n:reqs[n] for n in NODES if reqs[n]}, open('/tmp/prewarm/reqs.json','w'))
    print('emitted /tmp/prewarm/reqs.json')
