#!/usr/bin/env python3

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().with_name("check-dataset-indexes.py")
SPEC = importlib.util.spec_from_file_location("check_dataset_indexes", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)

def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()

class Tree:

    def __init__(self, root):
        self.root = Path(root)
        self.datasets = self.root / "datasets"
        self.datasets.mkdir(parents=True)

    def write(self, rel, text):
        path = self.datasets / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return digest(text)

    def file_sha(self, rel):
        return CHECKER.sha256_file(self.datasets / rel)

    def write_index(self, family, payload):

        self.write(family + "/INDEX.json", json.dumps(payload))
        self.write_top()

    def write_top(self):
        families = []
        for name in ("dax", "wfformat"):
            index_rel = name + "/INDEX.json"
            index = json.loads((self.datasets / index_rel).read_text(encoding="utf-8"))
            families.append({
                "family": name,
                "index": index_rel,
                "index_sha256": self.file_sha(index_rel),
                "n_instances": index["n_instances"],
            })
        self.write("INDEX.json", json.dumps({"families": families}))

def build_consistent_tree(root):
    tree = Tree(root)
    dax_sha = tree.write("dax/cybershake/n30/CyberShake_30.dax", "<adag/>")
    aux_sha = tree.write("dax/heft/heft-paper-example.dax", "<paper/>")
    wf_sha = tree.write("wfformat/montage/n100/montage-100-000.json", "{}")
    wf_manifest_sha = tree.write("wfformat/montage/n100/montage-100-000.manifest.json", "{}")
    dax_index = {
        "n_instances": 1,
        "n_auxiliary_files": 1,
        "instances": [
            {"family": "cybershake", "dax_xml": "cybershake/n30/CyberShake_30.dax",
             "sha256": dax_sha},
        ],
        "auxiliary_files": [
            {"file": "heft/heft-paper-example.dax", "sha256": aux_sha,
             "role": "paper example"},
        ],
    }
    wfformat_index = {
        "n_instances": 2,
        "instances": [
            {"recipe": "montage",
             "wfformat_json": "montage/n100/montage-100-000.json",
             "sha256": wf_sha,
             "dax_xml": None,
             "manifest": "montage/n100/montage-100-000.manifest.json",
             "manifest_sha256": wf_manifest_sha,
             "retained_in_standard_checkout": True},
            {"recipe": "montage",
             "wfformat_json": "montage/n400/montage-400-000.json",
             "sha256": "0" * 64,
             "dax_xml": None,
             "manifest": "montage/n400/montage-400-000.manifest.json",
             "manifest_sha256": "1" * 64,
             "retained_in_standard_checkout": False},
        ],
    }
    tree.write("dax/INDEX.json", json.dumps(dax_index))
    tree.write("wfformat/INDEX.json", json.dumps(wfformat_index))
    tree.write_top()
    return tree

