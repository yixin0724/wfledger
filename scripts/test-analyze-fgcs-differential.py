#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("differential_analysis", Path(__file__).with_name("analyze-fgcs-differential.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DifferentialAnalysisTest(unittest.TestCase):
    def case(self):
        return {
            "label": "case-STATIC-s7", "scheduler": "STATIC", "workflow": "x.dax",
            "criticalPathTaskIds": [1, 2],
            "tasks": [
                {"taskId": 1, "vmId": 1, "taskFinish": 2.0, "jobFinish": 2.0, "lengthMi": 10, "vmMips": 500},
                {"taskId": 2, "vmId": 3, "taskFinish": 5.0, "jobFinish": 5.0, "lengthMi": 20, "vmMips": 2000},
            ],
            "upstreamTasks": [
                {"taskId": 1, "vmId": 0, "finish": 2.0, "jobFinish": 2.0, "lengthMi": 10, "vmMips": 500},
                {"taskId": 2, "vmId": 1, "finish": 6.0, "jobFinish": 6.0, "lengthMi": 20, "vmMips": 1000},
            ],
        }

    def test_mapping_id_normalization_and_task_delta_are_explicit(self):
        row = MODULE.analyze_case(self.case())
        self.assertEqual(1, row["mappingMatchedTasks"])
        self.assertEqual([2], row["mappingMismatchTaskIds"])
        self.assertEqual(-1.0, row["makespanDeltaSeconds"])
        self.assertEqual([1, 2], row["criticalPathTaskIdsCurrent"])
        self.assertEqual(2, row["taskDifferences"][0]["taskId"])

    def test_different_task_sets_fail(self):
        case = self.case()
        case["upstreamTasks"] = case["upstreamTasks"][:1]
        with self.assertRaisesRegex(ValueError, "task identity sets differ"):
            MODULE.analyze_case(case)


if __name__ == "__main__":
    unittest.main()
