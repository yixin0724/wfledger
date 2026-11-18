#!/usr/bin/env python3

import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().with_name("check-language.py")
SPEC = importlib.util.spec_from_file_location("check_language", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)

def cjk(*codepoints):

    return "".join(chr(code) for code in codepoints)

class DetectionTests(unittest.TestCase):

    def test_simplified_chinese_is_rejected(self):
        self.assertTrue(CHECKER.CJK.search(cjk(0x8BC1, 0x636E, 0x94FE)))
        self.assertTrue(CHECKER.CJK.search("a mixed line " + cjk(0x4FA7, 0x8F66) + " here"))

    def test_traditional_chinese_is_rejected(self):
        self.assertTrue(CHECKER.CJK.search(cjk(0x8B49, 0x64DA, 0x93C8)))

    def test_fullwidth_punctuation_is_rejected(self):
        self.assertTrue(CHECKER.CJK.search("hello" + cjk(0xFF0C) + "world"))
        self.assertTrue(CHECKER.CJK.search("done" + cjk(0x3002)))

    def test_other_scripts_are_allowed(self):

        for allowed in ("Ubersicht", "Быстрый старт", "Größe", "α → β", "x ≤ 3, 5 ± 2, 10−3"):
            self.assertIsNone(CHECKER.CJK.search(allowed), allowed)

class ScopeTests(unittest.TestCase):

    def test_text_suffixes(self):
        for name in ("AGENTS.md", "A.java", "scripts/x.py", "Makefile", "LICENSE",
                     "docs/a/b.yml", "datasets/corpus.dax"):
            self.assertTrue(CHECKER.is_text(name), name)

    def test_binary_suffixes_are_skipped(self):
        for name in ("target/x.jar", "report.html.zip", "image.png"):
            self.assertFalse(CHECKER.is_text(name), name)

    def test_dax_fixture_is_exempt_everywhere(self):

        self.assertTrue(CHECKER.is_exempt("datasets/dax/heft/heft-paper-example.dax"))
        self.assertTrue(CHECKER.is_exempt("simulator/src/test/resources/dax/heft/heft-paper-example.dax"))

        self.assertTrue(CHECKER.is_exempt("docs/localization.dax"))
        self.assertFalse(CHECKER.is_exempt("docs/localization.md"))

    def test_exemption_reason_is_documented(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("SHA-256", text)
        self.assertIn("NetworkStudyPlan", text)

class RepositoryScanTests(unittest.TestCase):

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="check-language-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def put(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")
        return name

    def test_clean_tree_reports_nothing(self):
        files = [self.put("docs/a.md", "# Title\n\nEnglish only.\n"),
                 self.put("src/B.java", "// A comment.\nclass B {}\n")]
        self.assertEqual([], CHECKER.find_violations(files, str(self.root)))

    def test_violation_reports_file_line_and_text(self):
        files = [self.put("docs/a.md", "# Title\n\n" + cjk(0x4E2D, 0x6587, 0x4E00, 0x884C) + "\n")]
        violations = CHECKER.find_violations(files, str(self.root))
        self.assertEqual(1, len(violations))
        self.assertEqual("docs/a.md", violations[0]["file"])
        self.assertEqual(3, violations[0]["line"])
        self.assertEqual(cjk(0x4E2D, 0x6587, 0x4E00, 0x884C), violations[0]["text"])

    def test_each_offending_line_is_listed(self):
        files = [self.put("a.py", "# " + cjk(0x7B2C, 0x4E00, 0x884C) + "\nx = 1\n# " + cjk(0x7B2C, 0x4E8C, 0x884C) + "\n")]
        violations = CHECKER.find_violations(files, str(self.root))
        self.assertEqual([1, 3], [item["line"] for item in violations])

    def test_exempt_fixture_is_skipped(self):
        comment = "<!-- " + cjk(0x4E2D, 0x6587, 0x6CE8, 0x91CA) + " -->\n"
        files = [self.put("datasets/dax/x.dax", comment),
                 self.put("simulator/src/test/resources/dax/y.dax", comment)]
        self.assertEqual([], CHECKER.find_violations(files, str(self.root)))

    def test_non_utf8_file_is_skipped_not_crashed(self):
        files = [self.put("blob.bin.md", b"\xff\xfe\x00\x00broken")]
        self.assertEqual([], CHECKER.find_violations(files, str(self.root)))

    def test_missing_file_is_skipped_not_crashed(self):
        self.assertEqual([], CHECKER.find_violations(["docs/absent.md"], str(self.root)))

class CliTests(unittest.TestCase):

    def run_cli(self, *arguments):
        status = []
        with mock.patch.object(CHECKER, "tracked_files", return_value=["a.md"]), \
                mock.patch.object(CHECKER.subprocess, "run",
                                  return_value=mock.Mock(stdout=b"/nonexistent\n")), \
                contextlib.redirect_stdout(io.StringIO()) as captured:
            status.append(CHECKER.main(["check-language.py"] + list(arguments)))
        return status[0], captured.getvalue()

    def test_dirty_tree_exits_one(self):
        with tempfile.TemporaryDirectory() as temporary:
            Path(temporary, "a.md").write_text(cjk(0x4E2D, 0x6587) + "\n", encoding="utf-8")
            status = []
            with mock.patch.object(CHECKER, "tracked_files", return_value=["a.md"]), \
                    mock.patch.object(CHECKER.subprocess, "run",
                                       return_value=mock.Mock(stdout=(temporary + "\n").encode())), \
                    contextlib.redirect_stdout(io.StringIO()) as captured:
                status.append(CHECKER.main(["check-language.py"]))
            self.assertEqual(1, status[0])
            self.assertIn("a.md:1", captured.getvalue())

    def test_clean_tree_exits_zero(self):
        with tempfile.TemporaryDirectory() as temporary:
            Path(temporary, "a.md").write_text("English\n", encoding="utf-8")
            with mock.patch.object(CHECKER, "tracked_files", return_value=["a.md"]), \
                    mock.patch.object(CHECKER.subprocess, "run",
                                       return_value=mock.Mock(stdout=(temporary + "\n").encode())), \
                    contextlib.redirect_stdout(io.StringIO()) as captured:
                status = CHECKER.main(["check-language.py"])
            self.assertEqual(0, status)
            self.assertIn("no CJK characters", captured.getvalue())

    def test_json_report_is_machine_readable(self):
        with tempfile.TemporaryDirectory() as temporary:
            Path(temporary, "a.md").write_text(cjk(0x4E2D, 0x6587) + "\n", encoding="utf-8")
            with mock.patch.object(CHECKER, "tracked_files", return_value=["a.md"]), \
                    mock.patch.object(CHECKER.subprocess, "run",
                                       return_value=mock.Mock(stdout=(temporary + "\n").encode())), \
                    contextlib.redirect_stdout(io.StringIO()) as captured:
                CHECKER.main(["check-language.py", "--json"])
            import json
            report = json.loads(captured.getvalue())
            self.assertEqual(1, len(report["violations"]))
            self.assertEqual("a.md", report["violations"][0]["file"])

if __name__ == "__main__":
    unittest.main(verbosity=1)