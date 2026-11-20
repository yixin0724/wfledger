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

    def populate(self, root):
        root.mkdir(parents=True)
        (root / "verification").mkdir()
        (root / "microbench").mkdir()
        (root / "verification/results.json").write_text(json.dumps({
            "rq1": {"genuineCases": 1}, "rq2": {"corruptedCases": 1},
            "cases": [{"id": "case", "observations": [{"validator": "JAVA", "expected": "ACCEPT",
                                                       "actual": "ACCEPT", "reasonMatched": True,
                                                       "actualReason": "valid reason", "requiredReasonFragment": "valid"}]}],
        }))
        (root / "microbench/results.json").write_text(json.dumps({"cases": [
            {"id": "case", "expected": {"time": 2.0}, "observed": {"time": 2.0}},
        ]}))
        for name in ("rerun", "contracts"):
            (root / name).mkdir()
        (root / "rerun/results.json").write_text(json.dumps({
            "summary": {"unexpectedOutcomes": 0},
            "cases": [{"caseId": "case", "expectedVerdict": "IDENTICAL_CORE",
                       "actualVerdict": "IDENTICAL_CORE", "expectationMet": True}],
        }))
        (root / "contracts/results.json").write_text(json.dumps({"cases": [
            {"id": "case", "passed": True, "expected": {"time": 2.0}, "observed": {"time": 2.0}},
        ]}))

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

    def test_revision_comparison_rejects_changed_rerun_verdict(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            self.populate(left)
            self.populate(right)
            path = right / "rerun/results.json"
            document = json.loads(path.read_text())
            document["cases"][0]["actualVerdict"] = "DIVERGED"
            path.write_text(json.dumps(document))
            _, differences = MODULE.compare_revision(left, right)
            self.assertIn("case: actualVerdict differs", differences)

    def test_duplicate_labels_and_missing_core_fields_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            row = self.row()
            self.write(left, row)
            (left / "sensitivity/results.json").write_text(json.dumps([row, row]))
            self.write(right, row)
            with self.assertRaisesRegex(ValueError, "duplicate label"):
                MODULE.load(left, "sensitivity")
            with self.assertRaisesRegex(ValueError, "duplicate label"):
                MODULE.unique_index([row, row], "label", "duplicate-check")
            (left / "sensitivity/results.json").write_text(json.dumps([row]))
            changed = self.row()
            del changed["makespanSeconds"]
            self.write(right, changed)
            _, differences = MODULE.compare(left, right, ["sensitivity"])
            self.assertIn("case: makespanSeconds presence differs", differences)

    def test_extended_results_are_required_when_requested(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            self.populate(left)
            self.populate(right)
            (right / "rerun/results.json").unlink()
            _, differences = MODULE.compare_revision(left, right, require_extended=True)
            self.assertIn("rerun: result presence differs", differences)
            (left / "rerun/results.json").unlink()
            _, differences = MODULE.compare_revision(left, right, require_extended=True)
            self.assertIn("rerun: required round-two results are missing", differences)

    def test_original_baseline_comparison_checks_all_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            for root in (left, right):
                for name in ("makespan-baseline", "makespan-scale"):
                    (root / name).mkdir(parents=True)
                    (root / name / "results.json").write_text(json.dumps([{
                        "dataset": "workflow.dax", "algorithm": "HEFT", "makespanSeconds": 2.0,
                        "modeledProcessingCost": 10.0,
                    }]))
            count, differences = MODULE.compare_original_baselines(left, right)
            self.assertEqual(2, count)
            self.assertEqual([], differences)
            path = right / "makespan-baseline/results.json"
            document = json.loads(path.read_text())
            document[0]["modeledProcessingCost"] = 11.0
            path.write_text(json.dumps(document))
            _, differences = MODULE.compare_original_baselines(left, right)
            self.assertTrue(any("modeledProcessingCost" in item for item in differences))

    def test_revision_comparison_matches_independent_semantics(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            self.populate(left)
            self.populate(right)
            count, differences = MODULE.compare_revision(left, right)
            self.assertEqual(1, count)
            self.assertEqual([], differences)

    def test_revision_comparison_rejects_missing_validator_and_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            self.populate(left)
            self.populate(right)
            path = right / "verification/results.json"
            document = json.loads(path.read_text())
            document["cases"][0]["observations"] = []
            path.write_text(json.dumps(document))
            _, differences = MODULE.compare_revision(left, right)
            self.assertIn("case: validator sets differ", differences)
            path = left / "verification/results.json"
            document = json.loads(path.read_text())
            (right / "verification/results.json").write_text(json.dumps(document))
            document["cases"][0]["observations"][0]["requiredReasonFragment"] = "missing"
            path.write_text(json.dumps(document))
            _, differences = MODULE.compare_revision(left, right)
            self.assertTrue(any("semantic reason fragment" in item for item in differences))

    def test_revision_comparison_rejects_changed_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            left, right = Path(directory) / "left", Path(directory) / "right"
            self.populate(left)
            self.populate(right)
            path = right / "microbench/results.json"
            document = json.loads(path.read_text())
            document["cases"][0]["observed"]["time"] = 3.0
            path.write_text(json.dumps(document))
            _, differences = MODULE.compare_revision(left, right)
            self.assertIn("case.observed: time differs", differences)


if __name__ == "__main__":
    unittest.main()
