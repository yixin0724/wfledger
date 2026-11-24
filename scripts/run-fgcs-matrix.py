#!/usr/bin/env python3
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import sys

from _fgcs_protocol import check_manifest_semantics, declared_execution_semantics

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
OVERHEAD_PROTOCOL = "FGCS_OVERHEAD_ROUND2_V2"
PYTHON_EVENT_ONLY_SCOPE = "NOT_APPLICABLE_EVENT_ONLY_BUNDLE_WITHOUT_LIFECYCLE_CAPTURE"
OVERHEAD_REQUIRED_FIELDS = (
    "label", "protocol", "workflow", "mode", "repetition", "workflowIndex", "modeOrderIndex", "modeOrder",
    "warmupCompleted", "normalizedInput", "platformProfile", "completed", "logicalTasks", "completedTasks",
    "energyResidual", "costResidual", "makespanResidual", "tailResidual", "dependencyResidual", "makespanSeconds",
    "planningAlgorithm", "schedulingAlgorithm", "dataMovementModel", "fileSystem", "randomSeed", "vmCount",
    "eventRecorderCap", "eventRecordingEnabled", "networkEvidenceMode", "eventCount", "artifactBytes",
    "manifestBytes", "metricsBytes", "eventStreamBytes", "lifecycleEvidenceBytes", "lifecycleEvidenceAvailable",
    "simulationNanos", "serializationAndSizeAccountingNanos", "artifactWriteNanos", "auditNanos",
    "javaValidationApplicable", "javaValidationNanos", "javaValidationStatus", "javaValidationScope",
    "pythonValidationApplicable", "pythonValidationNanos", "pythonValidationStatus", "pythonValidationScope",
    "heapBeforeBytes", "sampledTotalHeapPeakBytes", "heapSampleCount", "heapPoolPeakSumBytes",
    "threadAllocatedBytes", "meanWaitingSeconds", "meanTrueSlowdown", "meanWaitingSecondsAvailable",
    "meanTrueSlowdownAvailable", "eventDependentMetricAvailability", "totalWaitingTimeObservationCount",
    "trueSlowdownObservationCount",
)


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


def mode_order(workflow_index, repetition):
    if workflow_index not in range(len(WORKFLOWS)) or repetition < 0:
        raise ValueError("invalid overhead order coordinates")
    shift = (workflow_index + repetition) % len(MODES)
    return MODES[shift:] + MODES[:shift]


def overhead_schedule(repetitions):
    schedule = []
    for workflow_index, workflow in enumerate(WORKFLOWS):
        for repetition in range(repetitions):
            order = mode_order(workflow_index, repetition)
            for position, selected in enumerate(order):
                schedule.append((workflow_index, workflow, repetition, selected, position, order))
    return schedule


def run_overhead(output, repetitions):
    classpath = compile_and_classpath(output)
    java = java_executable()
    parts = {}
    rows = []
    (output / "overhead/parts").mkdir(parents=True, exist_ok=True)
    for workflow_index, workflow, repetition, selected, position, order in overhead_schedule(repetitions):
        scenario = f"{Path(workflow).stem}-{selected}-r{repetition}"
        result = output / "overhead/runs" / (scenario + ".json")
        # The Java entry performs exactly one unreported warmup and one measured run in this fresh JVM.
        evidence = output / "overhead/evidence"
        run([
            java, "-Xmx4g", "-cp", classpath,
            "org.workflowsim.experiments.fgcs.scalability.EvidenceOverheadStudy",
            str(result.relative_to(ROOT)), workflow, selected, str(repetition), str(evidence.relative_to(ROOT)),
        ], output / "overhead" / (scenario + ".log"))
        measured = json.loads(result.read_text(encoding="utf-8"))
        if len(measured) != 1:
            raise ValueError(scenario + ": expected one measured row and zero warmup rows")
        row = measured[0]
        rows.append(row)
        parts.setdefault((workflow, selected), []).append(row)
    for (workflow, selected), items in parts.items():
        (output / "overhead/parts" / (Path(workflow).stem + "-" + selected + ".json")).write_text(
            json.dumps(items, indent=2) + "\n", encoding="utf-8")
    (output / "overhead/results.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")


def require(row, field, label, failures):
    if field not in row:
        failures.append(label + ": missing " + field)
        return None
    return row[field]


