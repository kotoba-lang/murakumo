"""Execute the actual image entry command against a fake runner, offline."""
import json
import pathlib
import subprocess
import tempfile
import time
import unittest


class RegistrationBarrier(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='.itonami-ci-test-', dir='.')
        self.root = pathlib.Path(self.tmp.name).resolve()
        self.secrets = self.root / 'secrets'
        self.secrets.mkdir()
        (self.secrets / 'token').write_text('fixture-token')
        for script in ('config.sh', 'run.sh'):
            p = self.root / script
            p.write_text('#!/bin/sh\ntouch ' + str(self.root / (script + '.called')) + '\n')
            p.chmod(0o700)
        source = pathlib.Path('scripts/itonami-ci/container/Containerfile').read_text()
        cmd = json.loads(next(s[4:] for s in source.splitlines() if s.startswith('CMD ')))
        self.command = cmd[2].replace('/home/runner/actions-runner', str(self.root)).replace('/run/itonami', str(self.secrets))
        self.command = self.command.replace('/home/runner/_work', str(self.root / 'work'))
        self.env = {'PATH': '/usr/bin:/bin', 'RUNNER_URL': 'fixture', 'RUNNER_NAME': 'fixture', 'RUNNER_LABELS': 'fixture'}
        self.proc = None

    def tearDown(self):
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait()
        if self.proc:
            self.proc.communicate(timeout=5)
            self.proc.stdout.close()
            self.proc.stderr.close()
        self.tmp.cleanup()

    def start(self, fast=False):
        command = self.command.replace('sleep 1;', 'sleep 0.001;') if fast else self.command
        self.proc = subprocess.Popen(['/bin/bash', '-c', command], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_registration_waits_for_filter(self):
        self.start()
        time.sleep(0.2)
        self.assertIsNone(self.proc.poll())
        self.assertFalse((self.root / 'config.sh.called').exists())
        self.assertTrue((self.secrets / 'token').exists())
        (self.secrets / 'egress-ready').write_text('ready\n')
        self.assertEqual(self.proc.wait(timeout=5), 0)
        self.assertTrue((self.root / 'run.sh.called').exists())
        self.assertFalse((self.secrets / 'token').exists())

    def test_missing_filter_times_out_without_registration(self):
        self.start(fast=True)
        self.assertEqual(self.proc.wait(timeout=10), 1)
        self.assertFalse((self.root / 'config.sh.called').exists())

    def test_invalid_marker_refuses_registration(self):
        (self.secrets / 'egress-ready').write_text('invalid')
        self.start()
        self.assertEqual(self.proc.wait(timeout=5), 1)
        self.assertFalse((self.root / 'config.sh.called').exists())


if __name__ == '__main__':
    unittest.main()
