#!/usr/bin/env python3
import argparse
import re
import subprocess
import sys
from pathlib import Path

MAX_FILE_BYTES = 100 * 1024 * 1024
WARNING_FILE_BYTES = 50 * 1024 * 1024
SECRET_PATTERNS = (
    ('private key', re.compile(r'BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY')),
    ('cloud credential', re.compile(r'\b(?:AKIA|ASIA)[0-9A-Z]{16}\b')),
    ('Google API key', re.compile(r'\bAIza[0-9A-Za-z_-]{35}\b')),
    ('GitHub token', re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9_]{36,}|github_pat_[A-Za-z0-9_]{20,})\b')),
    ('GitLab token', re.compile(r'\bglpat-[A-Za-z0-9_-]{20,}\b')),
    ('Slack token', re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{10,}\b')),
    ('generic secret key', re.compile(r'\bsk-(?:live|test|proj)-[A-Za-z0-9_-]{16,}\b')),
)
HOST_PATH = re.compile(r'(?:/' + 'Users/' + r'[^/\s<>"\']+|/' + 'home/' + r'[^/\s<>"\']+|[A-Za-z]:[\\/]Users[\\/][^\\\s<>"\']+)')


def forbidden_name(name):
    path = Path(name)
    base = path.name.lower()
    if name == 'output' or name.startswith('output/'):
        return 'generated output must not be committed'
    if name == '.idea' or name.startswith('.idea/'):
        return 'IDE-local configuration must not be committed'
    if base == '.env' or (base.startswith('.env.') and base != '.env.example'):
        return 'environment file may contain credentials'
    if base in ('credentials.json', 'service-account.json', 'local.properties'):
        return 'local credential/configuration file must not be committed'
    if base.endswith(('.pem', '.key', '.p12', '.jks', '.keystore')):
        return 'private key or certificate-store file must not be committed'
    return None


def inspect_text(name, text):
    issues = []
    for label, pattern in SECRET_PATTERNS:
        match = pattern.search(text)
        if match:
            issues.append((name, text.count('\n', 0, match.start()) + 1, label + ' detected'))
    match = HOST_PATH.search(text)
    if match:
        issues.append((name, text.count('\n', 0, match.start()) + 1, 'machine-specific absolute path detected'))
    return issues


def check_upload_safety(root, paths):
    root = Path(root).resolve()
    issues = []
    warnings = []
    for name in sorted(set(paths)):
        path = root / name
        reason = forbidden_name(name)
        if reason:
            issues.append((name, 1, reason))
            continue
        if not path.exists():
            continue
        if path.is_symlink() and not path.resolve().is_relative_to(root):
            issues.append((name, 1, 'symlink escapes repository root'))
            continue
        if not path.is_file():
            continue
        size = path.stat().st_size
        if size > MAX_FILE_BYTES:
            issues.append((name, 1, 'file exceeds the 100 MiB GitHub limit'))
        elif size >= WARNING_FILE_BYTES:
            warnings.append((name, size))
        try:
            text = path.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        issues.extend(inspect_text(name, text))
    return issues, warnings


def discover(root):
    result = subprocess.run(['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise ValueError(result.stderr.decode('utf-8', errors='replace').strip())
    return [item.decode() for item in result.stdout.split(b'\0') if item]


def main(argv=None):
    parser = argparse.ArgumentParser(description='Reject credentials, host paths, local configuration, generated output, and oversized files before publishing.')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--file', action='append', help='check an explicit repository-relative file; bypasses Git discovery')
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        paths = args.file if args.file else discover(root)
        issues, warnings = check_upload_safety(root, paths)
    except (OSError, ValueError) as error:
        print('UPLOAD_SAFETY_ERROR', error, file=sys.stderr)
        return 2
    for name, line, reason in issues:
        print(f'{name}:{line}: {reason}')
    for name, size in warnings:
        print(f'WARNING {name}: {size} bytes exceeds the 50 MiB GitHub recommendation')
    if issues:
        print(f'UPLOAD_SAFETY_FAILED {len(issues)} issue(s)', file=sys.stderr)
        return 1
    print(f'UPLOAD_SAFETY_OK files={len(paths)} warnings={len(warnings)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
