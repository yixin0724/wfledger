#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DRIVERS = {
    "static-baseline": "org.workflowsim.experiments.fgcs.baseline.StaticPlannerBaselineStudy",
    "verification": "org.workflowsim.experiments.fgcs.verification.AdversarialEvidenceStudy",
    "microbench": "org.workflowsim.experiments.fgcs.microbench.ModelMicrobenchmarks",
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
        for section, key in (("rq1", "unexpectedOutcomes"), ("rq2", "unexpectedOutcomes"), ("rq1", "reasonMismatches"), ("rq2", "reasonMismatches")):
            if result[section][key] != 0:
                issues.append(section + ": " + key)
        if result["rq2"]["actualRejections"] != result["rq2"]["expectedRejections"]:
            issues.append("adversarial rejection mismatch")
        if result["rq2"]["expectedAcceptances"] != result["rq2"]["actualAcceptances"]:
            issues.append("coverage-boundary mismatch")
    else:
        issues.append("missing adversarial results")
    micro_path = output / "microbench/results.json"
    if micro_path.is_file():
        document = json.loads(micro_path.read_text())
        for case in document["cases"]:
            for field, (expected, observed) in expected_observed_pairs(case).items():
                if observed is None or abs(float(expected) - float(observed)) > 1e-8:
                    issues.append(case["id"] + ": " + field)
    else:
        issues.append("missing model microbenchmarks")
    for name, expected in (("static-baseline", 21), ("overhead", 45)):
        path = output / name / "results.json"
        if not path.is_file():
            issues.append("missing " + name)
        elif len(json.loads(path.read_text())) != expected:
            issues.append("wrong row count for " + name)
    if issues:
        raise ValueError("; ".join(issues))


def metadata(output):
    java = os.environ.get("JAVA_HOME", "")
    java_bin = str(Path(java) / "bin/java") if java else "java"
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    return {
        "protocol": "FGCS_REVISION_V2",
        "sourceCommit": commit,
        "sourceTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
        "java": subprocess.run([java_bin, "-version"], capture_output=True, text=True).stderr.strip(),
        "maven": subprocess.run(["mvn", "-version"], cwd=ROOT, capture_output=True, text=True).stdout.splitlines()[0],
        "platform": platform.platform(),
        "processor": platform.processor(),
        "python": platform.python_version(),
        "overheadJvmFlags": ["-Xmx4g"],
        "overheadWarmup": "one warm-up before each measured run in a fresh JVM",
        "execution": "serial simulations; separate JVM for each overhead measurement",
        "legacyNonOverheadRuns": 306,
        "sameLayerStaticRuns": 21,
        "discriminatingSimulations": 6,
        "adversarialBundles": 11,
        "controlledOverheadRuns": 45,
        "revisedMatrixRowsExcludingOlderBaseline": 389,
        "olderMakespanAndScaleRowsReportedSeparately": 78,
        "excludedLegacyOverhead": "The older confounded 45-row matrix is not used as isolated recording-cost evidence",
    }


def main():
    parser = argparse.ArgumentParser(description="Run and verify FGCS revision-specific studies")
    parser.add_argument("--output", default="paper/tmp/fgcs-revision")
    parser.add_argument("--drivers", default="static-baseline,verification,microbench")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    output = (ROOT / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if not args.verify_only:
        for name in args.drivers.split(","):
            if name not in DRIVERS:
                parser.error("unknown revision driver " + name)
            run(["mvn", "-q", "-pl", ":workflowsim-workbench", "-am", "compile", "exec:java",
                 "-Dexec.mainClass=" + DRIVERS[name],
                 "-Dexec.args=" + str((output / name).relative_to(ROOT))], output / "logs" / (name + ".log"))
    verify(output)
    (output / "revision-metadata.json").write_text(json.dumps(metadata(output), indent=2) + "\n", encoding="utf-8")
    print("FGCS_REVISION_VERIFIED static=21 microbenchSimulations=6 adversarialBundles=11 overhead=45")


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, ValueError, OSError, json.JSONDecodeError) as error:
        print("FGCS_REVISION_FAILED:", error, file=sys.stderr)
        raise SystemExit(1)
