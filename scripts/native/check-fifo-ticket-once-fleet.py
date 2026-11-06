#!/usr/bin/env python3
"""Run task-only RGQ5 native diagnostics on three pinned Tailscale hosts."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import secrets
import shlex
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HOSTS = ('naphtali', 'benjamin', 'judah')
REMOTE = r'''import hashlib,io,pathlib,platform,subprocess,sys,tarfile,tempfile
expected=EXPECTED;nonce=sys.argv[1]
with tempfile.TemporaryDirectory(prefix='awai151-ticket-fleet-',dir='/private/tmp') as d:
 root=pathlib.Path(d)
 with tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read()),mode='r:') as t:
  for name,digest in expected.items():
   raw=t.extractfile(name).read();assert hashlib.sha256(raw).hexdigest()==digest
   p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw);p.chmod(0o700 if name.startswith('images/') else 0o600)
 cmd=['/usr/bin/python3',str(root/'scripts/native/check-fifo-ticket-once.py'),'--compiler',str(root/'images/compiler'),'--compiler-sha256',expected['images/compiler'],'--packager',str(root/'images/packager'),'--packager-sha256',expected['images/packager'],'--old-fifo',str(root/'images/old'),'--old-sha256',expected['images/old']]
 p=subprocess.run(cmd,capture_output=True,text=True,timeout=45);print('TEST_EXIT',p.returncode,flush=True);print(p.stdout,end='',flush=True)
 if p.returncode or p.stderr:raise RuntimeError(p.stderr[-900:])
 print('HOST_OS',platform.mac_ver()[0],platform.machine(),subprocess.check_output(['/usr/bin/sw_vers','-buildVersion'],text=True).strip(),flush=True)
print('COMPLETE',nonce,'temp_absent',not root.exists(),flush=True)
'''


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('compiler', 'packager', 'old-fifo'):
        p.add_argument('--' + name, type=Path, required=True)
        p.add_argument('--' + name + '-sha256', required=True)
    args = p.parse_args()
    files = {'scripts/native/check-fifo-ticket-once.py': ROOT / 'scripts/native/check-fifo-ticket-once.py',
             'kotoba/resource_fifo.kotoba': ROOT / 'kotoba/resource_fifo.kotoba',
             'images/compiler': args.compiler, 'images/packager': args.packager, 'images/old': args.old_fifo}
    payload = {name: path.read_bytes() for name, path in files.items()}
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in payload.items()}
    for name, expected in [('images/compiler', args.compiler_sha256), ('images/packager', args.packager_sha256),
                           ('images/old', args.old_fifo_sha256)]:
        if hashes[name] != expected:
            raise ValueError('input digest mismatch: ' + name)
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode='w') as tar:
        for name, data in payload.items():
            item = tarfile.TarInfo(name); item.size = len(data); item.mode = 0o700 if name.startswith('images/') else 0o600
            tar.addfile(item, io.BytesIO(data))
    remote = REMOTE.replace('EXPECTED', repr(hashes), 1)
    status = json.loads(subprocess.check_output(['tailscale', 'status', '--json'], timeout=8))
    peers = {peer.get('HostName'): peer for peer in status['Peer'].values()}
    for host in HOSTS:
        peer = peers.get(host, {})
        keys = [key for key in peer.get('sshHostKeys', []) if key.startswith(('ssh-ed25519 ', 'ssh-rsa ', 'ecdsa-sha2-nistp256 ')) and '\n' not in key]
        if not peer.get('Online') or not peer.get('TailscaleIPs') or not keys:
            raise RuntimeError('host identity unavailable: ' + host)
        nonce = secrets.token_hex(16)
        with tempfile.TemporaryDirectory(prefix='awai151-hostkeys-') as folder:
            known = Path(folder) / 'known'; known.write_text(''.join(host + ' ' + key + '\n' for key in keys)); known.chmod(0o600)
            command = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', '-o', 'StrictHostKeyChecking=yes',
                       '-o', 'HostKeyAlias=' + host, '-o', 'UserKnownHostsFile=' + str(known),
                       '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'HostName=' + peer['TailscaleIPs'][0],
                       host + '@' + host, '/usr/bin/python3 -c ' + shlex.quote(remote) + ' ' + shlex.quote(nonce)]
            result = subprocess.run(command, input=archive.getvalue(), capture_output=True, timeout=55)
            output = result.stdout.decode(errors='replace')
            print('HOST', host, 'EXIT', result.returncode, flush=True); print(output, end='', flush=True)
            if result.returncode or ('COMPLETE ' + nonce + ' temp_absent True') not in output:
                raise RuntimeError('unknown host outcome: ' + result.stderr.decode(errors='replace')[-600:])
    print('observer_sha256', hashes['scripts/native/check-fifo-ticket-once.py'])
    print('source_sha256', hashes['kotoba/resource_fifo.kotoba'])


if __name__ == '__main__':
    main()
