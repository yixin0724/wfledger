#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {".json", ".jsonl", ".md", ".tex", ".py", ".txt", ".csv", ".log"}
SKIP_DIRS = {"classes", "upstream-classes", "upstream-workflowsim", "logs", "warmup"}
REQUIRED_RESULT_NAMES = (
    "differential", "sensitivity", "energy", "ablation", "recovery", "overhead",
    "verification", "microbench", "rerun", "contracts", "static-baseline",
    "fixed-capacity-sensitivity", "energy-deadline", "makespan-baseline", "makespan-scale",
)
REQUIRED_NEGATIVE_MANIFESTS = (
    "rerun/evidence/cases/input-hash-mismatch/result.manifest.json",
    "rerun/evidence/cases/input-unresolved/result.manifest.json",
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_packaged_input(root, declared):
    """Resolve the manifest's virtual /results namespace to an extracted package root."""
    path = Path(declared)
    if not path.is_absolute() or path.parts[1] != "results":
        raise ValueError("declared input is outside the artifact /results namespace: " + declared)
    root = Path(root).resolve()
    candidate = root.joinpath(*path.parts[1:]).resolve()
    if root not in candidate.parents or not candidate.is_file():
        raise ValueError("packaged input is missing or escapes the artifact: " + declared)
    return candidate


def negative_fixture_kind(manifest):
    parts = Path(manifest).parts
    if "input-hash-mismatch" in parts:
        return "INPUT_HASH_MISMATCH"
    if "input-unresolved" in parts:
        return "INPUT_UNRESOLVED"
    return None


def validate_negative_fixture_inputs(root):
    root = Path(root).resolve()
    count = 0
    for manifest in Path(root).rglob("result.manifest.json"):
        kind = negative_fixture_kind(manifest)
        if kind is None:
            continue
        document = json.loads(manifest.read_text(encoding="utf-8"))
        for item in document["inputs"]:
            declared = Path(item["path"])
            if not declared.is_absolute() or declared.parts[1] != "results":
                raise ValueError("negative fixture input is outside the artifact namespace")
            candidate = root.joinpath(*declared.parts[1:]).resolve()
            if root not in candidate.parents:
                raise ValueError("negative fixture input escapes the artifact")
            if kind == "INPUT_UNRESOLVED":
                if candidate.exists():
                    raise ValueError("unresolved fixture unexpectedly has an input file")
            else:
                if not candidate.is_file():
                    raise ValueError("hash-mismatch fixture lost its corrupted candidate")
                if candidate.stat().st_size != item["sizeBytes"]:
                    raise ValueError("hash-mismatch fixture must preserve byte count")
                if sha256(candidate) == item["sha256"]:
                    raise ValueError("hash-mismatch fixture no longer mismatches declared identity")
            count += 1
    return count


def validate_manifest_inputs(root):
    count = 0
    for manifest in Path(root).rglob("result.manifest.json"):
        if negative_fixture_kind(manifest) is not None:
            continue
        document = json.loads(manifest.read_text(encoding="utf-8"))
        for item in document["inputs"]:
            path = resolve_packaged_input(root, item["path"])
            if path.stat().st_size != item["sizeBytes"] or sha256(path) != item["sha256"]:
                raise ValueError("packaged workflow identity mismatch: " + item["path"])
            count += 1
    return count


def copy_sanitized(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.suffix in TEXT_SUFFIXES:
        text = source.read_text(encoding="utf-8")
        text = text.replace(str(ROOT) + "/", "")
        text = text.replace(str(Path.home()) + "/.m2/", "$HOME/.m2/")
        text = text.replace("/private/tmp/fgcs-clean-reproduction-round2/", "")
        text = text.replace("/private/tmp/fgcs-clean-reproduction-round2", "detached-worktree")
        text = text.replace("/private/tmp/fgcs-clean-reproduction/", "")
        text = text.replace("/private/tmp/fgcs-clean-reproduction", "detached-worktree")
        target.write_text(text, encoding="utf-8")
    else:
        shutil.copyfile(source, target)


def relocate_manifest(path, results_root, source_results=None):
    """Relocate declared input paths without changing input bytes or measured outcomes."""
    document = json.loads(path.read_text(encoding="utf-8"))
    execution = document["provenance"]["execution"]
    original_root = Path(execution["workingDirectory"])
    if not original_root.is_absolute():
        raise ValueError("manifest workingDirectory must be absolute: " + str(path))
    source_root = original_root
    source_results = source_root / "paper/tmp/fgcs-revision" if source_results is None else Path(source_results).resolve()
    try:
        path.resolve().relative_to(results_root.resolve())
        source_results.relative_to(original_root)
    except ValueError as error:
        raise ValueError("manifest or retained results are outside their declared source checkout: " + str(path)) from error
    for scope in (document["configuration"], document, document["result"]):
        if scope is document["configuration"]:
            targets = [(scope["workflowPaths"], index, original)
                       for index, original in enumerate(scope["workflowPaths"])]
        elif scope is document:
            targets = [(item, "path", item["path"]) for item in scope["inputs"]]
        else:
            targets = [(item, "path", item["path"]) for item in scope["workflowOutcomes"]]
        for owner, key, original in targets:
            original_path = Path(original)
            try:
                relative_input = original_path.resolve().relative_to(original_root)
            except ValueError as error:
                raise ValueError("workflow input is outside the original source tree: " + original) from error
            expected_source = source_root / relative_input
            if not expected_source.is_file():
                if negative_fixture_kind(path) == "INPUT_UNRESOLVED" and relative_input.parts[:2] == ("paper", "tmp"):
                    result_relative = relative_input.relative_to(source_results.relative_to(source_root))
                    owner[key] = "/results/" + result_relative.as_posix()
                    continue
                raise ValueError("original workflow input is unavailable: " + original)
            if relative_input.parts[:2] != ("paper", "tmp"):
                # Classic DAX inputs are retained separately by the differential/source archive.
                if relative_input.parts[0] == "datasets":
                    relocated = "results/_source-inputs/" + relative_input.as_posix()
                    target = results_root / "_source-inputs" / relative_input
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(expected_source, target)
                else:
                    raise ValueError("workflow input is not in the retained paper results: " + original)
            else:
                result_relative = relative_input.relative_to(source_results.relative_to(source_root))
                candidate = results_root / result_relative
                if not candidate.is_file():
                    raise ValueError("retained workflow input is missing: " + str(candidate))
                relocated = "results/" + result_relative.as_posix()
            owner[key] = "/" + relocated
    execution["workingDirectory"] = "/"
    core = document["provenance"].get("core")
    if core and core.get("codeSource"):
        core["codeSource"] = "source-archive:wfledger-source.tar.gz#wfledger-source/simulator/src/main/java/"
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def collect_results(source, destination):
    if not source.is_dir():
        raise ValueError("reference result directory is missing: " + str(source))
    count = 0
    for path in sorted(source.rglob("*")):
        if not path.is_file() or any(part in SKIP_DIRS for part in path.parts):
            continue
        relative = path.relative_to(source)
        if path.suffix in {".json", ".jsonl", ".csv", ".txt", ".log", ".dax"}:
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if path.name == "result.manifest.json":
                # Relocation needs the original absolute workingDirectory and input paths.
                shutil.copyfile(path, target)
            else:
                copy_sanitized(path, target)
            count += 1
    if not count:
        raise ValueError("no reference results were retained")
    return count


def collect_derivations(source_results, output_results, output_root):
    source_results = Path(source_results).resolve()
    output_results = Path(output_results).resolve()
    output_root = Path(output_root).resolve()
    mappings = []
    for sidecar in sorted(source_results.rglob("*.derivation.json")):
        if any(part in SKIP_DIRS for part in sidecar.relative_to(source_results).parts):
            continue
        provenance = json.loads(sidecar.read_text(encoding="utf-8"))
        source = Path(provenance["sourcePath"])
        derived = Path(provenance["derivedPath"])
        source = (source if source.is_absolute() else ROOT / source).resolve()
        derived = (derived if derived.is_absolute() else ROOT / derived).resolve()
        if not source.is_file() or not derived.is_file():
            raise ValueError("derivation input is missing for " + str(sidecar))
        subprocess.run(["python3", "-B", str(ROOT / "scripts/verify-fgcs-derived-input.py"),
                        str(source), str(derived), str(sidecar)], cwd=ROOT, check=True,
                       stdout=subprocess.DEVNULL)
        derived_relative = derived.relative_to(source_results)
        retained_source = output_results / "_derivation-sources" / sha256(source) / source.name
        retained_source.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, retained_source)
        if not (output_results / derived_relative).is_file():
            raise ValueError("retained derived input is missing: " + str(derived_relative))
        mappings.append({
            "sidecar": "results/" + sidecar.relative_to(source_results).as_posix(),
            "source": retained_source.relative_to(output_root).as_posix(),
            "derived": "results/" + derived_relative.as_posix(),
            "sourceSha256": sha256(source),
            "derivedSha256": sha256(derived),
        })
    (output_root / "derivation-input-map.json").write_text(
        json.dumps(mappings, indent=2) + "\n", encoding="utf-8")
    return len(mappings)


def create_differential_lineage(source, normalized, output_root):
    source = Path(source)
    normalized = Path(normalized)
    raw_text = source.read_text(encoding="utf-8")
    observations = json.loads(raw_text)
    prefixes = set()
    for case in observations:
        workflow = case["workflow"]
        if "/paper/tmp/" in workflow:
            prefixes.add(workflow.split("/paper/tmp/", 1)[0] + "/")
        elif "/datasets/" in workflow:
            prefixes.add(workflow.split("/datasets/", 1)[0] + "/")
    if len(prefixes) != 1:
        raise ValueError("cannot infer one source-root prefix for differential observations")
    prefix = prefixes.pop()
    template = raw_text.replace(prefix, "${SOURCE_ROOT}/")
    if template == raw_text:
        raise ValueError("differential source-root prefix did not match any workflow path")
    template_path = output_root / "results/differential/upstream-observations.source-template.json"
    template_path.parent.mkdir(parents=True, exist_ok=True)
    template_path.write_text(template, encoding="utf-8")
    if raw_text.replace(prefix, "") != normalized.read_text(encoding="utf-8"):
        raise ValueError("normalized differential observations are not the declared root-removal transformation")
    return {
        "raw": sha256(source),
        "normalized": sha256(normalized),
        "sourceRootPrefix": prefix,
        "template": "results/differential/upstream-observations.source-template.json",
        "templateSha256": sha256(template_path),
        "normalization": "Replace ${SOURCE_ROOT}/ with sourceRootPrefix to reconstruct raw; remove that prefix to obtain normalized bytes",
    }


def copy_manuscript(destination):
    source = ROOT / "paper/fgcs"
    if not source.is_dir():
        return
    for path in sorted(source.rglob("*")):
        if path.is_file() and path.suffix in {".tex", ".bib", ".md", ".cls", ".bst"}:
            copy_sanitized(path, destination / "manuscript" / path.relative_to(source))
        elif path.is_file() and path.suffix == ".pdf":
            target = destination / "manuscript" / path.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    for name in ("main.pdf", "highlights.pdf"):
        source_file = ROOT / "paper/tmp/fgcs" / name
        if source_file.is_file():
            shutil.copyfile(source_file, destination / "manuscript" / name)


def verifier_text():
    return '''#!/usr/bin/env python3
import argparse, hashlib, json, os, subprocess, sys, tempfile
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument("--semantic", action="store_true", help="also rerun Python lifecycle and differential checks")
args = parser.parse_args()
root = Path(__file__).resolve().parent
issues = []
for line in (root / "SHA256SUMS").read_text().splitlines():
    expected, relative = line.split("  ", 1)
    path = root / relative
    if not path.is_file():
        issues.append("missing " + relative)
    elif hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        issues.append("hash mismatch " + relative)
metadata = json.loads((root / "artifact-metadata.json").read_text())
for name, expected in metadata["expectedResultRows"].items():
    path = root / "results" / name / "results.json"
    if not path.is_file():
        issues.append("missing results/" + name + "/results.json")
        continue
    document = json.loads(path.read_text())
    if isinstance(document, list):
        actual = len(document)
    elif name == "verification":
        actual = document["rq1"]["genuineCases"] + document["rq2"]["corruptedCases"]
    elif name == "microbench":
        actual = document["simulationRuns"] if "simulationRuns" in document else len(document["cases"])
    else:
        actual = len(document.get("cases", []))
    if actual != expected:
        issues.append("row count mismatch " + name + " expected=" + str(expected) + " actual=" + str(actual))
lineage = metadata.get("differentialObservationHashes")
if lineage:
    template_path = root / lineage["template"]
    template = template_path.read_text()
    reconstructed = template.replace("${SOURCE_ROOT}/", lineage["sourceRootPrefix"])
    if hashlib.sha256(template_path.read_bytes()).hexdigest() != lineage["templateSha256"]:
        issues.append("differential source-template hash mismatch")
    if hashlib.sha256(reconstructed.encode()).hexdigest() != lineage["raw"]:
        issues.append("differential raw observation hash cannot be reconstructed")
    normalized = (root / "results/differential/upstream-observations.json").read_text()
    if reconstructed.replace(lineage["sourceRootPrefix"], "") != normalized:
        issues.append("differential normalization is not the declared root-removal transformation")
mutation_path = root / "validation/mutation-gate.json"
if mutation_path.is_file():
    mutation = json.loads(mutation_path.read_text())
    if mutation.get("sourceCommit") != metadata["sourceCommit"] or mutation.get("sourceTreeDirty") is not False:
        issues.append("mutation report is not bound to a clean packaged source revision")
    summary = mutation["summary"]
    if summary["killed"] != summary["total"] or summary["survived"] or summary["staleIds"] or summary["infraFailureIds"]:
        issues.append("mutation gate is not fully killed and current")
if "classifiedRecoveryRuns" in metadata:
    recovery_path = root / "results/recovery/results.json"
    if not recovery_path.is_file():
        recovery_path = root / "results/contracts/recovery-study-results.json"
    recovery_rows = json.loads(recovery_path.read_text())
    if len(recovery_rows) != metadata["classifiedRecoveryRuns"]:
        issues.append("classified recovery row count mismatch")
    for row in recovery_rows:
        if row["recoveryClassification"] == "EXERCISED_FAILURE_RETRY" and (
                row["failedJobs"] <= 0 or row["retryJobsCreated"] <= 0):
            issues.append(row["label"] + ": exercised recovery has no injection")
inputIdentities = 0
negativeInputs = 0
rerunCases = {}
rerunResultsPath = root / "results/rerun/results.json"
if rerunResultsPath.is_file():
    rerunCases = {case["caseId"]: case["actualVerdict"]
                  for case in json.loads(rerunResultsPath.read_text())["cases"]}
for manifest in (root / "results").rglob("result.manifest.json"):
    negativeKind = "INPUT_UNRESOLVED" if "input-unresolved" in manifest.parts else (
        "INPUT_HASH_MISMATCH" if "input-hash-mismatch" in manifest.parts else None)
    document = json.loads(manifest.read_text())
    if negativeKind:
        caseId = "input-v3-unresolved" if negativeKind == "INPUT_UNRESOLVED" else "input-v2-hash-mismatch"
        if rerunCases.get(caseId) != negativeKind:
            issues.append(caseId + ": negative fixture verdict is not " + negativeKind)
        for item in document["inputs"]:
            declared = Path(item["path"])
            candidate = root.joinpath(*declared.parts[1:]).resolve() if declared.is_absolute() and len(declared.parts) > 1 else root
            if negativeKind == "INPUT_UNRESOLVED" and candidate.exists():
                issues.append("unresolved fixture unexpectedly has an input")
            if negativeKind == "INPUT_HASH_MISMATCH":
                if not candidate.is_file() or candidate.stat().st_size != item["sizeBytes"]:
                    issues.append("hash-mismatch fixture lost its same-size corrupted input")
                elif hashlib.sha256(candidate.read_bytes()).hexdigest() == item["sha256"]:
                    issues.append("hash-mismatch fixture no longer mismatches declared identity")
            negativeInputs += 1
        continue
    for item in document["inputs"]:
        declared = Path(item["path"])
        if not declared.is_absolute() or declared.parts[1] != "results":
            issues.append("input outside artifact namespace in " + str(manifest))
            continue
        candidate = root.joinpath(*declared.parts[1:]).resolve()
        if root not in candidate.parents or not candidate.is_file():
            issues.append("missing packaged input " + item["path"])
        elif candidate.stat().st_size != item["sizeBytes"] or hashlib.sha256(candidate.read_bytes()).hexdigest() != item["sha256"]:
            issues.append("packaged input identity mismatch " + item["path"])
        inputIdentities += 1
for derivation in json.loads((root / "derivation-input-map.json").read_text()):
    result = subprocess.run([sys.executable, "-B", str(root / "scripts/verify-fgcs-derived-input.py"),
                             str(root / derivation["source"]), str(root / derivation["derived"]),
                             str(root / derivation["sidecar"])], text=True, capture_output=True)
    if result.returncode:
        issues.append("derivation validation failed " + derivation["sidecar"] + ": " + result.stdout + result.stderr)
if metadata.get("negativeFixtureInputs") is not None and negativeInputs != metadata["negativeFixtureInputs"]:
    issues.append("intentional negative fixture count mismatch")
if args.semantic:
    cases = json.loads((root / "results/verification/results.json").read_text())["cases"]
    for case in cases:
        expected = next(row for row in case["observations"] if row["validator"] == "PYTHON_VERIFY_NETWORK_LEDGER")
        manifest = root / "results/verification" / case["manifest"]
        result = subprocess.run([sys.executable, "-B", str(root / "scripts/verify-network-ledger.py"),
                                 str(manifest), "--json"], text=True, capture_output=True)
        actual = "ACCEPT" if result.returncode == 0 else "REJECT"
        if actual != expected["expected"]:
            issues.append(case["id"] + ": expected " + expected["expected"] + " got " + actual)
        fragment = expected.get("requiredReasonFragment")
        if fragment and fragment not in result.stdout + result.stderr:
            issues.append(case["id"] + ": missing semantic reason " + fragment)
    environment = dict(os.environ, FGCS_ARTIFACT_ROOT=str(root))
    with tempfile.TemporaryDirectory(prefix="wfledger-diff-check-") as directory:
        generated = Path(directory) / "comparison.json"
        result = subprocess.run([sys.executable, "-B", str(root / "protocols/differential/compare.py"),
                                 "--observations", str(root / "results/differential/upstream-observations.json"),
                                 "--output", str(generated), "--no-table"],
                                env=environment, text=True, capture_output=True)
        expected_path = root / "results/differential/normalized-comparison.json"
        if result.returncode or not expected_path.is_file():
            issues.append("normalized differential comparison failed: " + result.stdout + result.stderr)
        elif json.loads(generated.read_text()) != json.loads(expected_path.read_text()):
            issues.append("normalized differential report differs from the retained full report")
for area in ("results", "reproduction", "manuscript", "protocols", "validation"):
    for path in (root / area).rglob("*"):
        if path.is_file() and path.suffix in {".json", ".jsonl", ".tex", ".md", ".py", ".txt", ".csv", ".log"}:
            text = path.read_text(encoding="utf-8")
            if path.name == "artifact-metadata.json" and lineage:
                text = text.replace(lineage["sourceRootPrefix"], "<source-root>/")
            if "/Users/" in text or "/private/tmp/" in text:
                issues.append("host-specific path remains in " + path.relative_to(root).as_posix())
if issues:
    print("FGCS_ARTIFACT_FAILED")
    for issue in issues:
        print(issue)
    sys.exit(1)
print("FGCS_ARTIFACT_OK files=" + str(len((root / "SHA256SUMS").read_text().splitlines())))
print("SOURCE_COMMIT=" + metadata["sourceCommit"])
print("INPUT_IDENTITIES_OK count=" + str(inputIdentities)
      + " negativeFixtures=" + str(negativeInputs)
      + " derivations=" + str(len(json.loads((root / "derivation-input-map.json").read_text()))))
if args.semantic:
    differentialCases = len(json.loads((root / "results/differential/upstream-observations.json").read_text()))
    print("FGCS_ARTIFACT_SEMANTIC_OK pythonCases=" + str(len(cases)) + " differentialCases=" + str(differentialCases))
'''


def validate_java_bundles(output):
    with tempfile.TemporaryDirectory(prefix="wfledger-validator-") as directory:
        classpath_file = Path(directory) / "classpath.txt"
        subprocess.run(["mvn", "-q", "-pl", ":workflowsim", "-am", "compile", "dependency:build-classpath",
                        "-Dmdep.outputFile=" + str(classpath_file)], cwd=ROOT, check=True)
        classpath = str(ROOT / "simulator/target/classes") + ":" + classpath_file.read_text().strip()
        cases = json.loads((output / "results/verification/results.json").read_text())["cases"]
        lines = ["JAVA_INDEPENDENT_BUNDLE_VALIDATION"]
        for case in cases:
            expected = next(row for row in case["observations"]
                            if row["validator"] == "JAVA_EXPERIMENT_ARTIFACT_VALIDATOR")
            manifest = output / "results/verification" / case["manifest"]
            result = subprocess.run(["java", "-cp", classpath,
                                     "org.workflowsim.experiment.ExperimentArtifactValidator", str(manifest)],
                                    text=True, capture_output=True)
            actual = "ACCEPT" if result.returncode == 0 else "REJECT"
            output_text = (result.stdout + result.stderr).replace(str(output) + "/", "")
            if actual != expected["expected"]:
                raise ValueError(case["id"] + ": Java expected " + expected["expected"] + " got " + actual
                                 + "\n" + output_text)
            fragment = expected.get("requiredReasonFragment")
            if fragment and fragment not in output_text:
                raise ValueError(case["id"] + ": missing Java semantic reason " + fragment)
            lines.append(case["id"] + " expected=" + expected["expected"] + " actual=" + actual)
        lines.append("JAVA_BUNDLES_OK cases=" + str(len(cases)))
        target = output / "validation/java-lifecycle.log"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate_portable_reruns(output):
    with tempfile.TemporaryDirectory(prefix="wfledger-portable-rerun-") as directory:
        classpath_file = Path(directory) / "classpath.txt"
        subprocess.run(["mvn", "-q", "-pl", ":workflowsim-workbench", "-am", "compile", "dependency:build-classpath",
                        "-Dmdep.outputFile=" + str(classpath_file)], cwd=ROOT, check=True)
        classpath = str(ROOT / "experiments/target/classes") + os.pathsep + str(ROOT / "simulator/target/classes")
        classpath += os.pathsep + classpath_file.read_text().strip()
        lines = ["PORTABLE_MANIFEST_GUIDED_RERUN"]
        for case_id in ("genuine-v2", "genuine-v3"):
            source = output / "results/verification/evidence" / case_id
            manifest = json.loads((source / "result.manifest.json").read_text())
            packaged_input = resolve_packaged_input(output, manifest["inputs"][0]["path"])
            study = Path(directory) / (case_id + "-study")
            run = study / "runs" / case_id
            shutil.copytree(source, run)
            inputs = study / "inputs"
            inputs.mkdir(parents=True)
            shutil.copyfile(packaged_input, inputs / packaged_input.name)
            destination = Path(directory) / (case_id + "-executor")
            result = subprocess.run(["java", "-cp", classpath,
                                     "org.workflowsim.experiments.rerun.RerunDiffExecutor",
                                     str(run), str(destination)], cwd=ROOT, text=True, capture_output=True)
            report_path = destination / "rerun-report.json"
            if not report_path.is_file():
                raise ValueError(case_id + ": portable rerun produced no report\n" + result.stdout + result.stderr)
            report = json.loads(report_path.read_text())
            if result.returncode != 0 or report["verdict"] != "IDENTICAL_CORE":
                raise ValueError(case_id + ": portable rerun did not preserve exact core: "
                                 + report["verdict"] + "\n" + result.stdout + result.stderr)
            resolution = report["inputResolution"][0]
            if resolution["tier"] != 1 or not resolution["verified"]:
                raise ValueError(case_id + ": portable input was not identity-verified at tier 1")
            target = output / "validation/portable-rerun" / case_id
            target.mkdir(parents=True, exist_ok=True)
            for name in ("rerun-report.json", "rerun-report.md"):
                text = (destination / name).read_text(encoding="utf-8")
                text = text.replace(str(Path(directory)) + "/", "")
                (target / name).write_text(text, encoding="utf-8")
            lines.append(case_id + " verdict=IDENTICAL_CORE inputTier=1 verified=true")
        target = output / "validation/portable-rerun.log"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Create a versioned local FGCS source and evidence package")
    parser.add_argument("--results", default="paper/tmp/fgcs-round2")
    parser.add_argument("--output", default="paper/tmp/fgcs-artifact")
    parser.add_argument("--validate", action="store_true", help="also rerun Java bundle validators")
    args = parser.parse_args()
    output = (ROOT / args.output).resolve()
    if output == ROOT or ROOT not in output.parents:
        raise ValueError("artifact output must be inside the ignored paper workspace")
    if output.exists() and any(output.iterdir()):
        raise ValueError("artifact output must be new or empty")
    output.mkdir(parents=True)
    dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()
    if dirty:
        raise ValueError("release packaging requires a clean tracked tree so archived and tested source are identical")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    subprocess.run(["git", "archive", "--format=tar.gz", "--prefix=wfledger-source/", "-o",
                    str(output / "wfledger-source.tar.gz"), commit], cwd=ROOT, check=True)
    result_count = collect_results(ROOT / args.results, output / "results")
    derivation_count = collect_derivations(ROOT / args.results, output / "results", output)
    copy_manuscript(output)
    mutation_report = ROOT / "output/mutation-gate/mutation-gate-round2.json"
    if mutation_report.is_file():
        copy_sanitized(mutation_report, output / "validation/mutation-gate.json")
    for name in ("makespan-baseline", "makespan-scale"):
        source = ROOT / args.results / name / "results.json"
        if not source.is_file():
            source = ROOT / "output" / name / "results.json"
        if source.is_file():
            copy_sanitized(source, output / "results" / name / "results.json")
    for name in ("report.json", "comparison.log", "maven-verify.log", "matrix.log"):
        source = ROOT / "paper/tmp/clean-reproduction" / name
        if source.is_file():
            copy_sanitized(source, output / "reproduction" / name)
    for name in ("report.json", "comparison.log", "maven-verify.log", "matrix.log", "revision-drivers.log"):
        source = ROOT / "paper/tmp/clean-reproduction-revision" / name
        if source.is_file():
            copy_sanitized(source, output / "reproduction/revised" / name)
    for name in ("report.json", "comparison.log", "maven-verify.log", "matrix.log", "revision-drivers.log"):
        source = ROOT / "paper/tmp/clean-reproduction-round2" / name
        if source.is_file():
            copy_sanitized(source, output / "reproduction/round2" / name)
    original_manifest_hashes = {
        path.relative_to(output).as_posix(): sha256(ROOT / args.results / path.relative_to(output / "results"))
        for path in (output / "results").rglob("result.manifest.json")
    }
    for path in sorted((output / "results").rglob("result.manifest.json")):
        relocate_manifest(path, output / "results", ROOT / args.results)
    if not (output / "results/differential/upstream-observations.json").is_file():
        raise ValueError("paired differential observations are missing; run scripts/prepare-fgcs-differential.py first")
    for relative in REQUIRED_NEGATIVE_MANIFESTS:
        if not (output / "results" / relative).is_file():
            raise ValueError("required negative rerun fixture is missing: " + relative)
    negative_count = validate_negative_fixture_inputs(output)
    if negative_count != 2:
        raise ValueError("expected two intentional negative rerun inputs, found " + str(negative_count))
    for name in ("chain.dax", "fan.dax", "independent.dax"):
        candidates = [ROOT / "paper/tmp/fgcs-round2/differential/inputs" / name,
                      ROOT / "paper/tmp/fgcs-differential-revision-2/inputs" / name,
                      ROOT / "paper/tmp/fgcs-revision/differential/inputs" / name]
        source = next((candidate for candidate in candidates if candidate.is_file()), None)
        if source is None:
            raise ValueError("differential input is missing: " + name)
        target = output / "results/differential/inputs" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    for relative in ("dax/montage/n50/Montage_50.dax", "dax/cybershake/n50/CyberShake_50.dax",
                     "dax/sipht/n60/Sipht_60.dax"):
        source = ROOT / "datasets" / relative
        target = output / "results/differential/datasets" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    for source in sorted((ROOT / "scripts").glob("*.py")):
        copy_sanitized(source, output / "scripts" / source.name)
    for directory in ("verification", "microbench", "baseline", "differential"):
        source = ROOT / "experiments/studies/fgcs" / directory
        if source.is_dir():
            for path in sorted(source.rglob("*")):
                if path.is_file():
                    copy_sanitized(path, output / "protocols" / directory / path.relative_to(source))
    metadata = {
        "schema": "wfledger-fgcs-artifact-v1",
        "sourceCommit": commit,
        "referenceDirectory": "results",
        "expectedResultRows": {},
        "persistentIdentifier": "PENDING_PUBLIC_DEPOSIT",
        "artifactScope": "Source archive, compact result/evidence package, protocols, and manuscript; not a production-validity certificate",
    }
    for name in REQUIRED_RESULT_NAMES:
        path = output / "results" / name / "results.json"
        if not path.is_file():
            raise ValueError("required round-two result is missing: " + str(path))
        document = json.loads(path.read_text())
        if isinstance(document, list):
            count = len(document)
        elif name == "verification":
            count = document["rq1"]["genuineCases"] + document["rq2"]["corruptedCases"]
        elif name == "microbench":
            count = document["simulationRuns"] if "simulationRuns" in document else len(document["cases"])
        else:
            count = len(document["cases"])
        metadata["expectedResultRows"][name] = count
    metadata["retainedFilesBeforeManifest"] = result_count
    recovery_results = ROOT / args.results / "recovery/results.json"
    if not recovery_results.is_file():
        recovery_results = ROOT / args.results / "contracts/recovery-study-results.json"
    if recovery_results.is_file():
        metadata["classifiedRecoveryRuns"] = len(json.loads(recovery_results.read_text()))
    metadata["derivationInputCount"] = derivation_count
    metadata["negativeFixtureInputs"] = negative_count
    metadata["originalManifestSha256"] = original_manifest_hashes
    metadata["pathRelocation"] = {
        "schema": "wfledger-artifact-path-relocation-v1",
        "workingDirectory": "/",
        "workflowPaths": "Absolute paths in the archive namespace, /results/...; workingDirectory is /",
        "originalBytes": "Input files are copied byte-for-byte; original input SHA-256 values are unchanged",
        "originalManifestHashes": "originalManifestSha256 records pre-relocation manifest bytes",
    }
    metadata["generationProvenance"] = {
        "original351MatrixCommit": "261f28c3c31aeb829837a935f5b9095a82f088c4",
        "revisionWorkingTree": "Current tables use detached-worktree results when revisedCleanReproduction is present",
        "packagingCommit": commit,
        "claim": "Packaging alone is not proof of clean regeneration; the separate clean-reproduction report records the executed scope",
    }
    raw_differential = ROOT / args.results / "differential/upstream-observations.json"
    normalized_differential = output / "results/differential/upstream-observations.json"
    if raw_differential.is_file() and normalized_differential.is_file():
        metadata["differentialObservationHashes"] = create_differential_lineage(
            raw_differential, normalized_differential, output)
    revised_reproduction = ROOT / "paper/tmp/clean-reproduction-round2/report.json"
    if revised_reproduction.is_file():
        reproduction = json.loads(revised_reproduction.read_text(encoding="utf-8"))
        reproduction["worktree"] = "detached-worktree"
        metadata["generationProvenance"]["revisedCleanReproduction"] = reproduction
    (output / "artifact-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (output / "verify-artifact.py").write_text(verifier_text(), encoding="utf-8")
    if args.validate:
        validate_java_bundles(output)
        validate_portable_reruns(output)
    differential_environment = dict(os.environ, FGCS_ARTIFACT_ROOT=str(output))
    subprocess.run(["python3", "-B", str(output / "protocols/differential/compare.py"),
                    "--observations", str(output / "results/differential/upstream-observations.json"),
                    "--output", str(output / "results/differential/normalized-comparison.json"), "--no-table"],
                   cwd=output, env=differential_environment, check=True, stdout=subprocess.DEVNULL)
    shutil.copyfile(output / "results/differential/normalized-comparison.json",
                    output / "protocols/differential/comparison.json")
    initial_lines = [sha256(path) + "  " + path.relative_to(output).as_posix()
                     for path in sorted(output.rglob("*")) if path.is_file() and path.name != "SHA256SUMS"]
    (output / "SHA256SUMS").write_text("\n".join(initial_lines) + "\n", encoding="utf-8")
    semantic = subprocess.run(["python3", str(output / "verify-artifact.py"), "--semantic"],
                              cwd=output, text=True, capture_output=True)
    semantic_log = (semantic.stdout + semantic.stderr).replace(str(output) + "/", "")
    if semantic.returncode:
        raise ValueError("retained semantic validation failed:\n" + semantic_log)
    (output / "validation").mkdir(exist_ok=True)
    (output / "validation/python-and-differential.log").write_text(semantic_log, encoding="utf-8")
    (output / "README.md").write_text(
        "# WfLedger FGCS artifact\n\n"
        "Source commit: `" + commit + "`.\n\n"
        "Run `python3 verify-artifact.py --semantic` to verify hashes, protocol counts, relocated lifecycle bundles, and normalized differential observations.\n"
        "The source archive is `wfledger-source.tar.gz`; result summaries are under `results/`; "
        "protocols are under `protocols/`; manuscript sources and PDF are under `manuscript/`.\n"
        "A persistent public identifier remains pending and is not claimed by this local package.\n",
        encoding="utf-8")
    lines = []
    for path in sorted(output.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS":
            lines.append(sha256(path) + "  " + path.relative_to(output).as_posix())
    (output / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
    subprocess.run(["python3", str(output / "verify-artifact.py")], cwd=output, check=True)
    print("FGCS_ARTIFACT_WRITTEN", output)


if __name__ == "__main__":
    main()
