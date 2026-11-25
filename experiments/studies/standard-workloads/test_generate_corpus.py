#!/usr/bin/env python3
"""Self-test for generate_corpus.py (R7-WORKLOAD, 2026-11-25).

Run with the WfCommons generation environment, from the repository root::

    MPLCONFIGDIR=/tmp/mpl <venv>/bin/python -B \\
        experiments/studies/standard-workloads/test_generate_corpus.py

The canonicalization tests are pure standard library. The reproducibility tests need
WfCommons; when it is not importable they fail (they are not skipped), because this test
exists to prove byte-identical regeneration.

Expectations come from the documented canonicalization contract in README.md (file-ID rule,
fixed timestamps, serialization) and from invariants (reference consistency, idempotence),
plus the committed corpus index, whose hashes are the frozen identity of the corpus.
"""

import copy
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent.parent
SPEC = importlib.util.spec_from_file_location("generate_corpus", HERE / "generate_corpus.py")
GEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GEN)

U1 = "11111111-1111-4111-8111-111111111111"
U2 = "22222222-2222-4222-8222-222222222222"
U3 = "33333333-3333-4333-8333-333333333333"


def toy_document():
    """Two tasks a -> b; b is listed first to prove the rule sorts by task ID."""
    return {
        "name": "Toy-synthetic-instance",
        "createdAt": "2026-11-25T14:00:00+08:00",
        "author": {"name": "somebody", "email": "x@y"},
        "schemaVersion": "1.6",
        "workflow": {
            "specification": {
                "tasks": [
                    {"id": "b_00000002", "name": "b", "parents": ["a_00000001"], "children": [],
                     "inputFiles": [U2 + ".dat", U3 + "maq"], "outputFiles": []},
                    {"id": "a_00000001", "name": "a", "parents": [], "children": ["b_00000002"],
                     "inputFiles": [U1], "outputFiles": [U2 + ".dat"]},
                ],
                "files": [{"id": U1, "sizeInBytes": 5}, {"id": U2 + ".dat", "sizeInBytes": 7},
                          {"id": U3 + "maq", "sizeInBytes": 0}],
            },
            "execution": {
                "makespanInSeconds": 0.0,
                "executedAt": "2026-11-25T14:00:00+08:00",
                "tasks": [{"id": "a_00000001", "runtimeInSeconds": 1.0},
                          {"id": "b_00000002", "runtimeInSeconds": 2.0}],
            },
        },
    }


class SeedRuleTests(unittest.TestCase):

    def test_seed_is_first_four_sha256_bytes(self):
        digest = hashlib.sha256(b"7:montage:100:3").digest()
        self.assertEqual(int.from_bytes(digest[:4], "big"), GEN.derive_seed(7, "montage", 100, 3))

    def test_seeds_differ_across_instances(self):
        seeds = {GEN.derive_seed(1, "blast", 100, index) for index in range(20)}
        self.assertEqual(20, len(seeds))


