#!/usr/bin/env python3
import argparse
import csv
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]
MODES = ("off", "events", "lifecycle")
FIELDS = (
    "simulationNanos", "artifactWriteNanos", "auditNanos", "eventCount", "artifactBytes",
    "heapBeforeBytes", "peakHeapBytes", "threadAllocatedBytes",
)


def mode(label):
    for candidate in MODES:
        if "-" + candidate + "-" in label:
            return candidate
    raise ValueError("unknown overhead mode in " + label)


def overhead(rows):
    groups = {}
    for row in rows:
        selected = mode(row["label"])
        workflow = row["label"].split("-" + selected + "-", 1)[0]
        groups.setdefault((workflow, selected), []).append(row)
    result = []
    for (workflow, selected), items in sorted(groups.items()):
        row = {"workflow": workflow, "mode": selected, "runs": len(items),
               "logicalTasks": items[0]["logicalTasks"]}
        for field in FIELDS:
            values = [float(item[field]) for item in items]
            row[field + "Median"] = statistics.median(values)
            row[field + "Min"] = min(values)
            row[field + "Max"] = max(values)
        result.append(row)
    return result


def sensitivity(rows):
    groups = {}
    for row in rows:
        label = row["label"]
        algorithm = "STATIC" if label.endswith("-STATIC") else "READY_BATCH_MINMIN"
        base = label.rsplit("-", 1)[0]
        groups.setdefault(base, {})[algorithm] = row["makespanSeconds"]
    cells = []
    for base, pair in sorted(groups.items()):
        if set(pair) != {"STATIC", "READY_BATCH_MINMIN"}:
            raise ValueError("incomplete sensitivity pair " + base)
        heft, minmin = pair["STATIC"], pair["READY_BATCH_MINMIN"]
        cells.append({"case": base, "heftMakespanSeconds": heft, "minMinMakespanSeconds": minmin,
                      "minMinMinusHeftSeconds": minmin - heft,
                      "relation": "tie" if heft == minmin else ("heft-lower" if heft < minmin else "minmin-lower")})
    counts = {relation: sum(cell["relation"] == relation for cell in cells)
              for relation in ("heft-lower", "minmin-lower", "tie")}
    return {"cells": cells, "counts": counts}


def energy(rows):
    result = []
    for row in rows:
        result.append({key: row[key] for key in (
            "label", "makespanSeconds", "energyJoules", "energyDelayProduct", "meanWaitingSeconds")})
    return result


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Summarize completed FGCS result JSON files")
    parser.add_argument("--input", default="paper/tmp/experiments")
    args = parser.parse_args()
    root = (ROOT / args.input).resolve()
    rows = {name: json.loads((root / name / "results.json").read_text(encoding="utf-8"))
            for name in ("differential", "sensitivity", "energy", "ablation", "recovery", "overhead")}
    summary = {
        "protocol": "FGCS_MATRIX_V1",
        "counts": {name: len(items) for name, items in rows.items()},
        "completed": {name: sum(item["completed"] for item in items) for name, items in rows.items()},
        "sensitivity": sensitivity(rows["sensitivity"]),
        "overhead": overhead(rows["overhead"]),
        "energy": energy(rows["energy"]),
    }
    write(root / "matrix-summary.json", summary)
    write(root / "overhead-summary.json", summary["overhead"])
    write(root / "sensitivity-summary.json", summary["sensitivity"])
    write(root / "energy-summary.json", summary["energy"])
    fieldnames = ["workflow", "mode", "runs", "logicalTasks"]
    for field in FIELDS:
        fieldnames.extend(field + suffix for suffix in ("Median", "Min", "Max"))
    with (root / "overhead-summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(summary["overhead"])
    print(json.dumps(summary["counts"], sort_keys=True))
    print(json.dumps(summary["sensitivity"]["counts"], sort_keys=True))


if __name__ == "__main__":
    main()
