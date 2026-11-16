"""One-shot standard-library lossless study checkpoint transport, never training."""
import argparse, gzip, hashlib, json, os, pathlib, re, shutil, sys, tarfile

ROOT = pathlib.Path.cwd().resolve()
SEEDS = {936, 937, 938}

def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()

def archive_path(name):
    rel = pathlib.Path(name)
    if not re.fullmatch(r'repeat-archives-v1/(control/)?rank[0-9]/s(936|937|938)/(ram|nvme)/checkpoint-(32|64)\.tar\.gz', str(rel)):
        raise ValueError('archive destination outside current study')
    raw = ROOT / rel
    if any(x.is_symlink() for x in [raw, *raw.parents]): raise ValueError('symlink archive')
    return raw

def checked_source(name):
    p = pathlib.Path(name)
    if not re.fullmatch(r'(repeat-v1|repeat-control-v1)/s(936|937|938)/(ram|nvme)/(prefix|suffix)/checkpoint-(32|64)', str(p)):
        raise ValueError('only new completed study checkpoints may be archived')
    raw = ROOT / p
    if any(x.is_symlink() for x in [raw, *raw.parents]):
        raise ValueError('symlink source')
    p = raw.resolve()
    if not (p.is_relative_to(ROOT / 'repeat-v1') or p.is_relative_to(ROOT / 'repeat-control-v1')) or not p.is_dir():
        raise ValueError('source outside current study')
    if any(x.is_symlink() for x in [p, *p.parents] if x != ROOT.parent):
        raise ValueError('symlink source')
    return p

def index(name):
    p = checked_source(name)
    state = json.loads((p / 'state.json').read_text())
    glob = json.loads((p / 'GLOBAL.json').read_text())
    cert = json.loads((p / 'COMMITTED.json').read_text())
    manifest = digest(p / 'GLOBAL.json')
    assert cert['manifest_sha256'] == manifest
    assert cert['rank_votes'] == [manifest] * 10
    assert (p / 'COMPLETE').read_text().strip() == digest(p / 'state.json')
    assert state['schema'] == 'ced-moe-repeat-shards/v1'
    assert state['common'] == glob['common']
    assert state['common']['step'] == int(p.name.split('-')[-1])
    assert state['world_size'] == 10
    assert state['common']['options']['seed'] == int(p.parts[-4][1:])
    assert state['common']['options']['state_placement'] == p.parts[-3]
    assert state['common']['options']['seed'] in SEEDS
    assert glob['ranks'][state['rank']]['state_sha256'] == digest(p / 'state.json')
    files = dict(state['files'])
    for n in ['state.json', 'GLOBAL.json', 'COMMITTED.json', 'COMPLETE']:
        files[n] = {'file_bytes': (p / n).stat().st_size, 'sha256': digest(p / n)}
    actual = {str(x.relative_to(p)) for x in p.rglob('*') if x.is_file()}
    assert actual == set(files)
    for rel, info in files.items():
        f = p / rel
        assert not f.is_symlink() and f.is_file()
        assert f.stat().st_size == info['file_bytes'] and digest(f) == info['sha256']
    return {'source': name, 'rank': state['rank'], 'step': state['common']['step'],
            'global_manifest_sha256': manifest, 'state_sha256': digest(p / 'state.json'),
            'files': files, 'raw_bytes': sum(x['file_bytes'] for x in files.values())}

def stream(name):
    p = checked_source(name)
    proof = index(name)
    class HashWriter:
        def __init__(self): self.h = hashlib.sha256(); self.count = 0
        def write(self, b): self.h.update(b); self.count += len(b); return sys.stdout.buffer.write(b)
        def flush(self): sys.stdout.buffer.flush()
    sink = HashWriter()
    with tarfile.open(fileobj=sink, mode='w|gz', compresslevel=1) as t:
        payload = json.dumps(proof, sort_keys=True).encode()
        import io
        info = tarfile.TarInfo('ARCHIVE-INDEX.json'); info.size = len(payload); info.mode = 0o600
        t.addfile(info, io.BytesIO(payload))
        for rel, entry in sorted(proof['files'].items()):
            info = tarfile.TarInfo(rel); info.size = entry['file_bytes']; info.mode = 0o600
            with open(p / rel, 'rb') as f:
                t.addfile(info, f)
    sink.flush()
    print(json.dumps({'stream_pass': True, 'archive_sha256': sink.h.hexdigest(), 'archive_bytes': sink.count, 'source': name, 'global_manifest_sha256': proof['global_manifest_sha256']}), file=sys.stderr, flush=True)

