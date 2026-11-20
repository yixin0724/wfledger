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
)


def load(root, name):
    return {row["label"]: row for row in json.loads((Path(root) / name / "results.json").read_text())}


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
                xv, yv = x.get(field), y.get(field)
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


def compare_revision(left, right):
    left, right = Path(left), Path(right)
    differences = []
    a = json.loads((left / "verification/results.json").read_text())
    b = json.loads((right / "verification/results.json").read_text())
    differences.extend(scalar_differences(a["rq1"], b["rq1"], "verification.rq1"))
    differences.extend(scalar_differences(a["rq2"], b["rq2"], "verification.rq2"))
    for x, y in zip(a["cases"], b["cases"]):
        if x["id"] != y["id"]:
            differences.append("verification case order differs")
            continue
        for u, v in zip(x["observations"], y["observations"]):
            for field in ("validator", "expected", "actual", "reasonMatched"):
                if u[field] != v[field]:
                    differences.append(x["id"] + ": " + field + " differs")
    a = json.loads((left / "microbench/results.json").read_text())
    b = json.loads((right / "microbench/results.json").read_text())
    for x, y in zip(a["cases"], b["cases"]):
        if x["id"] != y["id"]:
            differences.append("microbench case order differs")
            continue
        differences.extend(scalar_differences(x["expected"], y["expected"], x["id"] + ".expected"))
        differences.extend(scalar_differences(x["observed"], y["observed"], x["id"] + ".observed"))
    return len(a["cases"]), differences


def main():
    parser = argparse.ArgumentParser(description="Compare FGCS core results across independent reproductions")
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("--names", default="differential,sensitivity,energy,ablation,recovery,overhead")
    parser.add_argument("--revision", action="store_true", help="also compare adversarial and microbenchmark outcomes")
    args = parser.parse_args()
    names = [name for name in args.names.split(",") if name]
    compared, differences = compare(args.left, args.right, names)
    revision_cases = 0
    if args.revision:
        revision_cases, revision_differences = compare_revision(args.left, args.right)
        differences.extend(revision_differences)
    if differences:
        print("FGCS_REPRODUCTION_DIFFERENCES", len(differences))
        for difference in differences[:50]:
            print(difference)
        raise SystemExit(1)
    print("FGCS_REPRODUCTION_CORE_MATCH", compared)
    if args.revision:
        print("FGCS_REPRODUCTION_REVISION_MATCH microbenchContracts=" + str(revision_cases)
              + " adversarialBundles=11")


if __name__ == "__main__":
    main()
