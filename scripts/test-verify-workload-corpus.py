#!/usr/bin/env python3
"""Self-test for verify-workload-corpus.py (R7-WORKLOAD, 2026-11-25).

Expectations are hand-derived from small hand-built DAGs (levels, widths, edge counts,
byte totals) and from the documented canonical file-ID contract; no expected value is
copied from verifier output.
"""

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().with_name("verify-workload-corpus.py")
SPEC = importlib.util.spec_from_file_location("verify_workload_corpus", SCRIPT)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


def task(task_id, parents, children, inputs=(), outputs=()):
    return {"id": task_id, "name": task_id.split("_")[0], "parents": list(parents),
            "children": list(children), "inputFiles": list(inputs), "outputFiles": list(outputs)}


def diamond():
    """a -> {b, c} -> d; levels 1,2,2,3; widths 1,2,1."""
    return {
        "name": "Toy", "schemaVersion": "1.6",
        "workflow": {
            "specification": {
                "tasks": [
                    task("a_1", [], ["b_2", "c_3"], ["a_1.in000.txt"], ["a_1.out000.dat"]),
                    task("b_2", ["a_1"], ["d_4"], ["a_1.out000.dat"], ["b_2.out000"]),
                    task("c_3", ["a_1"], ["d_4"], ["a_1.out000.dat"], ["c_3.out000"]),
                    task("d_4", ["b_2", "c_3"], [], ["b_2.out000", "c_3.out000"], []),
                ],
                "files": [{"id": "a_1.in000.txt", "sizeInBytes": 10},
                          {"id": "a_1.out000.dat", "sizeInBytes": 20},
                          {"id": "b_2.out000", "sizeInBytes": 0},
                          {"id": "c_3.out000", "sizeInBytes": 5}],
                "metrics": {"numberOfTasks": 4, "numberOfFiles": 4, "numberOfLevels": 3,
                            "maximumWidth": 2, "sumOfFileSizesInBytes": 35},
            },
            "execution": {"makespanInSeconds": 0.0, "executedAt": "x",
                          "tasks": [{"id": "a_1", "runtimeInSeconds": 1.5},
                                    {"id": "b_2", "runtimeInSeconds": 2.0},
                                    {"id": "c_3", "runtimeInSeconds": 0.25},
                                    {"id": "d_4", "runtimeInSeconds": 4.0}]},
        },
    }


# Hand derivation for diamond(): 4 edges (a-b, a-c, b-d, c-d); depth 3; widest level 2;
# runtime 1.5+2+0.25+4 = 7.75; input references a(10) + b(20) + c(20) + d(0+5) = 55;
# distinct file bytes 10+20+0+5 = 35; one zero-size file.
DIAMOND_STATS = {"tasks": 4, "edges": 4, "depth": 3, "max_level_width": 2,
                 "total_runtime_s": 7.75, "total_input_bytes": 55, "files": 4,
                 "zero_size_files": 1, "total_file_bytes": 35}


class AnalyzeTests(unittest.TestCase):

    def test_diamond_statistics(self):
        self.assertEqual(DIAMOND_STATS, VERIFIER.analyze(diamond()))

    def expect_issue(self, document, fragment):
        with self.assertRaises(VERIFIER.CorpusIssue) as raised:
            VERIFIER.analyze(document)
        self.assertIn(fragment, str(raised.exception))

    def test_cycle_is_rejected(self):
        document = diamond()
        tasks = document["workflow"]["specification"]["tasks"]
        tasks[3]["children"] = ["a_1"]
        tasks[0]["parents"] = ["d_4"]
        self.expect_issue(document, "cycle")

    def test_one_sided_edge_is_rejected(self):
        document = diamond()
        document["workflow"]["specification"]["tasks"][3]["parents"] = ["b_2"]
        self.expect_issue(document, "missing from the child's parents")

    def test_unknown_child_is_rejected(self):
        document = diamond()
        document["workflow"]["specification"]["tasks"][3]["children"] = ["z_9"]
        self.expect_issue(document, "unknown child")

    def test_zero_runtime_is_rejected(self):
        document = diamond()
        document["workflow"]["execution"]["tasks"][2]["runtimeInSeconds"] = 0
        self.expect_issue(document, "zero or negative runtime")

    def test_negative_runtime_is_rejected(self):
        document = diamond()
        document["workflow"]["execution"]["tasks"][2]["runtimeInSeconds"] = -1.0
        self.expect_issue(document, "zero or negative runtime")

    def test_missing_execution_record_is_rejected(self):
        document = diamond()
        document["workflow"]["execution"]["tasks"].pop()
        self.expect_issue(document, "no execution record")

    def test_negative_file_size_is_rejected(self):
        document = diamond()
        document["workflow"]["specification"]["files"][0]["sizeInBytes"] = -3
        self.expect_issue(document, "negative size")

    def test_unresolved_reference_is_rejected(self):
        document = diamond()
        document["workflow"]["specification"]["tasks"][3]["inputFiles"].append("ghost")
        self.expect_issue(document, "undeclared file")

    def test_unreferenced_file_is_rejected(self):
        document = diamond()
        document["workflow"]["specification"]["files"].append({"id": "d_4.out000", "sizeInBytes": 1})
        self.expect_issue(document, "referenced by no task")

    def test_non_canonical_file_id_is_rejected(self):
        document = diamond()
        old, new = "a_1.in000.txt", "2b1c6f0e-uuid.txt"
        spec = document["workflow"]["specification"]
        spec["files"][0]["id"] = new
        spec["tasks"][0]["inputFiles"] = [new]
        self.expect_issue(document, "canonical rule")
        self.assertNotEqual(old, new)

    def test_file_named_after_a_later_task_is_rejected(self):
        # The first referencing task in ascending ID order is a_1, not b_2.
        document = diamond()
        spec = document["workflow"]["specification"]
        spec["files"][1]["id"] = "b_2.in000.dat"
        for item in spec["tasks"]:
            item["inputFiles"] = ["b_2.in000.dat" if f == "a_1.out000.dat" else f
                                  for f in item["inputFiles"]]
            item["outputFiles"] = ["b_2.in000.dat" if f == "a_1.out000.dat" else f
                                   for f in item["outputFiles"]]
        self.expect_issue(document, "canonical rule")

    def test_embedded_metrics_disagreement_is_rejected(self):
        document = diamond()
        document["workflow"]["specification"]["metrics"]["numberOfLevels"] = 4
        self.expect_issue(document, "numberOfLevels")


