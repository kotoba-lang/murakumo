#!/usr/bin/env python3
"""Stage a source-bound native checkpoint write probe; never run a compiler.

The source functions are taken verbatim from the checked-out checkpoint
component and the cases from its test file. The resulting fixture is diagnostic
only and must run with a fresh, exclusively owned FS2 root.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPONENT = ROOT / 'kotoba/git_checkpoint.kotoba'
CASES = ROOT / 'test/native/checkpoint_bounded_write_cases.kotoba'
NAMES = ('cp-bytes', 'cp-arg', 'cp-frame', 'cp-fs', 'cp-read-op', 'cp-cut',
         'cp-piece', 'cp-written-equal?', 'cp-write', 'cp-int', 'cp-meta-field',
         'cp-meta-digits?', 'cp-meta-size', 'cp-size', 'cp-range', 'cp-empty-readable?')


def form(source, name):
    pattern = r'(?m)^\(defn-? ' + re.escape(name) + r'(?=\s)'
    matches = list(re.finditer(pattern, source))
    if len(matches) != 1:
        raise ValueError('missing or duplicate function: ' + name)
    start = matches[0].start()
    next_form = re.search(r'(?m)^\(defn-? ', source[matches[0].end():])
    end = matches[0].end() + next_form.start() if next_form else len(source)
    return source[start:end].rstrip() + '\n'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw_component = COMPONENT.read_bytes()
    raw_cases = CASES.read_bytes()
    component = raw_component.decode('utf-8')
    cases = raw_cases.decode('utf-8')
    forms = [form(component, name) for name in NAMES]
    forms += [form(cases, name) for name in ('cbw-bytes', 'test-checkpoint-bounded-write')]
    unity = ('(ns checkpoint-bounded-fixture (:export [main]))\n' + '\n'.join(forms)
             + '\n(defn main [] :i64 (if (test-checkpoint-bounded-write (cp-arg 0)) 0 1))\n').encode()
    with args.output.open('xb') as out:
        out.write(unity)
    print(json.dumps({'componentSha256': sha(raw_component), 'casesSha256': sha(raw_cases),
                      'fixtureSha256': sha(unity), 'functions': list(NAMES),
                      'scope': 'source assembly only; native execution not performed'}))


if __name__ == '__main__':
    main()
