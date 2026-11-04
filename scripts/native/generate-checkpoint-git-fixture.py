#!/usr/bin/env python3
"""Stage the current checkpoint component in the prior privileged test fixture.

This source assembler does not build or run native code. The template's Git
adapter is privileged, restricted to a supplied disposable fixture path,
not a production isolation boundary; never use the result on untrusted input.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / 'docs/evidence/awai154-mac-checkpoint-end-to-end-20261103/fixture.kotoba'
COMPONENT = ROOT / 'kotoba/git_checkpoint.kotoba'
PIN = 'a76f3ae0bf449b21c81630496a0e7edc62d14422c45cc2710d2fa1e4e09a0f8a'
PATTERN = re.compile(r'(?m)^\(defn-? ([^\s\[]+)')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def spans(source):
    matches = list(PATTERN.finditer(source))
    names = [match.group(1) for match in matches]
    if len(names) != len(set(names)):
        raise ValueError('duplicate function definition')
    return {match.group(1): (match.start(), matches[i + 1].start() if i + 1 < len(matches) else len(source))
            for i, match in enumerate(matches)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--fixture-parent', required=True, type=Path)
    args = parser.parse_args()
    parent = args.fixture_parent.resolve(strict=True)
    if (args.fixture_parent.is_symlink() or not parent.is_dir() or parent.stat().st_uid != os.getuid() or
            parent.stat().st_mode & 0o077 or parent.parent != Path('/private/tmp') or
            not re.fullmatch(r'awai150-checkpoint-[a-z0-9_]+', parent.name)):
        raise ValueError('expected an owned, private temporary fixture parent')
    raw_template, raw_component = TEMPLATE.read_bytes(), COMPONENT.read_bytes()
    if sha(raw_template) != PIN:
        raise ValueError('diagnostic template changed')
    template, component = raw_template.decode(), raw_component.decode()
    old, current = spans(template), spans(component)
    if 'cp-spawn' not in current or 'cp-spawn' not in old or 'checkpoint-main' not in current:
        raise ValueError('required fixture entry missing')
    # Preserve the privileged fixture cp-spawn and its surrounding host helpers.
    removals = sorted((old[n] for n in current if n in old and n != 'cp-spawn'), reverse=True)
    staged = template
    for start, end in removals:
        staged = staged[:start] + staged[end:]
    anchor = '(defn lower [c :i64]'
    if staged.count(anchor) != 1:
        raise ValueError('fixture anchor changed')
    component_forms = '\n'.join(component[a:b].rstrip() for n, (a, b) in current.items() if n != 'cp-spawn')
    staged = staged.replace(anchor, component_forms + '\n\n' + anchor, 1)
    old_parent = '/private/tmp/awai154-fs2-darwin-gate'
    if staged.count(old_parent) != 3:
        raise ValueError('diagnostic adapter paths changed')
    staged = staged.replace(old_parent, str(parent))
    final = spans(staged)
    if any(n not in final for n in current):
        raise ValueError('incomplete composed source')
    for name, (start, end) in current.items():
        if name != 'cp-spawn':
            actual_start, actual_end = final[name]
            if staged[actual_start:actual_end].strip() != component[start:end].strip():
                raise ValueError('component function changed: ' + name)
    raw = staged.encode()
    with args.output.open('xb') as out:
        out.write(raw)
    print(json.dumps({'componentSha256': sha(raw_component), 'templateSha256': PIN,
                      'fixtureSha256': sha(raw), 'componentFunctions': len(current),
                      'exception': 'privileged test-only cp-spawn',
                      'scope': 'source assembly only; no native execution'}))


if __name__ == '__main__':
    main()
