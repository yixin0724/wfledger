#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("paper_check", Path(__file__).with_name("check-fgcs-paper.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FgcsPaperCheckTest(unittest.TestCase):
    def test_current_manuscript_passes_factual_and_citation_guards(self):
        self.assertEqual([], MODULE.inspect(Path(__file__).resolve().parents[1] / "paper/fgcs"))

    def test_wrong_doi_and_undefined_citation_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sections").mkdir()
            (root / "references.bib").write_text(
                (Path(__file__).resolve().parents[1] / "paper/fgcs/references.bib").read_text()
                .replace("10.1016/j.future.2021.09.043", "10.1016/j.future.2022.05.014"))
            (root / "sections/test.tex").write_text("\\cite{missingreference}\n")
            issues = MODULE.inspect(root)
            self.assertTrue(any("stale or incorrect" in issue for issue in issues))
            self.assertIn("undefined citation missingreference", issues)

    def test_real_trace_wording_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sections").mkdir()
            shutil_source = Path(__file__).resolve().parents[1] / "paper/fgcs/references.bib"
            (root / "references.bib").write_text(shutil_source.read_text())
            (root / "sections/test.tex").write_text("real scientific workflows")
            self.assertIn("real-execution wording used for characterization DAX", MODULE.inspect(root))


if __name__ == "__main__":
    unittest.main()