def check_common_row(row, failures):
    label = row.get("label", "<unknown>") if isinstance(row, dict) else "<non-object>"
    if not isinstance(row, dict):
        failures.append(label + ": result row must be an object")
        return
    for field in ("completed", "logicalTasks", "completedTasks", "energyResidual", "costResidual",
                  "makespanResidual", "tailResidual", "dependencyResidual"):
        require(row, field, label, failures)
    if not row.get("completed"):
        failures.append(label + ": incomplete")
    if row.get("logicalTasks") != row.get("completedTasks"):
        failures.append(label + ": logical completion mismatch")
    for field in ("energyResidual", "costResidual", "makespanResidual", "tailResidual", "dependencyResidual"):
        if field in row and abs(float(row[field])) > 1.0e-4:
            failures.append(label + ": residual " + field + "=" + str(row[field]))


def verify_overhead(output, repetitions):
    path = output / "overhead/results.json"
    if not path.is_file():
        raise ValueError("missing " + str(path))
    rows = json.loads(path.read_text(encoding="utf-8"))
    failures = []
    expected_count = 9 * repetitions
    if not isinstance(rows, list):
        raise ValueError("overhead results must be a JSON array")
    if len(rows) != expected_count:
        failures.append(f"overhead: expected {expected_count} rows, got {len(rows)}")
    schedule = overhead_schedule(repetitions)
    trials = {}
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            failures.append(f"overhead row {index}: not an object")
            continue
        label = row.get("label", f"overhead-row-{index}")
        explicitly_unavailable = set()
        if row.get("eventRecordingEnabled") is False:
            explicitly_unavailable.update(("meanWaitingSeconds", "meanTrueSlowdown"))
        if row.get("pythonValidationApplicable") is False:
            explicitly_unavailable.add("pythonValidationNanos")
        for field in OVERHEAD_REQUIRED_FIELDS:
            if field not in explicitly_unavailable:
                require(row, field, label, failures)
        if "peakHeapBytes" in row:
            failures.append(label + ": ambiguous peakHeapBytes is forbidden; use sampledTotalHeapPeakBytes")
        check_common_row(row, failures)
        if index >= len(schedule):
            continue
        workflow_index, workflow, repetition, selected, position, order = schedule[index]
        expected_identity = {
            "protocol": OVERHEAD_PROTOCOL, "workflow": workflow, "mode": selected, "repetition": repetition,
            "workflowIndex": workflow_index, "modeOrderIndex": position, "modeOrder": list(order),
            "label": f"{workflow}-{selected}-r{repetition}",
        }
        for field, expected in expected_identity.items():
            if field in row and row[field] != expected:
                failures.append(label + f": deterministic mode order/identity mismatch in {field}")
        if row.get("warmupCompleted") is not True:
            failures.append(label + ": one unreported warmup per fresh JVM was not recorded")
        expected_config = {
            "planningAlgorithm": "RANDOM", "schedulingAlgorithm": "STATIC",
            "dataMovementModel": "COHERENT_STORAGE_DATAFLOW_V3", "fileSystem": "LOCAL", "randomSeed": 7,
            "vmCount": 4, "eventRecorderCap": 200000, "platformProfile": "FGC_STORAGE_4_VMS_1_HOST",
        }
        for field, expected in expected_config.items():
            if field in row and row[field] != expected:
                failures.append(label + f": configuration mismatch in {field}")
        if not row.get("normalizedInput"):
            failures.append(label + ": normalized input identity is empty")
        for field in ("simulationNanos", "serializationAndSizeAccountingNanos", "artifactWriteNanos", "auditNanos",
                      "javaValidationNanos", "artifactBytes", "manifestBytes", "metricsBytes",
                      "sampledTotalHeapPeakBytes", "heapSampleCount", "heapPoolPeakSumBytes"):
            if field in row and (isinstance(row[field], bool) or not isinstance(row[field], (int, float))
                                 or not math.isfinite(row[field]) or row[field] <= 0):
                failures.append(label + ": positive measurement required for " + field)
        if ("serializationAndSizeAccountingNanos" in row and "artifactWriteNanos" in row
                and row["serializationAndSizeAccountingNanos"] != row["artifactWriteNanos"]):
            failures.append(label + ": serialization compatibility alias differs")
        if row.get("javaValidationApplicable") is not True or row.get("javaValidationStatus") != "VALID":
            failures.append(label + ": serialized Java validation did not accept the common bundle")
        if not row.get("javaValidationScope"):
            failures.append(label + ": serialized Java validation scope is missing")

        off = selected == "off"
        lifecycle = selected == "lifecycle"
        event_only = selected == "events"
        if row.get("eventRecordingEnabled") is off:
            failures.append(label + ": eventRecordingEnabled differs from mode")
        expected_network = "FILE_STORAGE_LIFECYCLE_V3" if lifecycle else "OFF"
        if row.get("networkEvidenceMode") != expected_network:
            failures.append(label + ": lifecycle recording mode mismatch")
        if off and (row.get("eventCount") != 0 or row.get("eventStreamBytes") != 0):
            failures.append(label + ": OFF retained events")
        if not off and (not isinstance(row.get("eventCount"), int) or row["eventCount"] <= 0
                        or not isinstance(row.get("eventStreamBytes"), (int, float)) or row["eventStreamBytes"] <= 0):
            failures.append(label + ": enabled recording has no serialized events")
        if off and (row.get("artifactBytes", 0) <= 0 or row.get("manifestBytes", 0) <= 0
                    or row.get("metricsBytes", 0) <= 0):
            failures.append(label + ": OFF is missing the common bundle")
        if row.get("lifecycleEvidenceAvailable") is not lifecycle:
            failures.append(label + ": lifecycle sidecar availability differs from mode")
        if lifecycle and row.get("lifecycleEvidenceBytes", 0) <= 0:
            failures.append(label + ": lifecycle evidence is empty")
        if not lifecycle and row.get("lifecycleEvidenceBytes") != 0:
            failures.append(label + ": non-lifecycle mode wrote a lifecycle sidecar")
        if event_only:
            if (row.get("pythonValidationApplicable") is not False or row.get("pythonValidationNanos") is not None
                    or row.get("pythonValidationStatus") != "NA"
                    or row.get("pythonValidationScope") != PYTHON_EVENT_ONLY_SCOPE):
                failures.append(label + ": event-only Python validation must be explicit NA with unsupported scope")
        else:
            if (row.get("pythonValidationApplicable") is not True
                    or isinstance(row.get("pythonValidationNanos"), bool)
                    or not isinstance(row.get("pythonValidationNanos"), (int, float))
                    or row["pythonValidationNanos"] <= 0):
                failures.append(label + ": supported Python validation requires a positive timing, not NA")
            expected_status = "VALID_COMPLETE" if lifecycle else "DISABLED"
            if row.get("pythonValidationStatus") != expected_status or not row.get("pythonValidationScope"):
                failures.append(label + ": Python validation status/scope mismatch")

        if off:
            if (row.get("meanWaitingSeconds") is not None or row.get("meanTrueSlowdown") is not None
                    or row.get("meanWaitingSecondsAvailable") is not False
                    or row.get("meanTrueSlowdownAvailable") is not False):
                failures.append(label + ": OFF event-dependent metrics must be null/NA, not zero")
            if row.get("eventDependentMetricAvailability") != "UNAVAILABLE_EVENT_RECORDING_DISABLED":
                failures.append(label + ": OFF availability reason mismatch")
        else:
            for field in ("meanWaitingSeconds", "meanTrueSlowdown"):
                if (isinstance(row.get(field), bool) or not isinstance(row.get(field), (int, float))
                        or not math.isfinite(row[field])):
                    failures.append(label + ": enabled event-dependent metric must be numeric: " + field)
            if (row.get("meanWaitingSecondsAvailable") is not True
                    or row.get("meanTrueSlowdownAvailable") is not True
                    or row.get("totalWaitingTimeObservationCount", 0) <= 0
                    or row.get("trueSlowdownObservationCount", 0) <= 0
                    or row.get("eventDependentMetricAvailability") != "AVAILABLE"):
                failures.append(label + ": enabled event-dependent availability/count mismatch")
        trials.setdefault((workflow, repetition), {})[selected] = row
    for (workflow, repetition), pair in trials.items():
        if set(pair) != set(MODES):
            failures.append(f"{workflow}-r{repetition}: incomplete paired modes")
            continue
        for field in ("meanWaitingSeconds", "meanTrueSlowdown"):
            if pair["events"].get(field) != pair["lifecycle"].get(field):
                failures.append(f"{workflow}-r{repetition}: paired event-dependent metrics differ in {field}")
        for field in ("normalizedInput", "dataMovementModel", "planningAlgorithm", "schedulingAlgorithm", "fileSystem",
                      "randomSeed", "vmCount", "eventRecorderCap", "platformProfile", "makespanSeconds"):
            if len({json.dumps(pair[selected].get(field), sort_keys=True) for selected in MODES}) != 1:
                failures.append(f"{workflow}-r{repetition}: paired input/model/planner/platform differs in {field}")
    if failures:
        raise ValueError("\n".join(failures))
    return len(rows)


