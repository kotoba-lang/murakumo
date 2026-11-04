#!/usr/bin/env python3
"""Compose a source-bound, local-Git-only archive diagnostic.

The inherited adapter is privileged, privacy is stubbed, and all remote URLs
are replaced by a disposable local repository. This is not production code.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = Path(__file__).with_name('generate-checkpoint-git-fixture.py')
ARCHIVE = ROOT / 'kotoba/github_checkpoint_archive.kotoba'


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fixture-parent', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    base = a.output.with_suffix('.base.kotoba')
    r = subprocess.run(['/usr/bin/python3', str(GENERATOR), '--fixture-parent', str(a.fixture_parent),
                        '--output', str(base)], capture_output=True, text=True, check=True, timeout=30)
    receipt = json.loads(r.stdout)
    source = base.read_text(); marker = '(defn main [] :i64 (checkpoint-main))'
    if source.count(marker) != 1: raise ValueError('checkpoint main changed')
    source = source.replace(marker, '')
    raw_archive = ARCHIVE.read_bytes(); archive = raw_archive.decode()
    archive = archive[archive.index('(defn- ga-repo? '):]
    start = archive.index('(defn- ga-private? ')
    end = archive.index('\n;; Reject effective Git configuration', start)
    archive = archive[:start] + '(defn- ga-private? [gh :string repo :string] :bool (= repo "fixture/private"))\n' + archive[end:]
    remote = '(str "https://github.com/" repo ".git")'
    if archive.count(remote) != 2: raise ValueError('archive remote expressions changed')
    local_remote = '"file://' + str(a.fixture_parent.resolve(strict=True)) + '/fixture3/remote.git"'
    archive = archive.replace(remote, local_remote)
    main = '''\n(defn main [] :i64
  (let [op (cp-arg 0)]
    (cond (= op "archive")
      (let [v (ga-archive (cp-arg 1) (cp-arg 2) (cp-arg 3) (cp-arg 4) (cp-arg 5) (cp-arg 6) (cp-arg 7) (cp-int (cp-arg 8)) (cp-arg 9) (cp-arg 10) (cp-arg 11) (cp-arg 12)) x (cp-say v)] (if (= (string-index-of v "archive/stored ") 0) 0 1))
      (= op "archive-restore")
      (let [v (ga-restore (cp-arg 1) (cp-arg 2) (cp-arg 3) (cp-arg 4) (cp-arg 5) (cp-arg 6) (cp-arg 7) (cp-int (cp-arg 8)) (cp-arg 9) (cp-arg 10) (cp-arg 11) (cp-arg 12)) x (cp-say v)] (if (= (string-index-of v "archive-restore/restored ") 0) 0 1))
      (= op "config-test") (if (ga-config-safe? (cp-arg 1)) 0 1)
      :else (checkpoint-main))))\n'''
    unity = (source + '\n' + archive + main).encode()
    with a.output.open('xb') as out: out.write(unity)
    receipt.update({'archiveSourceSha256':sha(raw_archive), 'archiveFixtureSha256':sha(unity),
                    'scope':'source assembly only; local transport and privacy stub; no native execution'})
    print(json.dumps(receipt))


if __name__ == '__main__': main()
