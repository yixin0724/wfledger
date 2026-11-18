#!/usr/bin/env python3
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().with_name('check-upload-safety.py')
SPEC = importlib.util.spec_from_file_location('check_upload_safety', SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class UploadSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='upload-safety-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def put(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        return path

    def issues(self, paths):
        issues, _ = CHECKER.check_upload_safety(self.root, paths)
        return issues

    def test_accepts_source_and_public_support_email(self):
        self.put('src/Main.java', 'class Main {}\n')
        self.put('datasets/workflow.json', '{"contact":"support@wfcommons.org"}\n')
        self.assertEqual([], self.issues(['src/Main.java', 'datasets/workflow.json']))

    def test_rejects_private_key_and_tokens(self):
        self.put('a.txt', '-----BEGIN ' + 'PRIVATE KEY-----\n')
        self.put('b.txt', 'github_pat_' + 'abcdefghijklmnopqrstuvwxyz\n')
        self.assertEqual(2, len(self.issues(['a.txt', 'b.txt'])))

    def test_rejects_machine_paths_not_generic_temp_paths(self):
        self.put('a.txt', '/Users/' + 'example/project\n')
        self.put('b.txt', '/home/' + 'example/project\n')
        self.put('c.txt', 'C:\\Users\\example\\project\n')
        self.put('d.txt', '/tmp/example\n')
        self.assertEqual(3, len(self.issues(['a.txt', 'b.txt', 'c.txt', 'd.txt'])))

    def test_rejects_credentials_and_local_configuration(self):
        for name in ('.env', '.env.local', 'secret.pem', 'secret.key', 'store.p12', 'store.jks',
                     'credentials.json', 'service-account.json', 'local.properties', '.idea/workspace.xml'):
            self.put(name, '')
        self.assertEqual(10, len(self.issues([
            '.env', '.env.local', 'secret.pem', 'secret.key', 'store.p12', 'store.jks',
            'credentials.json', 'service-account.json', 'local.properties', '.idea/workspace.xml'])))

    def test_rejects_generated_output_even_when_force_added(self):
        self.put('output/network-ci/result.manifest.json', '{}\n')
        issues = self.issues(['output/network-ci/result.manifest.json'])
        self.assertIn('generated output', issues[0][2])

    def test_rejects_large_file_and_warns_at_github_recommendation(self):
        path = self.root / 'large.bin'
        with path.open('wb') as stream:
            stream.truncate(CHECKER.MAX_FILE_BYTES + 1)
        self.assertIn('100 MiB', self.issues(['large.bin'])[0][2])
        with path.open('r+b') as stream:
            stream.truncate(CHECKER.WARNING_FILE_BYTES)
        issues, warnings = CHECKER.check_upload_safety(self.root, ['large.bin'])
        self.assertEqual([], issues)
        self.assertEqual([('large.bin', CHECKER.WARNING_FILE_BYTES)], warnings)

    def test_rejects_symlink_escaping_repository(self):
        target = Path(self.temporary.name).parent / 'upload-safety-outside-target'
        target.write_text('safe\n', encoding='utf-8')
        self.addCleanup(target.unlink, missing_ok=True)
        (self.root / 'escape').symlink_to(target)
        self.assertIn('symlink escapes', self.issues(['escape'])[0][2])

    def test_cli_reports_explicit_file_and_never_writes(self):
        self.put('good.txt', 'safe\n')
        result = subprocess.run([sys.executable, '-B', str(SCRIPT), '--root', str(self.root),
                                 '--file', 'good.txt'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('UPLOAD_SAFETY_OK', result.stdout)


if __name__ == '__main__':
    unittest.main()
