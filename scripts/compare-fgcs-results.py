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


def main():
    parser = argparse.ArgumentParser(description="Compare FGCS core results across independent reproductions")
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("--names", default="differential,sensitivity,energy,ablation,recovery,overhead")
    args = parser.parse_args()
    names = [name for name in args.names.split(",") if name]
    compared, differences = compare(args.left, args.right, names)
    if differences:
        print("FGCS_REPRODUCTION_DIFFERENCES", len(differences))
        for difference in differences[:50]:
            print(difference)
        raise SystemExit(1)
    print("FGCS_REPRODUCTION_CORE_MATCH", compared)


if __name__ == "__main__":
    main()
