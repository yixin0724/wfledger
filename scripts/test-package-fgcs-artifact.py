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

    def test_manifest_relocation_preserves_input_identity_and_declared_paths(self):
        project = Path(__file__).resolve().parents[1]
        base = project / "paper/tmp/fgcs-revision"
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory:
            root = Path(directory)
            source = root / "input.dax"
            source.write_text("workflow fixture")
            results = root / "archive-results"
            retained = results / root.name / "input.dax"
            retained.parent.mkdir(parents=True)
            retained.write_bytes(source.read_bytes())
            manifest = results / "case/result.manifest.json"
            manifest.parent.mkdir()
            original_path = str(source)
            document = {
                "configuration": {"workflowPaths": [original_path]},
                "inputs": [{"path": original_path, "sha256": MODULE.sha256(source), "sizeBytes": source.stat().st_size}],
                "result": {"workflowOutcomes": [{"path": original_path}]},
                "provenance": {"execution": {"workingDirectory": str(project)},
                               "core": {"codeSource": "file:" + str(project) + "/simulator/target/classes/"}},
            }
            manifest.write_text(json.dumps(document))
            original_hash = MODULE.sha256(manifest)
            MODULE.relocate_manifest(manifest, results)
            relocated = json.loads(manifest.read_text())
            expected = "/results/" + root.name + "/input.dax"
            self.assertEqual("/", relocated["provenance"]["execution"]["workingDirectory"])
            self.assertEqual([expected], relocated["configuration"]["workflowPaths"])
            self.assertEqual(expected, relocated["inputs"][0]["path"])
            self.assertEqual(expected, relocated["result"]["workflowOutcomes"][0]["path"])
            self.assertEqual(MODULE.sha256(source), relocated["inputs"][0]["sha256"])
            self.assertNotEqual(original_hash, MODULE.sha256(manifest))

    def test_packaged_input_resolver_checks_actual_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "results/input.dax"
            source.parent.mkdir()
            source.write_text("workflow")
            manifest = root / "results/case/result.manifest.json"
            manifest.parent.mkdir()
            manifest.write_text(json.dumps({"inputs": [{"path": "/results/input.dax",
                                                        "sizeBytes": source.stat().st_size,
                                                        "sha256": MODULE.sha256(source)}]}))
            self.assertEqual(1, MODULE.validate_manifest_inputs(root))
            source.write_text("corrupted")
            with self.assertRaisesRegex(ValueError, "identity mismatch"):
                MODULE.validate_manifest_inputs(root)
            manifest.write_text(json.dumps({"inputs": [{"path": "/results/missing.dax",
                                                        "sizeBytes": 0, "sha256": "0" * 64}]}))
            with self.assertRaisesRegex(ValueError, "missing"):
                MODULE.validate_manifest_inputs(root)

    def test_warmup_derivations_follow_the_same_retention_filter(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            warmup = root / "overhead/evidence/warmup/inputs"
            warmup.mkdir(parents=True)
            (warmup / "stale.derivation.json").write_text("{}")
            output = root / "artifact/results"
            output.mkdir(parents=True)
            self.assertEqual(0, MODULE.collect_derivations(root, output, root))

    def test_detached_collect_then_relocate_preserves_absolute_lineage(self):
        base = Path("/private/tmp/fgcs-clean-reproduction-round2")
        created_base = not base.exists()
        base.mkdir(parents=True, exist_ok=True)
        try:
            with tempfile.TemporaryDirectory(dir=base) as directory:
                clean_root = Path(directory)
                source_results = clean_root / "paper/tmp/fgcs-round2"
                source = source_results / "evidence/inputs/chain.dax"
                source.parent.mkdir(parents=True)
                source.write_text("workflow")
                manifest = source_results / "evidence/genuine/result.manifest.json"
                manifest.parent.mkdir(parents=True)
                original_path = str(source)
                manifest.write_text(json.dumps({
                    "configuration": {"workflowPaths": [original_path]},
                    "inputs": [{"path": original_path, "sha256": MODULE.sha256(source), "sizeBytes": source.stat().st_size}],
                    "result": {"workflowOutcomes": [{"path": original_path}]},
                    "provenance": {"execution": {"workingDirectory": str(clean_root)},
                                   "core": {"codeSource": "file:" + str(clean_root) + "/simulator/target/classes/"}},
                }))
                output = clean_root / "artifact/results"
                MODULE.collect_results(source_results, output)
                relocated = output / "evidence/genuine/result.manifest.json"
                MODULE.relocate_manifest(relocated, output, source_results)
                document = json.loads(relocated.read_text())
                self.assertEqual("/", document["provenance"]["execution"]["workingDirectory"])
                self.assertEqual("/results/evidence/inputs/chain.dax", document["inputs"][0]["path"])
                self.assertEqual(1, MODULE.validate_manifest_inputs(output.parent))
        finally:
            if created_base:
                base.rmdir()

    def test_negative_rerun_inputs_keep_their_intended_failure_semantics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "results/rerun").mkdir(parents=True)
            (root / "results/rerun/results.json").write_text(json.dumps({"cases": [
                {"caseId": "input-v2-hash-mismatch", "actualVerdict": "INPUT_HASH_MISMATCH"},
                {"caseId": "input-v3-unresolved", "actualVerdict": "INPUT_UNRESOLVED"},
            ]}))
            original = b"aaaa"
            declared_hash = MODULE.sha256(root / "results/rerun/results.json")  # different from candidate
            manifest = root / "results/rerun/evidence/cases/input-hash-mismatch/result.manifest.json"
            manifest.parent.mkdir(parents=True)
            candidate = root / "results/rerun/inputs/hash.dax"
            candidate.parent.mkdir(parents=True)
            candidate.write_bytes(b"bbbb")
            manifest.write_text(json.dumps({"inputs": [{"path": "/results/rerun/inputs/hash.dax",
                                                        "sizeBytes": 4, "sha256": declared_hash}]}))
            unresolved = root / "results/rerun/evidence/cases/input-unresolved/result.manifest.json"
            unresolved.parent.mkdir(parents=True)
            unresolved.write_text(json.dumps({"inputs": [{"path": "/results/rerun/missing/missing.dax",
                                                          "sizeBytes": 4, "sha256": declared_hash}]}))
            self.assertEqual(0, MODULE.validate_manifest_inputs(root))
            self.assertEqual(2, MODULE.validate_negative_fixture_inputs(root))
            (root / "results/rerun/missing").mkdir()
            (root / "results/rerun/missing/missing.dax").write_bytes(b"bbbb")
            with self.assertRaisesRegex(ValueError, "unexpectedly has an input"):
                MODULE.validate_negative_fixture_inputs(root)

    def test_detached_source_root_is_removed_from_text_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "paper/tmp/fgcs-round2/differential"
            source.mkdir(parents=True)
            (source / "observations.json").write_text(json.dumps([{"workflow": str(root / "paper/tmp/x/chain.dax")}]))
            destination = root / "artifact"
            MODULE.collect_results(source.parent, destination, root)
            text = (destination / "differential/observations.json").read_text()
            self.assertNotIn(str(root), text)
            self.assertIn("paper/tmp/x/chain.dax", text)

    def test_differential_lineage_reconstructs_raw_and_normalized_bytes(self):
        project = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = json.dumps([{"workflow": str(project / "paper/tmp/fgcs-round2/differential/inputs/chain.dax")}],
                             indent=2) + "\n"
            source = root / "raw.json"
            normalized = root / "normalized.json"
            source.write_text(raw)
            normalized.write_text(raw.replace(str(project) + "/", ""))
            lineage = MODULE.create_differential_lineage(source, normalized, root)
            template = (root / lineage["template"]).read_text()
            reconstructed = template.replace("${SOURCE_ROOT}/", lineage["sourceRootPrefix"])
            import hashlib
            self.assertEqual(lineage["raw"], hashlib.sha256(reconstructed.encode()).hexdigest())
            self.assertEqual("results/differential/inputs/chain.dax", json.loads(normalized.read_text())[0]["workflow"])
            for entry in lineage["pathMap"]:
                reconstructed = reconstructed.replace(entry["raw"], entry["normalized"])
            self.assertEqual(normalized.read_text(), reconstructed)

    def test_embedded_verifier_checks_hashes_and_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "results/differential").mkdir(parents=True)
            (root / "results/differential/results.json").write_text("[]")
            (root / "artifact-metadata.json").write_text(json.dumps({"sourceCommit": "abc", "expectedResultRows": {"differential": 0}}))
            (root / "derivation-input-map.json").write_text("[]")
            (root / "verify-artifact.py").write_text(MODULE.verifier_text())
            (root / "SHA256SUMS").write_text("")
            import subprocess
            result = subprocess.run(["python3", str(root / "verify-artifact.py")], text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("SOURCE_COMMIT=abc", result.stdout)


if __name__ == "__main__":
    unittest.main()
