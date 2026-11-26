#!/usr/bin/env python3
import argparse
import csv
import json
import math
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]
MODES = ("off", "events", "lifecycle")
FIELDS = (
    "simulationNanos", "serializationAndSizeAccountingNanos", "auditNanos", "javaValidationNanos",
    "pythonValidationNanos", "eventCount", "artifactBytes", "manifestBytes", "metricsBytes", "eventStreamBytes",
    "lifecycleEvidenceBytes", "heapBeforeBytes", "sampledTotalHeapPeakBytes", "heapSampleCount",
    "heapPoolPeakSumBytes", "threadAllocatedBytes",
)
STAT_SUFFIXES = ("Median", "Min", "Max", "Range", "Q1", "Q3", "IQR")
COMPARISONS = (
    ("EVENTS_MINUS_OFF", "off", "events"),
    ("LIFECYCLE_MINUS_OFF", "off", "lifecycle"),
    ("LIFECYCLE_MINUS_EVENTS", "events", "lifecycle"),
)
PYTHON_EVENT_ONLY_SCOPE = "NOT_APPLICABLE_EVENT_ONLY_BUNDLE_WITHOUT_LIFECYCLE_CAPTURE"


def mode(label):
    for candidate in MODES:
        if "-" + candidate + "-" in label:
            return candidate
    raise ValueError("unknown overhead mode in " + label)


def selected_workflow_mode(row):
    selected = row.get("mode") or mode(row["label"])
    if selected not in MODES:
        raise ValueError("unknown overhead mode in " + row.get("label", "<unknown>"))
    workflow = row.get("workflow")
    if workflow is None:
        workflow = row["label"].split("-" + selected + "-", 1)[0]
    return workflow, selected


def numeric_values(items, field, location):
    values = []
    for item in items:
        if field not in item:
            if field == "pythonValidationNanos" and item.get("pythonValidationApplicable") is False:
                value = None
                values.append(value)
                continue
            raise ValueError(location + ": missing " + field)
        value = item[field]
        if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float))
                                  or not math.isfinite(value)):
            raise ValueError(location + ": nonnumeric " + field)
        values.append(value)
    numeric = [float(value) for value in values if value is not None]
    if numeric and len(numeric) != len(values):
        raise ValueError(location + ": mixed numeric/NA values for " + field)
    return values, numeric


def distribution(values):
    if not values:
        return {suffix: None for suffix in STAT_SUFFIXES}
    ordered = sorted(values)
    if len(ordered) == 1:
        q1 = q3 = ordered[0]
    else:
        q1, _, q3 = statistics.quantiles(ordered, n=4, method="inclusive")
    return {
        "Median": statistics.median(ordered),
        "Min": ordered[0],
        "Max": ordered[-1],
        "Range": ordered[-1] - ordered[0],
        "Q1": q1,
        "Q3": q3,
        "IQR": q3 - q1,
    }


def overhead(rows):
    groups = {}
    for row in rows:
        selected_workflow, selected = selected_workflow_mode(row)
        if "repetition" not in row:
            raise ValueError(f"{selected_workflow}/{selected}: missing repetition")
        repetition = row["repetition"]
        key = (selected_workflow, selected)
        by_repetition = groups.setdefault(key, {})
        if repetition in by_repetition:
            raise ValueError(f"{selected_workflow}/{selected}: duplicate repetition {repetition}")
        by_repetition[repetition] = row
    result = []
    for (workflow, selected), by_repetition in sorted(groups.items()):
        items = [by_repetition[key] for key in sorted(by_repetition)]
        row = {"workflow": workflow, "mode": selected, "runs": len(items),
               "logicalTasks": items[0]["logicalTasks"]}
        trials = []
        for item in items:
            trial = {key: item.get(key) for key in ("repetition", "workflowIndex", "modeOrderIndex")}
            for field in FIELDS:
                trial[field] = item.get(field)
            for field in ("pythonValidationStatus", "pythonValidationScope"):
                trial[field] = item.get(field)
            trials.append(trial)
        row["trials"] = trials
        for field in FIELDS:
            _, values = numeric_values(items, field, f"{workflow}/{selected}")
            stats = distribution(values)
            for suffix in STAT_SUFFIXES:
                row[field + suffix] = stats[suffix]
            row[field + "Availability"] = "AVAILABLE" if values else "NA"
        result.append(row)
    return result


