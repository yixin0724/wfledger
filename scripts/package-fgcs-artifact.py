#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {".json", ".jsonl", ".md", ".tex", ".py", ".txt", ".csv", ".log"}
SKIP_DIRS = {"classes", "upstream-classes", "upstream-workflowsim", "logs", "warmup"}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_sanitized(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.suffix in TEXT_SUFFIXES:
        text = source.read_text(encoding="utf-8")
        text = text.replace(str(ROOT) + "/", "")
        text = text.replace(str(Path.home()) + "/.m2/", "$HOME/.m2/")
        text = text.replace("/private/tmp/fgcs-clean-reproduction/", "")
        text = text.replace("/private/tmp/fgcs-clean-reproduction", "detached-worktree")
        target.write_text(text, encoding="utf-8")
    else:
        shutil.copyfile(source, target)


def relocate_manifest(path, results_root):
    """Relocate declared input paths without changing input bytes or measured outcomes."""
    document = json.loads(path.read_text(encoding="utf-8"))
    execution = document["provenance"]["execution"]
    original_root = Path(execution["workingDirectory"])
    if not original_root.is_absolute():
        raise ValueError("manifest workingDirectory must be absolute: " + str(path))
    try:
        source_root = Path(__file__).resolve().parents[1]
        relative = path.resolve().relative_to(results_root.resolve())
    except ValueError as error:
        raise ValueError("manifest is outside the retained results: " + str(path)) from error
    if original_root != source_root and original_root not in source_root.parents:
        raise ValueError("unexpected manifest workingDirectory: " + str(original_root))
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
                raise ValueError("original workflow input is unavailable: " + original)
            if relative_input.parts[:2] != ("paper", "tmp"):
                # Classic DAX inputs are retained separately by the differential/source archive.
                if relative_input.parts[0] == "datasets":
                    relocated = "wfledger-source/" + relative_input.as_posix()
                else:
                    raise ValueError("workflow input is not in the retained paper results: " + original)
            else:
                result_relative = relative_input.relative_to(Path("paper/tmp/fgcs-revision"))
                candidate = results_root / result_relative
                if not candidate.is_file():
                    raise ValueError("retained workflow input is missing: " + str(candidate))
                relocated = "results/" + result_relative.as_posix()
            owner[key] = "/" + relocated
    execution["workingDirectory"] = "/"
    core = document["provenance"].get("core")
    if core and core.get("codeSource"):
        core["codeSource"] = "wfledger-source/simulator/target/classes/"
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
            copy_sanitized(path, destination / relative)
            count += 1
    if not count:
        raise ValueError("no reference results were retained")
    return count


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
import argparse, hashlib, json, os, subprocess, sys
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
    result = subprocess.run([sys.executable, "-B", str(root / "protocols/differential/compare.py"),
                             "--observations", str(root / "results/differential/upstream-observations.json"),
                             "--no-table"], env=environment, text=True, capture_output=True)
    if result.returncode:
        issues.append("normalized differential comparison failed: " + result.stdout + result.stderr)
for area in ("results", "reproduction", "manuscript", "protocols", "validation"):
    for path in (root / area).rglob("*"):
        if path.is_file() and path.suffix in {".json", ".jsonl", ".tex", ".md", ".py", ".txt", ".csv", ".log"}:
            text = path.read_text(encoding="utf-8")
            if "/Users/" in text or "/private/tmp/" in text:
                issues.append("host-specific path remains in " + path.relative_to(root).as_posix())
if issues:
    print("FGCS_ARTIFACT_FAILED")
    for issue in issues:
        print(issue)
    sys.exit(1)
print("FGCS_ARTIFACT_OK files=" + str(len((root / "SHA256SUMS").read_text().splitlines())))
print("SOURCE_COMMIT=" + metadata["sourceCommit"])
if args.semantic:
    print("FGCS_ARTIFACT_SEMANTIC_OK pythonCases=" + str(len(cases)) + " differentialCases=12")
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


def main():
    parser = argparse.ArgumentParser(description="Create a versioned local FGCS source and evidence package")
    parser.add_argument("--results", default="paper/tmp/fgcs-revision")
    parser.add_argument("--output", default="paper/tmp/fgcs-artifact")
    parser.add_argument("--validate", action="store_true", help="also rerun Java bundle validators")
    args = parser.parse_args()
    output = (ROOT / args.output).resolve()
    if output == ROOT or ROOT not in output.parents:
        raise ValueError("artifact output must be inside the ignored paper workspace")
    if output.exists() and any(output.iterdir()):
        raise ValueError("artifact output must be new or empty")
    output.mkdir(parents=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    subprocess.run(["git", "archive", "--format=tar.gz", "--prefix=wfledger-source/", "-o",
                    str(output / "wfledger-source.tar.gz"), commit], cwd=ROOT, check=True)
    result_count = collect_results(ROOT / args.results, output / "results")
    copy_manuscript(output)
    for name in ("makespan-baseline", "makespan-scale"):
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
    original_manifest_hashes = {
        path.relative_to(output).as_posix(): sha256(ROOT / args.results / path.relative_to(output / "results"))
        for path in (output / "results").rglob("result.manifest.json")
    }
    for path in sorted((output / "results").rglob("result.manifest.json")):
        relocate_manifest(path, output / "results")
    for name in ("chain.dax", "fan.dax", "independent.dax"):
        candidates = [ROOT / "paper/tmp/fgcs-differential-revision-2/inputs" / name,
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
    for name in ("differential", "sensitivity", "energy", "ablation", "recovery", "overhead",
                 "verification", "microbench", "static-baseline", "makespan-baseline", "makespan-scale"):
        path = output / "results" / name / "results.json"
        if not path.is_file():
            continue
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
        "revisionWorkingTree": "Paper revision; protocol-specific result metadata is retained",
        "packagingCommit": commit,
        "claim": "Packaging revision is a provenance anchor, not proof of a clean rerun at that commit",
    }
    raw_differential = ROOT / args.results / "differential/upstream-observations.json"
    normalized_differential = output / "results/differential/upstream-observations.json"
    if raw_differential.is_file() and normalized_differential.is_file():
        revised_reproduction = ROOT / "paper/tmp/clean-reproduction-revision/report.json"
    if revised_reproduction.is_file():
        reproduction = json.loads(revised_reproduction.read_text(encoding="utf-8"))
        reproduction["worktree"] = "detached-worktree"
        metadata["generationProvenance"]["revisedCleanReproduction"] = reproduction
    metadata["differentialObservationHashes"] = {
            "raw": sha256(raw_differential),
            "normalized": sha256(normalized_differential),
            "normalization": "Host-specific absolute input roots removed for artifact portability",
        }
    (output / "artifact-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (output / "verify-artifact.py").write_text(verifier_text(), encoding="utf-8")
    if args.validate:
        validate_java_bundles(output)
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