def verify(path):
    # Consume the gzip footer/CRC too; tar alone may stop at its end marker.
    with gzip.open(path, 'rb') as f:
        for _ in iter(lambda: f.read(1024 * 1024), b''): pass
    seen = set()
    with tarfile.open(path, 'r|gz') as t:
        members = iter(t)
        head = next(members, None)
        assert head and head.name == 'ARCHIVE-INDEX.json' and head.isfile() and head.size < 1024 * 1024
        proof = json.loads(t.extractfile(head).read())
        for member in members:
            assert member.isfile() and member.name in proof['files'] and member.name not in seen
            expected = proof['files'][member.name]
            assert member.size == expected['file_bytes']
            h = hashlib.sha256(); count = 0
            f = t.extractfile(member)
            for b in iter(lambda: f.read(1024 * 1024), b''):
                h.update(b); count += len(b)
            assert count == expected['file_bytes'] and h.hexdigest() == expected['sha256']
            seen.add(member.name)
    assert seen == set(proof['files'])
    return {'pass': True, 'proof': proof, 'archive_sha256': digest(path), 'archive_bytes': pathlib.Path(path).stat().st_size}

def receive(name, reserve):
    rel = pathlib.Path(name)
    path = archive_path(name); path.parent.mkdir(parents=True, exist_ok=True)
    assert not path.exists() and not path.is_symlink()
    tmp = path.with_suffix(path.suffix + '.partial'); assert not tmp.exists()
    free = shutil.disk_usage(path.parent).free
    bound = free - reserve
    assert bound > 1024 * 1024
    count = 0
    with open(tmp, 'xb') as f:
        for b in iter(lambda: sys.stdin.buffer.read(1024 * 1024), b''):
            count += len(b)
            if count > bound: raise ValueError('archive reserve exhausted')
            f.write(b)
        f.flush(); os.fsync(f.fileno())
    receipt = verify(tmp)
    tmp.rename(path)
    receipt['archive_path'] = str(rel)
    with open(str(path) + '.verified.json', 'x') as f:
        json.dump(receipt, f, sort_keys=True); f.flush(); os.fsync(f.fileno())
    fd = os.open(path.parent, os.O_RDONLY); os.fsync(fd); os.close(fd)
    print(json.dumps(receipt))

def retire(name, receipt_path):
    """Remove only redundant new-study copy after verified archive proof is supplied."""
    old = index(name)
    r = json.loads(pathlib.Path(receipt_path).read_text())
    assert r['pass'] and r['proof'] == old
    assert re.fullmatch('[0-9a-f]{64}', r['archive_sha256'])
    assert r['archive_bytes'] > 0 and r['archive_path'].startswith('repeat-archives-v1/')
    p = checked_source(name)
    pointer = p.with_name(p.name + '.ARCHIVED.json')
    assert not pointer.exists()
    with open(pointer, 'x') as f:
        json.dump(r, f, sort_keys=True); f.flush(); os.fsync(f.fileno())
    shutil.rmtree(p)
    print(json.dumps({'pass': True, 'redundant_source_retired': name, 'archive_sha256': r['archive_sha256']}))

def retire_archive(name, receipt_path):
    path = archive_path(name)
    original = json.loads(pathlib.Path(str(path) + '.verified.json').read_text())
    destination = json.loads(pathlib.Path(receipt_path).read_text())
    assert original['pass'] and destination['pass']
    for key in ['archive_sha256', 'archive_bytes', 'proof']:
        assert original[key] == destination[key]
    assert destination['archive_host'] != destination['retired_host']
    assert digest(path) == destination['archive_sha256']
    pointer = pathlib.Path(str(path) + '.MOVED.json')
    with open(pointer, 'x') as f:
        json.dump(destination, f, sort_keys=True); f.flush(); os.fsync(f.fileno())
    path.unlink()
    print(json.dumps({'pass': True, 'redundant_archive_retired': name, 'archive_sha256': destination['archive_sha256']}))

