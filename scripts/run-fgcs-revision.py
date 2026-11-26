#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

from _fgcs_protocol import check_manifest_semantics, declared_execution_semantics

ROOT = Path(__file__).resolve().parents[1]
DRIVERS = {
    "static-baseline": "org.workflowsim.experiments.fgcs.baseline.StaticPlannerBaselineStudy",
    "verification": "org.workflowsim.experiments.fgcs.verification.AdversarialEvidenceStudy",
    "microbench": "org.workflowsim.experiments.fgcs.microbench.ModelMicrobenchmarks",
    "rerun": "org.workflowsim.experiments.fgcs.rerun.ManifestGuidedRerunStudy",
    "contracts": "org.workflowsim.experiments.fgcs.contracts.SemanticContractBenchmarks",
    "fixed-capacity-sensitivity": "org.workflowsim.experiments.fgcs.sensitivity.CapacityControlledSensitivityStudy",
    "energy-deadline": "org.workflowsim.experiments.fgcs.energy.EnergyDeadlineStudy",
    "makespan-baseline": "org.workflowsim.experiments.makespan.MakespanBaselineComparison",
    "makespan-scale": "org.workflowsim.experiments.makespan.MakespanScaleComparison",
}
FILE_OUTPUT = {"static-baseline", "fixed-capacity-sensitivity", "energy-deadline", "makespan-baseline", "makespan-scale"}
EXPECTED_LIST_ROWS = {
    "static-baseline": 21,
    "overhead": 45,
    "fixed-capacity-sensitivity": 540,
    "energy-deadline": 80,
    "makespan-baseline": 42,
    "makespan-scale": 36,
}


def run(command, log):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as stream:
        subprocess.run(command, cwd=ROOT, check=True, stdout=stream, stderr=subprocess.STDOUT)


def expected_observed_pairs(case):
    expected, observed = case["expected"], case["observed"]
    return {key: (expected[key], observed.get(key)) for key in expected}


def verify(output):
    issues = []
    verification_path = output / "verification/results.json"
    if verification_path.is_file():
        result = json.loads(verification_path.read_text())
        for section in ("rq1", "rq2"):
            for key in ("unexpectedOutcomes", "reasonMismatches"):
                if result[section][key] != 0:
                    issues.append(section + ": " + key)
        if result["rq2"]["actualRejections"] != result["rq2"]["expectedRejections"]:
            issues.append("adversarial rejection mismatch")
        if result["rq2"]["expectedAcceptances"] != result["rq2"]["actualAcceptances"]:
            issues.append("coverage-boundary mismatch")
    else:
        issues.append("missing adversarial results")
    for name in ("microbench", "contracts"):
        path = output / name / "results.json"
        if not path.is_file():
            issues.append("missing " + name)
            continue
        document = json.loads(path.read_text())
        for case in document["cases"]:
            if "passed" in case and not case["passed"]:
                issues.append(case["id"] + ": contract failed")
            for field, (expected, observed) in expected_observed_pairs(case).items():
                if observed is None or abs(float(expected) - float(observed)) > 1e-8:
                    issues.append(case["id"] + ": " + field)
    rerun_path = output / "rerun/results.json"
    if not rerun_path.is_file():
        issues.append("missing manifest-guided rerun results")
    else:
        document = json.loads(rerun_path.read_text())
        if document["summary"]["unexpectedOutcomes"] != 0 or len(document["cases"]) != 9:
            issues.append("manifest-guided rerun protocol mismatch")
    recovery_path = output / "recovery/results.json"
    if not recovery_path.is_file():
        recovery_path = output / "contracts/recovery-study-results.json"
    if not recovery_path.is_file():
        issues.append("missing classified recovery results")
    else:
        rows = json.loads(recovery_path.read_text())
        if len(rows) != 12:
            issues.append("recovery row count mismatch")
        for row in rows:
            if row["recoveryClassification"] == "EXERCISED_FAILURE_RETRY" and (
                    row["failedJobs"] <= 0 or row["retryJobsCreated"] <= 0):
                issues.append(row["label"] + ": exercised recovery has no injection")
    for name, expected in EXPECTED_LIST_ROWS.items():
        path = output / name / "results.json"
        if not path.is_file():
            issues.append("missing " + name)
        elif len(json.loads(path.read_text())) != expected:
            issues.append("wrong row count for " + name)
    expected = declared_execution_semantics()
    for name in ("rerun/evidence", "verification/evidence"):
        _, failures = check_manifest_semantics(output / name, expected)
        issues.extend(failures)
    if issues:
        raise ValueError("; ".join(issues))


def metadata(output):
    java = os.environ.get("JAVA_HOME", "")
    java_bin = str(Path(java) / "bin/java") if java else "java"
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    counts = {name: len(json.loads((output / name / "results.json").read_text()))
              for name in EXPECTED_LIST_ROWS}
    counts["adversarialBundles"] = len(json.loads((output / "verification/results.json").read_text())["cases"])
    counts["manifestGuidedRerunCases"] = len(json.loads((output / "rerun/results.json").read_text())["cases"])
    counts["semanticContracts"] = len(json.loads((output / "contracts/results.json").read_text())["cases"])
    return {
        "protocol": "FGCS_ROUND2_V4",
        "executionSemantics": declared_execution_semantics(),
        "sourceCommit": commit,
        "sourceTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
        "java": subprocess.run([java_bin, "-version"], capture_output=True, text=True).stderr.strip(),
        "maven": subprocess.run(["mvn", "-version"], cwd=ROOT, capture_output=True, text=True).stdout.splitlines()[0],
        "platform": platform.platform(),
        "processor": platform.processor(),
        "python": platform.python_version(),
        "overheadJvmFlags": ["-Xmx4g"],
        "overheadWarmup": "one full-pipeline warmup before each measured run in a fresh JVM",
        "execution": "serial simulations; separate JVM for each overhead measurement",
        "counts": counts,
        "excludedLegacyOverhead": "The older confounded 45-row matrix is not used as isolated recording-cost evidence",
    }


def main():
    parser = argparse.ArgumentParser(description="Run and verify all second-round FGCS revision studies")
    parser.add_argument("--output", default="paper/tmp/fgcs-round2")
    parser.add_argument("--drivers", default=",".join(DRIVERS))
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    output = (ROOT / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if not args.verify_only:
        for name in args.drivers.split(","):
            if name not in DRIVERS:
                parser.error("unknown revision driver " + name)
            driver_output = output / name / "results.json" if name in FILE_OUTPUT else output / name
            run(["mvn", "-q", "-pl", ":workflowsim-workbench", "-am", "compile", "exec:java",
                 "-Dexec.mainClass=" + DRIVERS[name],
                 "-Dexec.args=" + str(driver_output.relative_to(ROOT))], output / "logs" / (name + ".log"))
    verify(output)
    (output / "revision-metadata.json").write_text(json.dumps(metadata(output), indent=2) + "\n", encoding="utf-8")
    print("FGCS_ROUND2_VERIFIED " + json.dumps(metadata(output)["counts"], sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, ValueError, OSError, json.JSONDecodeError) as error:
        print("FGCS_ROUND2_FAILED:", error, file=sys.stderr)
        raise SystemExit(1)