class CanonicalizationTests(unittest.TestCase):

    def test_file_ids_follow_the_documented_rule(self):
        document = GEN.canonicalize(toy_document())
        spec = document["workflow"]["specification"]
        by_id = {task["id"]: task for task in spec["tasks"]}
        # a_00000001 sorts first: its input ordinal 0 (no extension) and output ordinal 0.
        self.assertEqual(["a_00000001.in000"], by_id["a_00000001"]["inputFiles"])
        self.assertEqual(["a_00000001.out000.dat"], by_id["a_00000001"]["outputFiles"])
        # b reuses a's output under a's name; its own new input gets b's ordinal 1 and a
        # dot is inserted before the bare recipe key "maq".
        self.assertEqual(["a_00000001.out000.dat", "b_00000002.in001.maq"],
                         by_id["b_00000002"]["inputFiles"])
        self.assertEqual({"a_00000001.in000": 5, "a_00000001.out000.dat": 7,
                          "b_00000002.in001.maq": 0},
                         {f["id"]: f["sizeInBytes"] for f in spec["files"]})

    def test_timestamps_and_author_are_fixed(self):
        document = GEN.canonicalize(toy_document())
        self.assertEqual(GEN.FIXED_TIMESTAMP, document["createdAt"])
        self.assertEqual(GEN.FIXED_TIMESTAMP, document["workflow"]["execution"]["executedAt"])
        self.assertEqual(GEN.FIXED_AUTHOR, document["author"])

    def test_serialization_is_sorted_compact_with_newline(self):
        data = GEN.serialize({"b": 1, "a": [1, 2]})
        self.assertEqual(b'{"a":[1,2],"b":1}\n', data)

    def test_uuid_permutation_does_not_change_bytes(self):
        first = GEN.serialize(GEN.canonicalize(toy_document()))
        text = json.dumps(toy_document())
        for old, new in ((U1, "aaaaaaaa-0000-4000-8000-000000000000"),
                         (U2, "bbbbbbbb-0000-4000-8000-000000000000"),
                         (U3, "cccccccc-0000-4000-8000-000000000000")):
            text = text.replace(old, new)
        self.assertEqual(first, GEN.serialize(GEN.canonicalize(json.loads(text))))

    def test_undeclared_reference_fails(self):
        document = toy_document()
        document["workflow"]["specification"]["files"].pop()
        with self.assertRaises(GEN.CorpusError):
            GEN.canonicalize(document)

    def test_unreferenced_declared_file_fails(self):
        document = toy_document()
        document["workflow"]["specification"]["files"].append(
            {"id": "44444444-4444-4444-8444-444444444444.x", "sizeInBytes": 1})
        with self.assertRaises(GEN.CorpusError):
            GEN.canonicalize(document)

    def test_non_uuid_file_id_fails(self):
        document = toy_document()
        document["workflow"]["specification"]["files"][0]["id"] = "plain"
        with self.assertRaises(GEN.CorpusError):
            GEN.canonicalize(document)

    def test_asymmetric_edge_fails(self):
        document = toy_document()
        document["workflow"]["specification"]["tasks"][0]["parents"] = []
        with self.assertRaises(GEN.CorpusError):
            GEN.canonicalize(copy.deepcopy(document))


class ReproducibilityTests(unittest.TestCase):
    """Needs WfCommons; proves byte-identical regeneration."""

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="wfgen-standard-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def write_spec(self, payload):
        path = self.temp / "spec.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_two_runs_are_byte_identical(self):
        spec = self.write_spec({
            "corpus": "test", "base_seed": 3, "instances_per_size": 2, "retained_sizes": [100],
            "recipes": [{"recipe": "montage", "sizes": [100],
                         "excluded_sizes": [{"size": 50, "reason": "base graph too large"}]},
                        {"recipe": "blast", "sizes": [50]}]})
        GEN.generate(spec, self.temp / "a")
        GEN.generate(spec, self.temp / "b")
        files_a = sorted(p.relative_to(self.temp / "a") for p in (self.temp / "a").rglob("*.json"))
        files_b = sorted(p.relative_to(self.temp / "b") for p in (self.temp / "b").rglob("*.json"))
        self.assertEqual(files_a, files_b)
        self.assertEqual(5, len(files_a))  # 4 instances + GENERATION.json
        for rel in files_a:
            self.assertEqual((self.temp / "a" / rel).read_bytes(),
                             (self.temp / "b" / rel).read_bytes(), str(rel))

    def test_false_exclusion_is_rejected(self):
        spec = self.write_spec({
            "corpus": "test", "base_seed": 3, "instances_per_size": 1,
            "recipes": [{"recipe": "blast", "sizes": [100],
                         "excluded_sizes": [{"size": 50, "reason": "wrong"}]}]})
        with self.assertRaises(GEN.CorpusError):
            GEN.generate(spec, self.temp / "a")

    def test_committed_instances_regenerate_identically(self):
        """Golden regression: the committed index hashes are the corpus identity."""
        index = json.loads((ROOT / "datasets/wfgen-standard/INDEX.json").read_text("utf-8"))
        chosen = {}
        for entry in index["instances"]:
            chosen.setdefault((entry["recipe"], entry["requested_tasks"]), entry)
        for (recipe, size), entry in sorted(chosen.items()):
            data = GEN.serialize(GEN.canonicalize(GEN.build_document(recipe, size, entry["seed"])))
            self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest(),
                             entry["wfformat_json"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
