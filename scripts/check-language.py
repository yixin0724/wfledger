#!/usr/bin/env python3
"""Reject CJK characters in the tracked text files of this repository.

The repository maintains professional technical English, including javadoc,
comments, generated reports, and retained study archives. A single pass over
`git ls-files` catches a stray Chinese sentence before it reaches main.

Two exceptions, both structural rather than stylistic:

* `*.dax` fixtures are frozen bytes. Their SHA-256 is part of a recorded input
  identity (`NetworkStudyPlan` registers the HEFT paper-example hash), and the
  same file also ships as a test resource, so translating a comment inside one
  would silently invalidate the evidence that cites it and break the pair.
* Binary files are skipped; the checker only reads UTF-8 text.

Usage:

    python3 -B scripts/check-language.py            # check the whole tree
    python3 -B scripts/check-language.py --json     # machine-readable report

Exit status is 0 when clean and 1 when a violation is found.
"""

import argparse
import json
import os
import re
import subprocess
import sys

CJK = re.compile('[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\ufe30-\ufe4f\uff00-\uffef]')

TEXT_SUFFIXES = (
    '.md', '.java', '.py', '.cjs', '.js', '.mjs', '.ts', '.xml', '.json', '.jsonl',
    '.yml', '.yaml', '.cff', '.txt', '.html', '.htm', '.css', '.sh', '.dax', '.properties',
)

EXEMPT_PATTERNS = (
    re.compile(r'\.dax$'),
)


def is_text(name):
    base = name.rsplit('/', 1)[-1]
    if base in ('Makefile', 'LICENSE', 'NOTICE'):
        return True
    return name.endswith(TEXT_SUFFIXES)


def is_exempt(name):
    return any(pattern.search(name) for pattern in EXEMPT_PATTERNS)


def tracked_files():
    listing = subprocess.run(['git', 'ls-files', '-z'], stdout=subprocess.PIPE, check=True).stdout
    return [item.decode('utf-8') for item in listing.split(b'\0') if item]


def find_violations(files, root=''):
    violations = []
    for name in files:
        if not is_text(name) or is_exempt(name):
            continue
        path = os.path.join(root, name) if root else name
        try:
            with open(path, encoding='utf-8') as handle:
                lines = handle.read().split('\n')
        except (OSError, UnicodeDecodeError):
            continue
        for number, line in enumerate(lines, 1):
            if CJK.search(line):
                violations.append({
                    'file': name,
                    'line': number,
                    'text': line.strip()[:160],
                })
    return violations


def main(argv):
    parser = argparse.ArgumentParser(description='Reject CJK characters in tracked text files.')
    parser.add_argument('--json', action='store_true', help='emit a JSON report')
    args = parser.parse_args(argv[1:])

    root = subprocess.run(['git', 'rev-parse', '--show-toplevel'],
                          stdout=subprocess.PIPE, check=True).stdout.decode('utf-8').strip()
    files = tracked_files()
    violations = find_violations(files, root)
    if args.json:
        print(json.dumps({
            'checkedFiles': len(files),
            'violations': violations,
        }, ensure_ascii=True, indent=2))
    elif violations:
        for item in violations:
            print('{file}:{line}: {text}'.format(**item))
        print('\n%d CJK line(s) in %d file(s); the repository is maintained in English.'
              % (len(violations), len({item['file'] for item in violations})))
    else:
        print('Checked %d tracked text files: no CJK characters.' % len(files))
    return 1 if violations else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))