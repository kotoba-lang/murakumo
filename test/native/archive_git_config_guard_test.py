"""Exercise the archive source's Git-config pattern with the installed Git.

This is a test-only probe. It does not run the native archive or contact GitHub.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "kotoba/github_checkpoint_archive.kotoba"
MATCH = re.search(r'"(\^\(url\[\.\].*\)\$)"', SOURCE.read_text())
if MATCH is None:
    raise RuntimeError("archive Git-config guard not found")
PATTERN = MATCH.group(1)


class ArchiveConfigGuard(unittest.TestCase):
    def test_dangerous_git_config_names_are_detected(self):
        keys = (
            "core.hooksPath", "core.fsmonitor", "core.attributesFile", "core.sshCommand",
            "filter.sample.clean", "filter.sample.process", "filter.sample.required",
            "commit.gpgSign", "gpg.program", "gpg.ssh.program",
            "include.path", "includeIf.gitdir:/example/.path",
            "url.https://example.com/.insteadOf", "http.https://example.com/.sslVerify",
            "remote.origin.proxy", "credential.https://example.com.helper",
        )
        with tempfile.TemporaryDirectory() as directory:
            env = {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
            for index, key in enumerate(keys):
                with self.subTest(key=key):
                    config = str(Path(directory) / f"config-{index}")
                    subprocess.run(["git", "config", "--file", config, key, "value"],
                                   env=env, check=True, capture_output=True)
                    result = subprocess.run(["git", "config", "--file", config, "--name-only",
                                             "--get-regexp", PATTERN], env=env, capture_output=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertTrue(result.stdout)

    def test_ordinary_identity_is_not_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            config = str(Path(directory) / "config")
            env = {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
            subprocess.run(["git", "config", "--file", config, "user.name", "Archive Test"],
                           env=env, check=True, capture_output=True)
            result = subprocess.run(["git", "config", "--file", config, "--name-only",
                                     "--get-regexp", PATTERN], env=env, capture_output=True)
            self.assertEqual((result.returncode, result.stdout), (1, b""))


if __name__ == "__main__":
    unittest.main()
