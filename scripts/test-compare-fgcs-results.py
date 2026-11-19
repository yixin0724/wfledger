#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("compare_results", Path(__file__).with_name("compare-fgcs-results.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CompareResultsTest(unittest.TestCase):
    def write(self, root, row):
        path = root / "sensitivity/results.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps([row]))

    def row(self):
        return {field: 0.0 for field in MODULE.CORE_FIELDS} | {
            "label": "case", "completed": True, "logicalTasks": 1, "completedTasks": 1,
            "eventCount": 4, "mapping": {"1": 2}, "makespanSeconds": 3.0,
        }

    def test_core_match_ignores_wall_clock_and_artifact_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left, right = root / "left", root / "right"
            row = self.row()
            self.write(left, row)
            changed = dict(row, simulationNanos=999, peakHeapBytes=1000, artifactBytes=1000)
            self.write(right, changed)
            self.assertEqual((1, []), MODULE.compare(left, right, ["sensitivity"]))

    def test_makespan_and_mapping_differences_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left, right = root / "left", root / "right"
            row = self.row()
            self.write(left, row)
            changed = dict(row, makespanSeconds=4.0, mapping={"1": 3})
            self.write(right, changed)
            _, differences = MODULE.compare(left, right, ["sensitivity"])
            self.assertTrue(any("makespanSeconds" in item for item in differences))
            self.assertTrue(any("mapping" in item for item in differences))


if __name__ == "__main__":
    unittest.main()
