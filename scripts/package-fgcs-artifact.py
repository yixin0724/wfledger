#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

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
        text = text.replace("/private/tmp/fgcs-clean-reproduction/", "")
        target.write_text(text, encoding="utf-8")
    else:
        shutil.copyfile(source, target)


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
    for name in ("main.pdf", "highlights.pdf"):
        source_file = ROOT / "paper/tmp/fgcs" / name
        if source_file.is_file():
            shutil.copyfile(source_file, destination / "manuscript" / name)


def verifier_text():
    return '''#!/usr/bin/env python3
import hashlib, json, sys
from pathlib import Path
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
if issues:
    print("FGCS_ARTIFACT_FAILED")
    for issue in issues:
        print(issue)
    sys.exit(1)
print("FGCS_ARTIFACT_OK files=" + str(len((root / "SHA256SUMS").read_text().splitlines())))
print("SOURCE_COMMIT=" + metadata["sourceCommit"])
'''


def main():
    parser = argparse.ArgumentParser(description="Create a versioned local FGCS source and evidence package")
    parser.add_argument("--results", default="paper/tmp/fgcs-revision")
    parser.add_argument("--output", default="paper/tmp/fgcs-artifact")
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
                 "verification", "microbench", "static-baseline"):
        path = ROOT / args.results / name / "results.json"
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
    (output / "artifact-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (output / "verify-artifact.py").write_text(verifier_text(), encoding="utf-8")
    (output / "README.md").write_text(
        "# WfLedger FGCS artifact\n\n"
        "Source commit: `" + commit + "`.\n\n"
        "Run `python3 verify-artifact.py` to verify all retained file hashes and expected matrix rows.\n"
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