def partial_index(path):
    with tarfile.open(path, 'r|gz') as t:
        head=t.next()
        assert head and head.name=='ARCHIVE-INDEX.json' and head.isfile() and head.size<1024*1024
        return json.loads(t.extractfile(head).read())

def prepare_retry(name, receipt_path):
    # Called only after transport children have exited; never during an active stream.
    path=archive_path(name); p=path.with_suffix(path.suffix+'.partial')
    expected=json.loads(pathlib.Path(receipt_path).read_text())['proof']
    assert not path.exists() and p.is_file() and not p.is_symlink()
    assert partial_index(p)==expected
    try: r=verify(p)
    except (EOFError, OSError, tarfile.TarError, AssertionError):
        dest=pathlib.Path(str(path)+'.interrupted-v1'); assert not dest.exists()
        p.rename(dest)
        print(json.dumps({'pass':True,'incomplete_preserved':str(dest.relative_to(ROOT)),'proof':expected}))
        return
    assert r['proof']==expected
    p.rename(path); r['archive_path']=name
    with open(str(path)+'.verified.json','x') as f:
        json.dump(r,f,sort_keys=True);f.flush();os.fsync(f.fileno())
    print(json.dumps({'pass':True,'complete_partial_recovered':True,'receipt':r}))

def retire_partial(name, receipt_path):
    path=archive_path(name); p=pathlib.Path(str(path)+'.interrupted-v1')
    r=json.loads(pathlib.Path(receipt_path).read_text())
    assert r['pass'] and p.is_file() and not p.is_symlink()
    assert partial_index(p)==r['proof']
    record={'pass':True,'redundant_partial_sha256':digest(p),'redundant_partial_bytes':p.stat().st_size,
            'complete_retained_archive':r,'reason':'incomplete derived transport; full checkpoint bytes independently verified in retained archive'}
    with open(str(path)+'.RETIRED-PARTIAL.json','x') as f:
        json.dump(record,f,sort_keys=True);f.flush();os.fsync(f.fileno())
    p.unlink(); print(json.dumps(record))

def restore(archive, destination):
    proof = verify(archive)['proof']
    dest = ROOT / destination
    if not re.fullmatch(r'repeat-restored-v1/(control/)?rank[0-9]/s(936|937|938)/(ram|nvme)/checkpoint-(32|64)', destination):
        raise ValueError('restore outside explicit study destination')
    assert not dest.exists()
    tmp = dest.with_name(dest.name + '.partial'); assert not tmp.exists()
    tmp.mkdir(parents=True)
    with tarfile.open(archive, 'r|gz') as t:
        for m in t:
            if m.name == 'ARCHIVE-INDEX.json':
                continue
            assert m.name in proof['files'] and m.isfile()
            p = tmp / m.name; p.parent.mkdir(parents=True, exist_ok=True)
            with open(p, 'xb') as f:
                shutil.copyfileobj(t.extractfile(m), f, 1024 * 1024)
                f.flush(); os.fsync(f.fileno())
            assert digest(p) == proof['files'][m.name]['sha256']
    tmp.rename(dest)
    print(json.dumps({'pass': True, 'restored': destination, 'proof': proof}))

if __name__ == '__main__':
    a = argparse.ArgumentParser(); a.add_argument('action', choices=['index', 'stream', 'receive', 'verify', 'retire', 'restore', 'retire-archive', 'prepare-retry', 'retire-partial'])
    a.add_argument('path'); a.add_argument('--receipt'); a.add_argument('--destination'); a.add_argument('--reserve', type=int, default=1024**3)
    x = a.parse_args()
    if x.action == 'index': print(json.dumps(index(x.path)))
    elif x.action == 'stream': stream(x.path)
    elif x.action == 'receive': receive(x.path, x.reserve)
    elif x.action == 'verify': print(json.dumps(verify(x.path)))
    elif x.action == 'restore': restore(x.path, x.destination)
    elif x.action == 'retire-archive': retire_archive(x.path, x.receipt)
    elif x.action == 'prepare-retry': prepare_retry(x.path, x.receipt)
    elif x.action == 'retire-partial': retire_partial(x.path, x.receipt)
    else: retire(x.path, x.receipt)