def paired_deltas(rows):
    trials_by_case = {}
    for row in rows:
        workflow, selected = selected_workflow_mode(row)
        if "repetition" not in row:
            raise ValueError(f"{workflow}/{selected}: missing repetition")
        key = (workflow, row["repetition"])
        pair = trials_by_case.setdefault(key, {})
        if selected in pair:
            raise ValueError(f"{workflow}/r{row['repetition']}: duplicate {selected} row")
        pair[selected] = row
    raw_trials = []
    for (workflow, repetition), pair in sorted(trials_by_case.items()):
        if set(pair) != set(MODES):
            raise ValueError(f"{workflow}/r{repetition}: incomplete paired modes")
        for comparison, baseline_mode, treatment_mode in COMPARISONS:
            baseline = pair[baseline_mode]
            treatment = pair[treatment_mode]
            for field in FIELDS:
                for row in (baseline, treatment):
                    if field not in row and not (
                            field == "pythonValidationNanos" and row.get("pythonValidationApplicable") is False):
                        raise ValueError(f"{workflow}/r{repetition}: missing {field}")
                baseline_value = baseline.get(field)
                treatment_value = treatment.get(field)
                for value in (baseline_value, treatment_value):
                    if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float))
                                              or not math.isfinite(value)):
                        raise ValueError(f"{workflow}/r{repetition}: nonnumeric paired field {field}")
                delta = (None if baseline_value is None or treatment_value is None
                         else treatment_value - baseline_value)
                if delta is None:
                    scope = ("NA_EVENT_ONLY_PYTHON_VALIDATION_UNSUPPORTED"
                             if field == "pythonValidationNanos" and "events" in (baseline_mode, treatment_mode)
                             else "NA_MEASUREMENT_UNAVAILABLE")
                else:
                    scope = "PAIRED_SAME_WORKFLOW_REPETITION"
                raw_trials.append({
                    "workflow": workflow, "repetition": repetition, "comparison": comparison, "field": field,
                    "baselineMode": baseline_mode, "treatmentMode": treatment_mode,
                    "baselineValue": baseline_value, "treatmentValue": treatment_value,
                    "delta": delta, "scope": scope,
                })
    groups = {}
    for trial in raw_trials:
        groups.setdefault((trial["workflow"], trial["comparison"], trial["field"]), []).append(trial)
    summary = []
    for (workflow, comparison, field), items in sorted(groups.items()):
        deltas = [item["delta"] for item in items]
        numeric = [float(value) for value in deltas if value is not None]
        if numeric and len(numeric) != len(deltas):
            raise ValueError(f"{workflow}/{comparison}/{field}: mixed numeric/NA paired deltas")
        stats = distribution(numeric)
        row = {"workflow": workflow, "comparison": comparison, "field": field, "runs": len(items),
               "scope": items[0]["scope"]}
        for suffix in STAT_SUFFIXES:
            row["delta" + suffix] = stats[suffix]
        summary.append(row)
    return {
        "protocol": "FGCS_OVERHEAD_ROUND2_V2",
        "pairing": "SAME_WORKFLOW_AND_REPETITION_WITH_BALANCED_INTERLEAVED_MODE_ORDER",
        "trials": raw_trials,
        "summary": summary,
    }


def signed_relation(heft, minmin):
    return "tie" if heft == minmin else ("heft-lower" if heft < minmin else "minmin-lower")


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
        raw_relation = signed_relation(heft, minmin)
        relation = ("tie" if math.isclose(float(heft), float(minmin), rel_tol=1.0e-12, abs_tol=1.0e-9)
                    else raw_relation)
        cells.append({"case": base, "heftMakespanSeconds": heft, "minMinMakespanSeconds": minmin,
                      "minMinMinusHeftSeconds": minmin - heft,
                      "relation": relation, "rawRelation": raw_relation,
                      "tolerance": "ABS_1E-9_SECONDS_REL_1E-12"})
    counts = {relation: sum(cell["relation"] == relation for cell in cells)
              for relation in ("heft-lower", "minmin-lower", "tie")}
    raw_counts = {relation: sum(cell["rawRelation"] == relation for cell in cells)
                  for relation in ("heft-lower", "minmin-lower", "tie")}
    return {"cells": cells, "counts": counts, "rawSignedCounts": raw_counts}


