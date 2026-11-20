#!/usr/bin/env python3
import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE_FIELDS = (
    "makespanSeconds", "meanWaitingSeconds", "meanTrueSlowdown", "throughputJobsPerSecond",
    "meanVmUtilization", "energyJoules", "energyDelayProduct", "modeledProcessingCost",
    "logicalTasks", "completedTasks", "eventCount", "energyResidual", "costResidual",
    "makespanResidual", "tailResidual", "dependencyResidual", "completed",
    "recoveryClassification", "failureModelEnabled", "failedJobs", "retryJobsCreated",
    "deadlineSeconds", "deadlineFeasible", "deadlineFactor", "policy",
)


OPTIONAL_CORE_FIELDS = {
    "recoveryClassification", "failureModelEnabled", "failedJobs", "retryJobsCreated",
    "deadlineSeconds", "deadlineFeasible", "deadlineFactor", "policy",
}


def unique_index(rows, key, scope):
    result = {}
    for row in rows:
        if key not in row:
            raise ValueError(scope + ": missing key " + key)
        value = row[key]
        if value in result:
            raise ValueError(scope + ": duplicate " + key + "=" + str(value))
        result[value] = row
    return result


def load(root, name):
    rows = json.loads((Path(root) / name / "results.json").read_text())
    return unique_index(rows, "label", name)


def compare(left, right, names):
    differences = []
    compared = 0
    for name in names:
        a, b = load(left, name), load(right, name)
        if set(a) != set(b):
            differences.append(name + ": label sets differ")
            continue
        for label in sorted(a):
            x, y = a[label], b[label]
            if x.get("mapping") != y.get("mapping"):
                differences.append(label + ": mapping differs")
            for field in CORE_FIELDS:
                x_missing, y_missing = field not in x, field not in y
                if x_missing or y_missing:
                    if x_missing != y_missing or field not in OPTIONAL_CORE_FIELDS:
                        differences.append(label + ": " + field + " presence differs")
                    continue
                xv, yv = x.get(field), y.get(field)
                if xv is None or yv is None:
                    if xv is not yv:
                        differences.append(label + ": " + field + " null status differs")
                    continue
                if isinstance(xv, bool) or isinstance(yv, bool):
                    if xv != yv:
                        differences.append(label + ": " + field + " differs")
                elif isinstance(xv, (int, float)) and isinstance(yv, (int, float)):
                    if not math.isclose(float(xv), float(yv), rel_tol=1e-12, abs_tol=1e-9):
                        differences.append(label + ": " + field + " differs")
                elif xv != yv:
                    differences.append(label + ": " + field + " differs")
            compared += 1
    return compared, differences


def scalar_differences(left, right, prefix):
    differences = []
    for key in sorted(set(left) | set(right)):
        if key not in left or key not in right:
            differences.append(prefix + ": missing " + key)
            continue
        x, y = left[key], right[key]
        if isinstance(x, (int, float)) and not isinstance(x, bool) and isinstance(y, (int, float)) and not isinstance(y, bool):
            if not math.isclose(float(x), float(y), rel_tol=1e-12, abs_tol=1e-9):
                differences.append(prefix + ": " + key + " differs")
        elif x != y:
            differences.append(prefix + ": " + key + " differs")
    return differences


