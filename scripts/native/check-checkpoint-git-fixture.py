#!/usr/bin/env python3
"""Run current checkpoint capture/restore with a privileged disposable adapter.

Only use with task-owned synthetic data. This exercises local Git, not a
production supervisor, provider, GitHub, fault recovery or authorization.
"""
import argparse
import importlib.util
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('bounded_probe', HERE / 'check-checkpoint-bounded-fixture.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--compiler', type=Path, required=True)
    p.add_argument('--compiler-sha256', required=True)
    p.add_argument('--packager', type=Path, required=True)
    p.add_argument('--packager-sha256', required=True)
    p.add_argument('--local-archive', action='store_true', help='also exercise a stubbed-private local bare Git remote')
    a = p.parse_args()
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'):
        raise ValueError('Apple Silicon macOS required')
    compiler = helper.verified(a.compiler, a.compiler_sha256)
    packager = helper.verified(a.packager, a.packager_sha256)
    with tempfile.TemporaryDirectory(prefix='awai150-checkpoint-', dir='/private/tmp') as tmp:
        root = Path(tmp); fixture = root / 'fixture3'
        fixture.mkdir(mode=0o700)
        for name in ('repo', 'store', 'logs', 'input', 'home'):
            (fixture / name).mkdir(mode=0o700)
        generator = 'generate-checkpoint-local-archive.py' if a.local_archive else 'generate-checkpoint-git-fixture.py'
        gen = subprocess.run(['/usr/bin/python3', str(HERE / generator),
                              '--fixture-parent', str(root), '--output', str(root / 'fixture.kotoba')],
                             check=True, capture_output=True, text=True, timeout=30)
        receipt = json.loads(gen.stdout)
        env = {'PATH': '/usr/bin:/bin', 'HOME': str(fixture / 'home'),
               'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'LANG': 'C'}
        def git(repo, *args):
            r = subprocess.run(['/usr/bin/git', '-C', str(fixture / repo), *args], env=env,
                               capture_output=True, timeout=15, check=True)
            return r.stdout.decode()
        git('repo', 'init', '--quiet', '--template=')
        if a.local_archive:
            (fixture / 'remote.git').mkdir(mode=0o700)
            git('remote.git', 'init', '--bare', '--quiet', '--template=')
        (fixture / 'repo/tracked.txt').write_text('base\n')
        git('repo', 'add', '--', 'tracked.txt')
        git('repo', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
            'commit', '--quiet', '-m', 'fixture')
        (fixture / 'repo/tracked.txt').write_text('staged\n'); git('repo', 'add', '--', 'tracked.txt')
        (fixture / 'repo/tracked.txt').write_text('unstaged\n')
        (fixture / 'repo/notes.txt').write_text('untracked content\n')
        os.chmod(fixture / 'repo/notes.txt', 0o644)
        for name, value in [('seed', '00' * 32 + '\n'), ('profile', 'test profile\n'),
                            ('policy', 'test policy\n'), ('environment', 'test environment\n'),
                            ('summary', 'synthetic checkpoint\n'), ('untracked', '["notes.txt"]')]:
            (fixture / 'input' / name).write_text(value)
        def snapshot(repo):
            return {'head': git(repo, 'rev-parse', 'HEAD').strip(),
                    'status': git(repo, 'status', '--porcelain=v1', '--untracked-files=all'),
                    'index': git(repo, 'show', ':tracked.txt'),
                    'worktree': (fixture / repo / 'tracked.txt').read_text(),
                    'untracked': (fixture / repo / 'notes.txt').read_text(),
                    'mode': stat.S_IMODE((fixture / repo / 'notes.txt').stat().st_mode)}
        fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY); saved = None
        if fd != 3:
            try: saved = os.dup(3)
            except OSError: pass
            os.dup2(fd, 3)
        try:
            def command(argv, expected, timeout=180, stderr_ok=False):
                r = subprocess.run([str(x) for x in argv], cwd=root, pass_fds=(3,),
                                   capture_output=True, timeout=timeout, env=env)
                if r.returncode != expected or (r.stderr and not stderr_ok):
                    raise RuntimeError((str(argv[0]), r.returncode, r.stdout[-500:], r.stderr[-500:]))
                return r.stdout
            command([compiler, 'compile', 'fixture.kotoba', '--target', 'aarch64-macos',
                     '--output', 'fixture.kseed'], 0)
            output = command([compiler, 'extract-native', 'fixture.kseed', '--symbol', 'main',
                              '--output', 'fixture.raw'], 0)
            match = re.search(rb':offset (\d+), :length (\d+), :arity 0}', output)
            if not match or int(match.group(2)) != (root / 'fixture.raw').stat().st_size:
                raise ValueError('native extraction mismatch')
            command([packager, 'fixture-unsigned.macho', 'fixture.raw', 'amu-cli-host',
                     match.group(1).decode()], 0)
            image = root / 'fixture.macho'; shutil.copyfile(root / 'fixture-unsigned.macho', image)
            image.chmod(0o700)
            subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', str(image)],
                           check=True, capture_output=True, timeout=30)
            subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(image)],
                           check=True, capture_output=True, timeout=30)
            def guest(args, expected=0):
                return command([image, *args], expected, timeout=45, stderr_ok=True).decode()
            if a.local_archive:
                guest(['config-test', '/fixture3/repo'])
                git('repo', 'config', '--local', 'credential.helper', 'synthetic-helper')
                try: guest(['config-test', '/fixture3/repo'], 1)
                finally: git('repo', 'config', '--local', '--unset-all', 'credential.helper')
                git('repo', 'config', '--local', 'include.path', '/nonexistent/synthetic')
                try: guest(['config-test', '/fixture3/repo'], 1)
                finally: git('repo', 'config', '--local', '--unset-all', 'include.path')
            before = snapshot('repo')
            captured = guest(['capture', '/fixture3/repo', '/fixture3/store', '/fixture3/input/seed',
                              '/fixture3/input/profile', '/fixture3/input/policy', '/fixture3/input/environment',
                              '/fixture3/input/summary', '/fixture3/input/untracked', '1', 'root', 'fixture'])
            line, encoded = captured.split('\n', 1)
            if not line.startswith('checkpoint/created '): raise ValueError(('capture', captured))
            cid = line.split(' ')[1]; document = json.loads(encoded); v = document['payload']
            if json.loads((fixture / 'store' / cid).read_text()) != document or snapshot('repo') != before:
                raise ValueError('capture did not match source')
            base = ['/fixture3/store', cid, v['did'], v['profile'], v['policy'], v['environment'], '1', 'root', 'fixture']
            if guest(['verify', *base]) != 'checkpoint/verified\n': raise ValueError('verify failed')
            wrong = base.copy(); wrong[4] = v['environment']
            if not guest(['verify', *wrong], 1).startswith('checkpoint/'): raise ValueError('wrong policy accepted')
            if guest(['restore', *base, '/fixture3/restored']) != 'checkpoint/restored\n': raise ValueError('restore failed')
            after = snapshot('restored')
            if after != before: raise ValueError('restored snapshot mismatch')
            if guest(['restore', *base, '/fixture3/restored'], 1) != 'checkpoint/destination-exists\n':
                raise ValueError('existing destination accepted')
            block = fixture / 'store' / v['untracked'][0]['cid']; original = block.read_bytes()
            block.write_bytes(b'CORRUPTED')
            try:
                if guest(['verify', *base], 1) != 'checkpoint/missing-or-corrupt-block\n':
                    raise ValueError('corrupted block accepted')
            finally: block.write_bytes(original)
            if guest(['verify', *base]) != 'checkpoint/verified\n': raise ValueError('post-control verify failed')
            if not guest(['closure', *base]).endswith('checkpoint/closure-complete\n'):
                raise ValueError('closure failed')
            successor = guest(['capture', '/fixture3/repo', '/fixture3/store', '/fixture3/input/seed',
                               '/fixture3/input/profile', '/fixture3/input/policy', '/fixture3/input/environment',
                               '/fixture3/input/summary', '/fixture3/input/untracked', '2', cid, 'fixture'])
            successor_line, successor_json = successor.split('\n', 1)
            if not successor_line.startswith('checkpoint/created '):
                raise ValueError('successor capture failed')
            next_cid = successor_line.split(' ')[1]
            successor_doc = json.loads(successor_json)
            if successor_doc['payload']['parent'] != cid or successor_doc['payload']['epoch'] != 2:
                raise ValueError('successor binding mismatch')
            recovery = guest(['recovery', '/fixture3/store', cid, next_cid,
                              '/fixture3/input/seed', 'fixture'])
            recovery_line, recovery_json = recovery.split('\n', 1)
            if not recovery_line.startswith('recovery/recorded '):
                raise ValueError('recovery record failed')
            recovery_cid = recovery_line.split(' ')[1]
            if json.loads((fixture / 'store' / recovery_cid).read_text()) != json.loads(recovery_json):
                raise ValueError('recovery record readback mismatch')
            recovery_args = ['/fixture3/store', recovery_cid, v['did'], cid, next_cid, 'fixture', '2']
            if guest(['verify-recovery', *recovery_args]) != 'recovery/verified\n':
                raise ValueError('recovery verification failed')
            wrong_epoch = recovery_args.copy(); wrong_epoch[-1] = '3'
            if guest(['verify-recovery', *wrong_epoch], 1) != 'recovery/contract\n':
                raise ValueError('wrong recovery epoch accepted')
            if a.local_archive:
                contract = [cid, v['did'], v['profile'], v['policy'], v['environment'], '1', 'root', 'fixture']
                denied = guest(['archive', '/fixture3/denied', '/fixture3/store', *contract,
                                'fixture/public', '/usr/bin/gh'], 1)
                if denied != 'archive/private-repository-unverified\n' or (fixture/'denied').exists():
                    raise ValueError(('privacy control failed', denied))
                stored = guest(['archive', '/fixture3/archive-work', '/fixture3/store', *contract,
                                'fixture/private', '/usr/bin/gh'])
                parts = stored.split()
                if len(parts) != 4 or parts[0] != 'archive/stored' or parts[1] != cid:
                    raise ValueError(('archive result', stored))
                tree = git('remote.git', 'rev-parse', 'refs/heads/itonami/archive/'+cid+'^{tree}').strip()
                if tree != parts[2]: raise ValueError('remote tree mismatch')
                remote_restore = guest(['archive-restore', '/fixture3/archive-restore-work',
                                        '/fixture3/archive-restored', *contract, 'fixture/private', '/usr/bin/gh'])
                if not remote_restore.startswith('archive-restore/restored '+cid+' '):
                    raise ValueError(('archive restore result', remote_restore))
                if snapshot('archive-restored') != before: raise ValueError('archive restored snapshot mismatch')
                wrong = contract.copy(); wrong[3] = v['environment']
                refused = guest(['archive-restore', '/fixture3/wrong-work', '/fixture3/wrong-dest',
                                 *wrong, 'fixture/private', '/usr/bin/gh'], 1)
                if refused != 'archive-restore/readback-refused\n' or (fixture/'wrong-dest').exists():
                    raise ValueError(('archive wrong-policy control', refused))
            if snapshot('repo') != before or snapshot('restored') != after:
                raise ValueError('source or restore changed')
            if helper.digest(compiler) != a.compiler_sha256 or helper.digest(packager) != a.packager_sha256:
                raise ValueError('tool changed')
            receipt.update({'compilerSha256': a.compiler_sha256, 'packagerSha256': a.packager_sha256,
                            'capture': True, 'verify': True, 'restoreMatches': True,
                            'wrongPolicyRefused': True, 'existingDestinationRefused': True,
                            'corruptedBlockRefused': True, 'closure': True,
                            'successorCaptured': True, 'recoveryRecordedAndVerified': True,
                            'wrongRecoveryEpochRefused': True,
                            'localArchiveAndRestore': a.local_archive,
                            'scope': 'local Git diagnostic; privileged adapter; not production'})
            print(json.dumps(receipt))
        finally:
            if fd != 3:
                if saved is None: os.close(3)
                else: os.dup2(saved, 3); os.close(saved)
            os.close(fd)


if __name__ == '__main__': main()
