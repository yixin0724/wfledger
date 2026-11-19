#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = {
    "differential": "org.workflowsim.experiments.fgcs.differential.UpstreamDifferentialStudy",
    "sensitivity": "org.workflowsim.experiments.fgcs.sensitivity.SensitivityStudy",
    "energy": "org.workflowsim.experiments.fgcs.energy.EnergySensitivityStudy",
    "ablation": "org.workflowsim.experiments.fgcs.ablation.CommunicationStorageAblation",
    "recovery": "org.workflowsim.experiments.fgcs.recovery.FailureRecoveryStudy",
}
COUNTS = {"differential": 24, "sensitivity": 216, "energy": 36, "ablation": 18, "recovery": 12, "overhead": 45}
WORKFLOWS = (
    "datasets/dax/montage/n50/Montage_50.dax",
    "datasets/dax/montage/n1000/Montage_1000.dax",
    "experiments/studies/makespan-scale/inputs/Montage_5000.dax",
)
MODES = ("off", "events", "lifecycle")


def run(command, log=None):
    print("+", " ".join(str(x) for x in command), flush=True)
    if log is None:
        return subprocess.run(command, cwd=ROOT, check=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as stream:
        return subprocess.run(command, cwd=ROOT, check=True, stdout=stream, stderr=subprocess.STDOUT)


def java_executable():
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / "java" if home else None
    return str(candidate) if candidate and candidate.is_file() else "java"


def compile_and_classpath(output):
    run(["mvn", "-q", "-pl", ":workflowsim-workbench", "-am", "compile"])
    path = (ROOT / "paper/tmp/fgcs-classpath.txt").resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    run([
        "mvn", "-q", "-pl", ":workflowsim-workbench", "dependency:build-classpath",
        "-Dmdep.outputFile=" + str(path),
    ])
    dependencies = path.read_text(encoding="utf-8").strip()
    return os.pathsep.join([
        str(ROOT / "simulator/target/classes"),
        str(ROOT / "experiments/target/classes"),
        dependencies,
    ])


def run_standard(output, log_dir):
    for name, main in MAIN.items():
        destination = output / name / "results.json"
        run([
            "mvn", "-q", "-pl", ":workflowsim-workbench", "-am", "compile", "exec:java",
            "-Dexec.mainClass=" + main,
            "-Dexec.args=" + str(destination.relative_to(ROOT)),
        ], log_dir / (name + ".log"))


def run_overhead(output, repetitions):
    classpath = compile_and_classpath(output)
    java = java_executable()
    for workflow in WORKFLOWS:
        for mode in MODES:
            rows = []
            for repetition in range(repetitions):
                result = output / "overhead" / "runs" / (Path(workflow).stem + "-" + mode + "-r" + str(repetition) + ".json")
                evidence = output / "overhead" / "evidence" / (Path(workflow).stem + "-" + mode + "-r" + str(repetition))
                run([
                    java, "-Xmx4g", "-cp", classpath,
                    "org.workflowsim.experiments.fgcs.scalability.EvidenceOverheadStudy",
                    str(result.relative_to(ROOT)), workflow, mode, str(repetition), str(evidence.relative_to(ROOT)),
                ], output / "overhead" / (Path(workflow).stem + "-" + mode + "-r" + str(repetition) + ".log"))
                rows.extend(json.loads(result.read_text(encoding="utf-8")))
            (output / "overhead" / "parts").mkdir(parents=True, exist_ok=True)
            (output / "overhead" / "parts" / (Path(workflow).stem + "-" + mode + ".json")).write_text(
                json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    rows = []
    for workflow in WORKFLOWS:
        for mode in MODES:
            rows.extend(json.loads((output / "overhead" / "parts" / (Path(workflow).stem + "-" + mode + ".json")).read_text()))
    (output / "overhead").mkdir(parents=True, exist_ok=True)
    (output / "overhead/results.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")


def verify(output, repetitions):
    expected = dict(COUNTS)
    expected["overhead"] = 9 * repetitions
    failures = []
    total = 0
    for name, count in expected.items():
        path = output / name / "results.json"
        if not path.is_file():
            failures.append("missing " + str(path))
            continue
        rows = json.loads(path.read_text(encoding="utf-8"))
        if len(rows) != count:
            failures.append(f"{name}: expected {count} rows, got {len(rows)}")
        for row in rows:
            total += 1
            label = row.get("label", "<unknown>")
            if not row.get("completed"):
                failures.append(label + ": incomplete")
            if row.get("logicalTasks") != row.get("completedTasks"):
                failures.append(label + ": logical completion mismatch")
            for field in ("energyResidual", "costResidual", "makespanResidual", "tailResidual", "dependencyResidual"):
                if abs(float(row.get(field, 0.0))) > 1.0e-4:
                    failures.append(label + ": residual " + field + "=" + str(row.get(field)))
            if "-off-" in label and row.get("eventCount") != 0:
                failures.append(label + ": OFF retained events")
            if "-off-" not in label and name == "overhead" and row.get("eventCount", 0) <= 0:
                failures.append(label + ": enabled recording has no events")
            if "-lifecycle-" in label and row.get("artifactBytes", 0) <= 0:
                failures.append(label + ": lifecycle evidence is empty")
    if failures:
        raise ValueError("\n".join(failures))
    return total


def write_metadata(output):
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    metadata = {
        "protocol": "FGCS_MATRIX_V1",
        "sourceCommit": commit,
        "sourceTreeDirty": bool(status.strip()),
        "java": subprocess.run([java_executable(), "-version"], cwd=ROOT, text=True,
                               capture_output=True).stderr.strip(),
        "platform": platform.platform(),
        "outputPolicy": "generated evidence remains under paper/tmp and is not a Git source input",
    }
    text = json.dumps(metadata, indent=2) + "\n"
    (output / "run-metadata.json").write_text(text, encoding="utf-8")
    (output / "run-metadata.sha256").write_text(hashlib.sha256(text.encode()).hexdigest() + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Run and verify the FGCS experiment matrix")
    parser.add_argument("--output", default="paper/tmp/experiments")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--overhead-only", action="store_true")
    parser.add_argument("--standard-only", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("repetitions must be positive")
    output = (ROOT / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.verify_only:
        print("FGCS_VERIFIED_RUNS", verify(output, args.repetitions))
        return
    if args.overhead_only and args.standard_only:
        parser.error("choose only one matrix subset")
    if not args.overhead_only:
        run_standard(output, output / "logs")
    if not args.standard_only:
        run_overhead(output, args.repetitions)
    print("FGCS_VERIFIED_RUNS", verify(output, args.repetitions))
    write_metadata(output)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, ValueError, OSError, json.JSONDecodeError) as error:
        print("FGCS_MATRIX_FAILED:", error, file=sys.stderr)
        raise SystemExit(1)