def energy(rows):
    result = []
    for row in rows:
        result.append({key: row[key] for key in (
            "label", "makespanSeconds", "energyJoules", "energyDelayProduct", "meanWaitingSeconds")})
    return result


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Summarize completed FGCS result JSON files")
    parser.add_argument("--input", default="paper/tmp/fgcs-round2")
    parser.add_argument("--overhead-only", action="store_true")
    args = parser.parse_args()
    root = (ROOT / args.input).resolve()
    names = ("overhead",) if args.overhead_only else (
        "differential", "sensitivity", "energy", "ablation", "recovery", "overhead")
    rows = {name: json.loads((root / name / "results.json").read_text(encoding="utf-8")) for name in names}
    overhead_summary = overhead(rows["overhead"])
    paired = paired_deltas(rows["overhead"])
    environment_path = (root / "run-metadata.json") if (root / "run-metadata.json").is_file() else (
        root / "revision-metadata.json")
    environment = json.loads(environment_path.read_text(encoding="utf-8")) if environment_path.is_file() else None
    summary = {
        "protocol": "FGCS_MATRIX_V2",
        "executionSemantics": environment.get("executionSemantics") if environment else None,
        "overheadProtocol": "FGCS_OVERHEAD_ROUND2_V2",
        "counts": {name: len(items) for name, items in rows.items()},
        "completed": {name: sum(item["completed"] for item in items) for name, items in rows.items()},
        "sensitivity": sensitivity(rows["sensitivity"]) if "sensitivity" in rows else None,
        "overhead": overhead_summary,
        "overheadPairedDeltas": paired,
        "energy": energy(rows["energy"]) if "energy" in rows else None,
        "overheadConfiguration": {
            "measuredRunsAtFiveRepetitions": 45,
            "warmup": "one unreported full-pipeline warmup in every fresh measured-run JVM",
            "inputPopulation": "UNIQUE_PRODUCER_FILE_IDENTITY_V1 for every mode",
            "dataMovementModel": "COHERENT_STORAGE_DATAFLOW_V3 for every mode",
            "planner": "RANDOM for every mode",
            "fileSystem": "LOCAL for every mode",
            "modeOrder": "cyclic Latin-square rotation by workflowIndex + repetition; order recorded per row",
            "modes": {
                "off": "event recording disabled; common manifest+metrics bundle and empty event stream",
                "events": "ordinary events enabled; no lifecycle sidecar; Python lifecycle validator NA",
                "lifecycle": "ordinary events plus storage lifecycle V3; Java and Python serialized validation",
            },
            "pythonEventOnlyScope": PYTHON_EVENT_ONLY_SCOPE,
            "heap": "sampledTotalHeapPeakBytes is a sampled approximation; heapPoolPeakSumBytes is not simultaneous",
            "eventDependentOffMetrics": "null/NA with availability and observation counts; never numeric zero",
            "modesDifferOnlyByRecording": True,
        },
        "overheadEnvironment": environment,
    }
    write((root / "overhead" if args.overhead_only else root) / "matrix-summary.json", summary)
    write(root / "overhead/overhead-summary.json", summary["overhead"])
    write(root / "overhead/overhead-paired-deltas.json", paired)
    if "sensitivity" in rows:
        write(root / "sensitivity-summary.json", summary["sensitivity"])
    if "energy" in rows:
        write(root / "energy-summary.json", summary["energy"])
    fieldnames = ["workflow", "mode", "runs", "logicalTasks"]
    for field in FIELDS:
        fieldnames.extend(field + suffix for suffix in STAT_SUFFIXES)
        fieldnames.append(field + "Availability")
    with (root / "overhead/overhead-summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(summary["overhead"])
    with (root / "overhead/overhead-paired-deltas.csv").open("w", newline="", encoding="utf-8") as stream:
        fieldnames = ["workflow", "repetition", "comparison", "field", "baselineMode", "treatmentMode",
                      "baselineValue", "treatmentValue", "delta", "scope"]
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(paired["trials"])
    print(json.dumps(summary["counts"], sort_keys=True))
    if summary["sensitivity"] is not None:
        print(json.dumps(summary["sensitivity"]["counts"], sort_keys=True))


if __name__ == "__main__":
    main()