class CheckerTestCase(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.mkdtemp(prefix="dataset-index-check-")
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.tree = build_consistent_tree(self.temp)

    def check(self):
        return CHECKER.check_indexes(self.tree.root)

    def load_index(self, family):
        path = self.tree.datasets / family / "INDEX.json"
        return json.loads(path.read_text(encoding="utf-8"))

class ConsistentTreeTests(CheckerTestCase):

    def test_consistent_tree_passes(self):
        issues, counts = self.check()
        self.assertEqual([], issues)
        self.assertEqual(2, counts["families"])
        self.assertEqual(4, counts["entries"])
        self.assertEqual(3, counts["present_verified"])
        self.assertEqual(1, counts["absent_not_retained"])
        self.assertEqual(1, counts["manifests_verified"])
        self.assertEqual(4, counts["disk_files"])
        self.assertLess(counts["tracked_files"], 0)

    def test_absent_manifest_passes(self):
        (self.tree.datasets / "wfformat/montage/n100"
         / "montage-100-000.manifest.json").unlink()
        issues, counts = self.check()
        self.assertEqual([], issues)
        self.assertEqual(0, counts["manifests_verified"])

    def test_absent_auxiliary_not_retained_passes(self):
        dax_index = self.load_index("dax")
        dax_index["auxiliary_files"].append({
            "file": "upstream/README.md", "sha256": "2" * 64,
            "role": "upstream documentation", "retained_in_standard_checkout": False,
        })
        dax_index["n_auxiliary_files"] = 2
        self.tree.write_index("dax", dax_index)
        issues, counts = self.check()
        self.assertEqual([], issues)
        self.assertEqual(2, counts["absent_not_retained"])

    def test_main_accepts_a_consistent_tree(self):
        with contextlib.redirect_stdout(io.StringIO()) as captured:
            self.assertEqual(0, CHECKER.main(["--root", str(self.tree.root)]))
        self.assertIn("0 issues", captured.getvalue())

class AdditionalFamilyTests(CheckerTestCase):

    def add_family(self):
        inst_sha = self.tree.write("wfgen-standard/blast/n50/blast-50-000.json", "{}\n")
        gen_sha = self.tree.write("wfgen-standard/GENERATION.json", "{\"n\": 2}\n")
        index = {
            "n_instances": 2,
            "n_auxiliary_files": 1,
            "instances": [
                {"recipe": "blast", "wfformat_json": "blast/n50/blast-50-000.json",
                 "sha256": inst_sha, "retained_in_standard_checkout": True},
                {"recipe": "blast", "wfformat_json": "blast/n1000/blast-1000-000.json",
                 "sha256": "4" * 64, "retained_in_standard_checkout": False},
            ],
            "auxiliary_files": [{"file": "GENERATION.json", "sha256": gen_sha,
                                 "role": "generation manifest"}],
        }
        self.tree.write("wfgen-standard/INDEX.json", json.dumps(index))
        top = json.loads((self.tree.datasets / "INDEX.json").read_text(encoding="utf-8"))
        top["families"].append({
            "family": "wfgen-standard",
            "index": "wfgen-standard/INDEX.json",
            "index_sha256": self.tree.file_sha("wfgen-standard/INDEX.json"),
            "n_instances": 2,
        })
        self.tree.write("INDEX.json", json.dumps(top))

    def test_additional_family_passes(self):
        self.add_family()
        issues, counts = self.check()
        self.assertEqual([], issues)
        self.assertEqual(3, counts["families"])
        self.assertEqual(7, counts["entries"])
        self.assertEqual(2, counts["absent_not_retained"])

    def test_additional_family_stray_file_is_an_issue(self):
        self.add_family()
        self.tree.write("wfgen-standard/blast/n50/blast-50-001.json", "{}\n")
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("wfgen-standard/blast/n50/blast-50-001.json", issues[0])

    def test_additional_family_unanchored_edit_is_an_issue(self):
        self.add_family()
        index_path = self.tree.datasets / "wfgen-standard/INDEX.json"
        index = json.loads(index_path.read_text(encoding="utf-8"))
        index["note"] = "edited after anchoring"
        index_path.write_text(json.dumps(index), encoding="utf-8")
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("family index hash mismatch", issues[0])

class TamperingTests(CheckerTestCase):

    def test_tampered_file_hash_mismatch(self):
        self.tree.write("dax/cybershake/n30/CyberShake_30.dax", "<adag tampered/>")
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("recorded hash mismatch", issues[0])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, CHECKER.main(["--root", str(self.tree.root)]))

    def test_missing_retained_instance_is_an_issue(self):
        (self.tree.datasets / "wfformat/montage/n100"
         / "montage-100-000.json").unlink()
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("missing but the entry claims", issues[0])

    def test_missing_retained_auxiliary_is_an_issue(self):
        (self.tree.datasets / "dax/heft/heft-paper-example.dax").unlink()
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("missing but the entry claims", issues[0])

    def test_manifest_hash_mismatch_is_an_issue(self):
        self.tree.write("wfformat/montage/n100/montage-100-000.manifest.json",
                        '{"seed": 43}')
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("manifest hash mismatch", issues[0])

    def test_broken_index_chain_is_an_issue(self):
        dax_index = self.load_index("dax")
        dax_index["note"] = "edited without re-chaining the top-level index"
        self.tree.write("dax/INDEX.json", json.dumps(dax_index))
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("family index hash mismatch", issues[0])

    def test_wrong_n_instances_is_an_issue(self):
        dax_index = self.load_index("dax")
        dax_index["n_instances"] = 7
        self.tree.write_index("dax", dax_index)
        issues, _ = self.check()
        self.assertEqual(2, len(issues))
        self.assertIn("n_instances is 7 but the index lists 1", " ".join(issues))

    def test_wrong_counts_by_runtime_is_an_issue(self):
        dax_index = self.load_index("dax")
        dax_index["instances"][0]["runtime"] = "makeflow"
        dax_index["counts_by_runtime"] = {"makeflow": 2}
        self.tree.write_index("dax", dax_index)
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("counts_by_runtime", issues[0])

    def test_path_traversal_entry_is_an_issue(self):
        dax_index = self.load_index("dax")
        dax_index["instances"][0]["dax_xml"] = "../outside.dax"
        self.tree.write_index("dax", dax_index)
        issues, _ = self.check()

        self.assertEqual(2, len(issues))
        self.assertIn("invalid instance file path", issues[0])
        self.assertIn("present on disk but listed by no dataset index", issues[1])

    def test_directory_listed_as_a_file_is_an_issue(self):
        (self.tree.datasets / "dax/heft/bundle.dax").mkdir()
        dax_index = self.load_index("dax")
        dax_index["auxiliary_files"].append({"file": "heft/bundle.dax",
                                             "sha256": "3" * 64, "role": "not a file"})
        dax_index["n_auxiliary_files"] = 2
        self.tree.write_index("dax", dax_index)
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("listed path is a directory", issues[0])

