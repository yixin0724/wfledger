#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("artifact_package", Path(__file__).with_name("package-fgcs-artifact.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ArtifactPackageTest(unittest.TestCase):
    def test_result_collection_preserves_structure_and_removes_host_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "results/differential"
            source.mkdir(parents=True)
            (source / "results.json").write_text(json.dumps([{"workflow": str(Path(__file__).resolve().parents[1] / "datasets/x.dax")}]))
            copied = MODULE.collect_results(root / "results", root / "out")
            self.assertEqual(1, copied)
            text = (root / "out/differential/results.json").read_text()
            self.assertIn('"workflow": "datasets/x.dax"', text)
            self.assertNotIn(str(Path(__file__).resolve().parents[1]), text)

    def test_embedded_verifier_checks_hashes_and_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "results/differential").mkdir(parents=True)
            (root / "results/differential/results.json").write_text("[]")
            (root / "artifact-metadata.json").write_text(json.dumps({"sourceCommit": "abc", "expectedResultRows": {"differential": 0}}))
            (root / "verify-artifact.py").write_text(MODULE.verifier_text())
            (root / "SHA256SUMS").write_text("")
            import subprocess
            result = subprocess.run(["python3", str(root / "verify-artifact.py")], text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("SOURCE_COMMIT=abc", result.stdout)


if __name__ == "__main__":
    unittest.main()