class CorpusTests(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="workload-corpus-"))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        data = (json.dumps(diamond(), sort_keys=True, separators=(",", ":")) + "\n").encode()
        (self.root / "toy/n4").mkdir(parents=True)
        (self.root / "toy/n4/toy-4-000.json").write_bytes(data)
        self.entry = {"recipe": "toy", "requested_tasks": 4, "num_tasks": 4,
                      "wfformat_json": "toy/n4/toy-4-000.json",
                      "sha256": hashlib.sha256(data).hexdigest(),
                      "retained_in_standard_checkout": True, "stats": dict(DIAMOND_STATS)}
        self.absent = {"recipe": "toy", "requested_tasks": 40, "num_tasks": 40,
                       "wfformat_json": "toy/n40/toy-40-000.json", "sha256": "0" * 64,
                       "retained_in_standard_checkout": False, "stats": {}}
        self.write_index([self.entry, self.absent])

    def write_index(self, entries):
        payload = {"n_instances": len(entries), "instances": entries}
        (self.root / "INDEX.json").write_text(json.dumps(payload), encoding="utf-8")

    def test_consistent_corpus_passes(self):
        issues, counts = VERIFIER.verify_corpus(self.root)
        self.assertEqual([], issues)
        self.assertEqual(1, counts["verified"])
        self.assertEqual(1, counts["absent_not_retained"])
        self.assertEqual(1, counts["zero_size_files"])
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, VERIFIER.main([str(self.root)]))
        self.assertIn("0 issues", out.getvalue())

    def test_hash_mismatch_is_an_issue(self):
        path = self.root / "toy/n4/toy-4-000.json"
        path.write_bytes(path.read_bytes() + b" ")
        issues, _ = VERIFIER.verify_corpus(self.root)
        self.assertEqual(1, len(issues))
        self.assertIn("sha256 mismatch", issues[0])

    def test_missing_retained_instance_is_an_issue(self):
        retained = copy.deepcopy(self.absent)
        retained["retained_in_standard_checkout"] = True
        self.write_index([self.entry, retained])
        issues, _ = VERIFIER.verify_corpus(self.root)
        self.assertEqual(1, len(issues))
        self.assertIn("claimed retained", issues[0])

    def test_stale_stats_are_an_issue(self):
        stale = copy.deepcopy(self.entry)
        stale["stats"]["depth"] = 2
        self.write_index([stale])
        issues, _ = VERIFIER.verify_corpus(self.root)
        self.assertEqual(1, len(issues))
        self.assertIn("disagree with indexed stats", issues[0])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, VERIFIER.main([str(self.root)]))

    def test_wrong_num_tasks_is_an_issue(self):
        wrong = copy.deepcopy(self.entry)
        wrong["num_tasks"] = 5
        self.write_index([wrong])
        issues, _ = VERIFIER.verify_corpus(self.root)
        self.assertEqual(1, len(issues))
        self.assertIn("num_tasks", issues[0])

    def test_missing_index_is_fatal(self):
        (self.root / "INDEX.json").unlink()
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, VERIFIER.main([str(self.root)]))

    def test_write_index_round_trip(self):
        manifest = {"corpus": "toy", "spec_sha256": "1" * 64, "environment": {},
                    "seed_rule": "rule", "exclusions": [], "instances": [
                        {k: v for k, v in self.entry.items() if k != "stats"}]}
        (self.root / "GENERATION.json").write_text(json.dumps(manifest), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, VERIFIER.main([str(self.root), "--write-index"]))
        index = json.loads((self.root / "INDEX.json").read_text(encoding="utf-8"))
        self.assertEqual(DIAMOND_STATS, index["instances"][0]["stats"])
        self.assertEqual("GENERATION.json", index["auxiliary_files"][0]["file"])
        issues, _ = VERIFIER.verify_corpus(self.root)
        self.assertEqual([], issues)


if __name__ == "__main__":
    unittest.main(verbosity=2)