def verify_standard(output):
    failures = []
    total = 0
    for name, count in COUNTS.items():
        if name == "overhead":
            continue
        path = output / name / "results.json"
        if not path.is_file():
            failures.append("missing " + str(path))
            continue
        rows = json.loads(path.read_text(encoding="utf-8"))
        if len(rows) != count:
            failures.append(f"{name}: expected {count} rows, got {len(rows)}")
        for row in rows:
            check_common_row(row, failures)
        total += len(rows)
    if failures:
        raise ValueError("\n".join(failures))
    return total


def verify_execution_semantics(output, expected=None):
    """FGCS-R3 (2026-11-24): every overhead manifest must declare the protocol's current execution identity."""
    expected = expected or declared_execution_semantics()
    count, failures = check_manifest_semantics(output / "overhead/evidence", expected)
    if failures:
        raise ValueError("\n".join(failures))
    return count


def verify(output, repetitions):
    standard = verify_standard(output)
    overhead = verify_overhead(output, repetitions)
    verify_execution_semantics(output)
    return standard + overhead


def write_metadata(output, repetitions):
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    metadata = {
        "protocol": "FGCS_MATRIX_V2",
        "executionSemantics": declared_execution_semantics(),
        "overheadProtocol": OVERHEAD_PROTOCOL,
        "sourceCommit": commit,
        "sourceTreeDirty": bool(status.strip()),
        "java": subprocess.run([java_executable(), "-version"], cwd=ROOT, text=True,
                               capture_output=True).stderr.strip(),
        "platform": platform.platform(),
        "overheadMeasuredRuns": 9 * repetitions,
        "overheadFreshJvmPerMeasuredRun": True,
        "overheadWarmupPerFreshJvm": 1,
        "overheadPythonEventOnlyScope": PYTHON_EVENT_ONLY_SCOPE,
        "outputPolicy": "generated evidence remains under paper/tmp and is not a Git source input",
    }
    text = json.dumps(metadata, indent=2) + "\n"
    (output / "run-metadata.json").write_text(text, encoding="utf-8")
    (output / "run-metadata.sha256").write_text(hashlib.sha256(text.encode()).hexdigest() + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Run and verify the FGCS experiment matrix")
    parser.add_argument("--output", default="paper/tmp/fgcs-round2")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--overhead-only", action="store_true")
    parser.add_argument("--standard-only", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("repetitions must be positive")
    if args.overhead_only and args.standard_only:
        parser.error("choose only one matrix subset")
    output = (ROOT / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.verify_only:
        count = (verify_overhead(output, args.repetitions) if args.overhead_only
                 else verify_standard(output) if args.standard_only else verify(output, args.repetitions))
        if args.overhead_only:
            verify_execution_semantics(output)
        print("FGCS_VERIFIED_RUNS", count)
        return
    if not args.overhead_only:
        run_standard(output, output / "logs")
    if not args.standard_only:
        run_overhead(output, args.repetitions)
    count = (verify_overhead(output, args.repetitions) if args.overhead_only
             else verify_standard(output) if args.standard_only else verify(output, args.repetitions))
    if args.overhead_only:
        verify_execution_semantics(output)
    print("FGCS_VERIFIED_RUNS", count)
    write_metadata(output, args.repetitions)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, ValueError, OSError, json.JSONDecodeError) as error:
        print("FGCS_MATRIX_FAILED:", error, file=sys.stderr)
        raise SystemExit(1)