class DriftTests(CheckerTestCase):

    def test_unindexed_present_file_is_an_issue(self):
        self.tree.write("dax/stray/orphan.dax", "<orphan/>")
        issues, _ = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("present on disk but listed by no dataset index", issues[0])

    def test_tracked_unindexed_file_is_an_issue(self):
        tracked = [
            "datasets/README.md",
            "datasets/dax/cybershake/n30/CyberShake_30.dax",
            "datasets/dax/INDEX.json",
            "datasets/dax/stray/orphan.dax",
        ]
        with mock.patch.object(CHECKER, "tracked_datasets_files",
                               return_value=tracked) as _:
            issues, counts = self.check()
        self.assertEqual(1, len(issues))
        self.assertIn("tracked by Git but listed by no dataset index", issues[0])
        self.assertEqual(4, counts["tracked_files"])

    def test_git_unavailable_skips_the_tracked_check(self):
        with mock.patch.object(CHECKER, "tracked_datasets_files", return_value=None):
            issues, counts = self.check()
        self.assertEqual([], issues)
        self.assertLess(counts["tracked_files"], 0)

class FatalInputTests(CheckerTestCase):

    def test_missing_top_index_is_fatal(self):
        (self.tree.datasets / "INDEX.json").unlink()
        with self.assertRaises(ValueError):
            self.check()
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, CHECKER.main(["--root", str(self.tree.root)]))

    def test_top_index_without_families_is_fatal(self):
        self.tree.write("INDEX.json", json.dumps({"families": []}))
        with self.assertRaises(ValueError):
            self.check()

    def test_family_index_missing_on_disk_is_fatal(self):
        (self.tree.datasets / "wfformat/INDEX.json").unlink()
        with self.assertRaises(ValueError):
            self.check()

if __name__ == "__main__":
    unittest.main(verbosity=2)