def compare_revision(left, right, require_extended=False):
    left, right = Path(left), Path(right)
    differences = []
    a = json.loads((left / "verification/results.json").read_text())
    b = json.loads((right / "verification/results.json").read_text())
    differences.extend(scalar_differences(a["rq1"], b["rq1"], "verification.rq1"))
    differences.extend(scalar_differences(a["rq2"], b["rq2"], "verification.rq2"))
    left_cases = unique_index(a["cases"], "id", "verification")
    right_cases = unique_index(b["cases"], "id", "verification")
    if set(left_cases) != set(right_cases):
        differences.append("verification case ID sets differ")
    for case_id in sorted(set(left_cases) & set(right_cases)):
        x, y = left_cases[case_id], right_cases[case_id]
        left_observations = unique_index(x["observations"], "validator", case_id)
        right_observations = unique_index(y["observations"], "validator", case_id)
        if set(left_observations) != set(right_observations):
            differences.append(case_id + ": validator sets differ")
        for validator in sorted(set(left_observations) & set(right_observations)):
            u, v = left_observations[validator], right_observations[validator]
            for field in ("expected", "actual", "reasonMatched"):
                if u[field] != v[field]:
                    differences.append(case_id + "/" + validator + ": " + field + " differs")
            fragment = u.get("requiredReasonFragment")
            if fragment and fragment not in v.get("actualReason", ""):
                differences.append(case_id + "/" + validator + ": semantic reason fragment differs")
    a = json.loads((left / "microbench/results.json").read_text())
    b = json.loads((right / "microbench/results.json").read_text())
    left_cases = unique_index(a["cases"], "id", "verification")
    right_cases = unique_index(b["cases"], "id", "verification")
    if set(left_cases) != set(right_cases):
        differences.append("microbench case ID sets differ")
    for case_id in sorted(set(left_cases) & set(right_cases)):
        x, y = left_cases[case_id], right_cases[case_id]
        differences.extend(scalar_differences(x["expected"], y["expected"], case_id + ".expected"))
        differences.extend(scalar_differences(x["observed"], y["observed"], case_id + ".observed"))
    microbench_count = len(a["cases"])
    for name in ("rerun", "contracts"):
        left_path, right_path = left / name / "results.json", right / name / "results.json"
        if not left_path.is_file() and not right_path.is_file():
            if require_extended:
                differences.append(name + ": required round-two results are missing")
            continue
        if not left_path.is_file() or not right_path.is_file():
            differences.append(name + ": result presence differs")
            continue
        a = json.loads(left_path.read_text())
        b = json.loads(right_path.read_text())
        if name == "rerun":
            differences.extend(scalar_differences(a["summary"], b["summary"], "rerun.summary"))
        left_cases = unique_index(a["cases"], "caseId" if name == "rerun" else "id", name)
        right_cases = unique_index(b["cases"], "caseId" if name == "rerun" else "id", name)
        if set(left_cases) != set(right_cases):
            differences.append(name + ": case ID sets differ")
        for case_id in sorted(set(left_cases) & set(right_cases)):
            x, y = left_cases[case_id], right_cases[case_id]
            if name == "rerun":
                for field in ("expectedVerdict", "actualVerdict", "expectationMet"):
                    if x[field] != y[field]:
                        differences.append(case_id + ": " + field + " differs")
            else:
                if x["passed"] != y["passed"]:
                    differences.append(case_id + ": passed differs")
                differences.extend(scalar_differences(x["expected"], y["expected"], case_id + ".expected"))
                differences.extend(scalar_differences(x["observed"], y["observed"], case_id + ".observed"))
    return microbench_count, differences


def compare_original_baselines(left, right):
    differences = []
    compared = 0
    for name in ("makespan-baseline", "makespan-scale"):
        left_rows = json.loads((Path(left) / name / "results.json").read_text())
        right_rows = json.loads((Path(right) / name / "results.json").read_text())
        left_index = {(row["dataset"], row["algorithm"]): row for row in left_rows}
        right_index = {(row["dataset"], row["algorithm"]): row for row in right_rows}
        if len(left_index) != len(left_rows) or len(right_index) != len(right_rows):
            differences.append(name + ": duplicate dataset/algorithm key")
            continue
        if set(left_index) != set(right_index):
            differences.append(name + ": dataset/algorithm sets differ")
            continue
        for key in sorted(left_index):
            differences.extend(scalar_differences(left_index[key], right_index[key], name + "/" + key[0] + "/" + key[1]))
            compared += 1
    return compared, differences


def main():
    parser = argparse.ArgumentParser(description="Compare FGCS core results across independent reproductions")
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("--names", default="differential,sensitivity,energy,ablation,recovery,overhead")
    parser.add_argument("--revision", action="store_true", help="also compare adversarial and microbenchmark outcomes")
    parser.add_argument("--baseline", action="store_true", help="also compare all original baseline and scale metrics")
    parser.add_argument("--require-extended", action="store_true", help="require rerun and semantic-contract results")
    args = parser.parse_args()
    names = [name for name in args.names.split(",") if name]
    compared, differences = compare(args.left, args.right, names)
    baseline_cases = 0
    if args.baseline:
        baseline_cases, baseline_differences = compare_original_baselines(args.left, args.right)
        differences.extend(baseline_differences)
    revision_cases = 0
    adversarial_cases = 0
    if args.revision:
        revision_cases, revision_differences = compare_revision(args.left, args.right, args.require_extended)
        adversarial_cases = len(json.loads((Path(args.left) / "verification/results.json").read_text())["cases"])
        differences.extend(revision_differences)
    if differences:
        print("FGCS_REPRODUCTION_DIFFERENCES", len(differences))
        for difference in differences[:50]:
            print(difference)
        raise SystemExit(1)
    print("FGCS_REPRODUCTION_CORE_MATCH", compared)
    if args.baseline:
        print("FGCS_REPRODUCTION_BASELINE_MATCH", baseline_cases)
    if args.revision:
        print("FGCS_REPRODUCTION_REVISION_MATCH microbenchContracts=" + str(revision_cases)
              + " adversarialBundles=" + str(adversarial_cases))


if __name__ == "__main__":
    main()
