#!/usr/bin/env python3
"""Contract and mutation tests for compare.py; no simulator output is an expected oracle."""

import copy
import json
from pathlib import Path
import tempfile
import unittest

import compare


class DifferentialContractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.observations = self.root / "observations.json"
        self.document = []
        for base in compare.CASE_BASES:
            workflow_name = base + ".dax" if base in compare.SHAPES else base
            workflow = self.root / workflow_name
            dependency = "" if base == "independent" else "<child ref=\"child\"><parent ref=\"root\"/></child>"
            workflow.write_text(
                "<adag version=\"3.3\" count=\"2\">\n"
                "<job id=\"root\" name=\"root\" runtime=\"1\"><uses name=\"r\" link=\"output\" size=\"1\"/></job>\n"
                "<job id=\"child\" name=\"child\" runtime=\"1\"><uses name=\"c\" link=\"output\" size=\"1\"/></job>\n"
                + dependency + "\n</adag>\n", encoding="utf-8")
            for scheduler in compare.SCHEDULERS:
                self.document.append({
                    "label": base + "-" + scheduler + "-s7",
                    "workflow": str(workflow),
                    "scheduler": scheduler,
                    "vmCount": 4,
                    "speedRatio": 2,
                    "logicalTasks": 2,
                    "completed": True,
                    "tasks": [
                        {"taskId": 1, "vmId": 1, "start": 0.21, "jobFinish": 1.0, "status": 4},
                        {"taskId": 2, "vmId": 2, "start": 1.0, "jobFinish": 2.0, "status": 4},
                    ],
                    "upstreamTasks": [
                        {"jobId": 0, "taskId": 1, "vmId": 0, "start": 0.1275,
                         "finish": 0.9, "jobFinish": 1.0, "status": "4"},
                        {"jobId": 1, "taskId": 2, "vmId": 1, "start": 1.0,
                         "finish": 1.9, "jobFinish": 2.0, "status": "4"},
                    ],
                })
        self.write(self.document)

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, document):
        self.observations.write_text(json.dumps(document), encoding="utf-8")

    def report(self, document=None):
        self.write(self.document if document is None else document)
        return compare.inspect_observations(self.observations)

    def row(self, report, label):
        return next(row for row in report["cases"] if row["label"] == label)

    def test_complete_registered_matrix_satisfies_contract_invariants(self):
        report = self.report()
        self.assertEqual("PASS", report["status"], report["issues"])
        self.assertEqual(12, report["summary"]["comparedCaseCount"])
        self.assertEqual(12, report["summary"]["completeOnBothSidesCaseCount"])
        self.assertEqual(12, report["summary"]["mappingMatchCaseCount"])
        self.assertEqual(0, report["summary"]["currentDependencyViolationCount"])
        self.assertEqual(0, report["summary"]["upstreamDependencyViolationCount"])

    def test_each_missing_case_is_rejected(self):
        for index in range(len(self.document)):
            with self.subTest(index=index):
                document = copy.deepcopy(self.document)
                del document[index]
                report = self.report(document)
                self.assertEqual("FAIL", report["status"])
                self.assertTrue(any(issue["code"] == "case-matrix" for issue in report["issues"]))

    def test_unsuccessful_upstream_task_is_not_counted_as_complete(self):
        document = copy.deepcopy(self.document)
        document[0]["upstreamTasks"][1]["status"] = "3"
        report = self.report(document)
        row = self.row(report, document[0]["label"])
        self.assertEqual("FAIL", report["status"])
        self.assertFalse(row["upstreamAllTasksComplete"])
        self.assertTrue(any(issue["code"] == "upstream-task-contract" for issue in report["issues"]))
        self.assertTrue(any(issue["code"] == "upstream-task-coverage" for issue in report["issues"]))

    def test_current_completion_flag_is_mandatory(self):
        document = copy.deepcopy(self.document)
        document[0]["completed"] = False
        report = self.report(document)
        row = self.row(report, document[0]["label"])
        self.assertEqual("FAIL", report["status"])
        self.assertFalse(row["currentAllTasksComplete"])
        self.assertTrue(any(issue["code"] == "current-completion-flag" for issue in report["issues"]))

    def test_parent_job_must_finish_before_child_starts_on_each_side(self):
        for side, expected_code in (("tasks", "current-dependency-violation"),
                                    ("upstreamTasks", "upstream-dependency-violation")):
            with self.subTest(side=side):
                document = copy.deepcopy(self.document)
                document[0][side][1]["start"] = 0.5
                report = self.report(document)
                self.assertEqual("FAIL", report["status"])
                self.assertTrue(any(issue["code"] == expected_code for issue in report["issues"]),
                                report["issues"])

    def test_vm_mapping_difference_is_descriptive_not_a_correctness_oracle(self):
        document = copy.deepcopy(self.document)
        document[0]["upstreamTasks"][0]["vmId"] = 1
        report = self.report(document)
        row = self.row(report, document[0]["label"])
        self.assertEqual("PASS", report["status"], report["issues"])
        self.assertFalse(row["mappingMatches"])
        self.assertEqual([1], row["mappingMismatchTaskIds"])
        self.assertEqual(1, row["mappingMatchedTasks"])

    def test_independent_emission_order_difference_does_not_fail_invariants(self):
        label = "independent-STATIC-s7"
        document = copy.deepcopy(self.document)
        case = next(case for case in document if case["label"] == label)
        case["tasks"].reverse()
        report = self.report(document)
        row = self.row(report, label)
        self.assertEqual("PASS", report["status"], report["issues"])
        self.assertFalse(row["taskOrderMatches"])
        self.assertTrue(row["mappingMatches"])

    def test_duplicate_json_members_fail_closed(self):
        with self.assertRaises(compare.CheckError):
            compare.decode_json('{"taskId": 1, "taskId": 2}')


if __name__ == "__main__":
    unittest.main()
