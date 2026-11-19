#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("fgcs_matrix", Path(__file__).with_name("run-fgcs-matrix.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FgcsMatrixVerifierTest(unittest.TestCase):
    def populate(self, root):
        for name, count in MODULE.COUNTS.items():
            if name == "overhead":
                count = 9
            rows = []
            for index in range(count):
                label = f"{name}-r{index}"
                if name == "overhead":
                    label += "-off-" if index % 3 == 0 else "-events-"
                rows.append({
                    "label": label,
                    "completed": True,
                    "logicalTasks": 1,
                    "completedTasks": 1,
                    "energyResidual": 0.0,
                    "costResidual": 0.0,
                    "makespanResidual": 0.0,
                    "tailResidual": 0.0,
                    "dependencyResidual": 0.0,
                    "eventCount": 0 if "-off-" in label else 1,
                    "artifactBytes": 1,
                })
            path = root / name / "results.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(rows), encoding="utf-8")

    def test_valid_matrix_passes_completion_and_recording_invariants(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            expected = sum(MODULE.COUNTS.values()) - MODULE.COUNTS["overhead"] + 9
            self.assertEqual(MODULE.verify(root, 1), expected)

    def test_off_run_with_retained_events_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.populate(root)
            path = root / "overhead/results.json"
            rows = json.loads(path.read_text())
            rows[0]["eventCount"] = 1
            path.write_text(json.dumps(rows), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "OFF retained events"):
                MODULE.verify(root, 1)

    def test_missing_matrix_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "missing"):
                MODULE.verify(Path(directory), 1)


if __name__ == "__main__":
    unittest.main()
