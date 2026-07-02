import json, subprocess, os
SNAP='/Users/junkawasaki/.cache/huggingface/hub/models--pipenetwork--GLM-5.2-REAP50-Q2_K-GGUF/snapshots/ea3c0f0123c6b5f746b3098cd38484b274f41a51'
PARTS=[f'GLM-5.2-REAP50-Q2_K-0000{i}-of-00004.gguf' for i in range(1,5)]
FNV=os.path.dirname(os.path.abspath(__file__))+'/fnv'
reqs=json.load(open('/tmp/prewarm/reqs.json'))
# group all (node,idx) by part for one mmap pass per part
by_part={}
for node, lst in reqs.items():
    for i,(pi,off,sz,nm) in enumerate(lst):
        by_part.setdefault(pi,[]).append((node,i,off,sz))
hashes={}
for pi, items in sorted(by_part.items()):
    inp='\n'.join(f"{off}:{sz}" for _,_,off,sz in items)
    out=subprocess.run([FNV, os.path.join(SNAP,PARTS[pi])], input=inp, capture_output=True, text=True, check=True).stdout.split()
    for (node,i,off,sz),h in zip(items,out):
        hashes[(node,i)]=h
    print(f"part{pi+1}: {len(items)} tensors hashed")
os.makedirs('/tmp/prewarm/manifests',exist_ok=True)
for node,lst in reqs.items():
    with open(f'/tmp/prewarm/manifests/{node}.txt','w') as f:
        for i,(pi,off,sz,nm) in enumerate(lst):
            f.write(f"{hashes[(node,i)]} {pi+1} {off} {sz}\n")
    print(node, len(lst), 'entries')
