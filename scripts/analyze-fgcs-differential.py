#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def index(rows):
    return {int(row["taskId"]): row for row in rows}


def analyze_case(case):
    current = index(case["tasks"])
    upstream = index(case["upstreamTasks"])
    if set(current) != set(upstream):
        raise ValueError(case["label"] + ": task identity sets differ")
    task_rows = []
    for task_id in sorted(current):
        a, b = current[task_id], upstream[task_id]
        a_finish = float(a.get("taskFinish", a["jobFinish"]))
        b_finish = float(b.get("finish", b["jobFinish"]))
        task_rows.append({
            "taskId": task_id,
            "currentVmId": int(a["vmId"]),
            "upstreamVmIdNormalized": int(b["vmId"]) + 1,
            "mappingMatches": int(a["vmId"]) == int(b["vmId"]) + 1,
            "currentTaskFinishSeconds": a_finish,
            "upstreamTaskFinishSeconds": b_finish,
            "finishDeltaSeconds": a_finish - b_finish,
            "currentLengthMi": a.get("lengthMi"),
            "upstreamLengthMi": b.get("lengthMi"),
            "currentVmMips": a.get("vmMips"),
            "upstreamVmMips": b.get("vmMips"),
        })
    task_rows.sort(key=lambda row: (-abs(row["finishDeltaSeconds"]), row["taskId"]))
    makespan_current = max(float(row["jobFinish"]) for row in current.values())
    makespan_upstream = max(float(row["jobFinish"]) for row in upstream.values())
    return {
        "label": case["label"],
        "workflow": Path(case["workflow"]).name,
        "scheduler": case["scheduler"],
        "currentMakespanSeconds": makespan_current,
        "upstreamMakespanSeconds": makespan_upstream,
        "makespanDeltaSeconds": makespan_current - makespan_upstream,
        "mappingMatchedTasks": sum(row["mappingMatches"] for row in task_rows),
        "logicalTasks": len(task_rows),
        "mappingMismatchTaskIds": [row["taskId"] for row in task_rows if not row["mappingMatches"]],
        "criticalPathTaskIdsCurrent": case.get("criticalPathTaskIds", []),
        "currentPlannerModel": "SHARED_STORAGE_HEFT" if case["scheduler"] == "STATIC" else "READY_BATCH_MINMIN",
        "upstreamPlannerModel": "CLASSIC_POINT_TO_POINT_HEFT" if case["scheduler"] == "STATIC" else "LEGACY_MINMIN",
        "taskDifferences": task_rows,
    }


def main():
    parser = argparse.ArgumentParser(description="Adjudicate current/upstream differential observations")
    parser.add_argument("observations")
    parser.add_argument("output")
    args = parser.parse_args()
    cases = json.loads(Path(args.observations).read_text(encoding="utf-8"))
    rows = [analyze_case(case) for case in cases]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"schema": "fgcs-differential-adjudication-v1",
                                  "interpretation": "Observed differences are not proof of a defect. Planner communication semantics and runtime windows differ by case.",
                                  "cases": rows}, indent=2) + "\n", encoding="utf-8")
    for row in rows:
        print(row["label"], "makespanDelta", row["makespanDeltaSeconds"],
              "mapping", f"{row['mappingMatchedTasks']}/{row['logicalTasks']}",
              "criticalPath", row["criticalPathTaskIdsCurrent"])


if __name__ == "__main__":
    main()
