#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("fgcs_revision", Path(__file__).with_name("run-fgcs-revision.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RevisionVerifierTest(unittest.TestCase):
    def populate(self, root):
        (root / "verification").mkdir(parents=True)
        (root / "verification/results.json").write_text(json.dumps({
            "rq1": {"unexpectedOutcomes": 0, "reasonMismatches": 0},
            "rq2": {"unexpectedOutcomes": 0, "reasonMismatches": 0,
                    "actualRejections": 13, "expectedRejections": 13,
                    "actualAcceptances": 5, "expectedAcceptances": 5},
        }))
        (root / "microbench").mkdir()
        (root / "microbench/results.json").write_text(json.dumps({"cases": [{"id": "case", "expected": {"time": 2.0}, "observed": {"time": 2.0}}]}))
        for name, count in (("static-baseline", 21), ("overhead", 45)):
            (root / name).mkdir()
            (root / name / "results.json").write_text(json.dumps([{}] * count))

    def test_verified_revision_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            MODULE.verify(root)

    def test_wrong_microbench_observation_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            (root / "microbench/results.json").write_text(json.dumps({"cases": [{"id": "case", "expected": {"time": 2.0}, "observed": {"time": 3.0}}]}))
            with self.assertRaisesRegex(ValueError, "case: time"):
                MODULE.verify(root)

    def test_expected_observation_pairs_preserve_hand_derivation(self):
        case = {"expected": {"x": 1.0}, "observed": {"x": 1.0}}
        self.assertEqual({"x": (1.0, 1.0)}, MODULE.expected_observed_pairs(case))


if __name__ == "__main__":
    unittest.main()
