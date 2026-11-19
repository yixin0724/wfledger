#!/usr/bin/env python3
"""Compare retained FGCS observations and optionally reproduce both execution sides.

A PASS means that the observation schema, task coverage/success, numeric timing domains, and
DAX parent-before-child constraints hold. Mapping, order, and makespan differences are reported
as observations, not treated as proof of a defect in either implementation. No third-party source
is stored here: --reproduce downloads and compiles the commit-pinned upstream tree in a work
 directory outside this study.
"""

from __future__ import annotations

import argparse
import copy
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[4]
STUDY = Path(__file__).resolve().parent
LOCK_PATH = STUDY / "upstream-lock.json"
ADAPTER_PATH = STUDY / "UpstreamRunner.java.txt"
DEFAULT_OBSERVATIONS = Path("paper/tmp/experiments/differential/upstream-observations.json")
DEFAULT_WORK = Path("paper/tmp/fgcs-differential-reproduction")
EXPORTER = "org.workflowsim.experiments.fgcs.differential.UpstreamDifferentialExporter"
EPSILON = Decimal("0.0000001")
EQUALITY_TOLERANCE = Decimal("0.000000001")
SCHEDULERS = ("READY_BATCH_MINMIN", "STATIC")
SHAPES = ("chain", "fan", "independent")
REAL_WORKFLOWS = {
    "Montage_50.dax": "datasets/dax/montage/n50/Montage_50.dax",
    "CyberShake_50.dax": "datasets/dax/cybershake/n50/CyberShake_50.dax",
    "Sipht_60.dax": "datasets/dax/sipht/n60/Sipht_60.dax",
}
CASE_BASES = SHAPES + tuple(REAL_WORKFLOWS)
EXPECTED_LABELS = tuple(base + "-" + scheduler + "-s7" for base in CASE_BASES for scheduler in SCHEDULERS)


class CheckError(ValueError):
    """An input cannot be interpreted under the differential observation contract."""


def _unique_members(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CheckError("duplicate JSON member: " + key)
        result[key] = value
    return result


def _reject_constant(value):
    raise CheckError("non-standard JSON numeric constant: " + value)


def decode_json(raw):
    try:
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8")
        return json.loads(raw, parse_float=Decimal, parse_constant=_reject_constant,
                          object_pairs_hook=_unique_members)
    except (ValueError, UnicodeError) as error:
        raise CheckError(str(error)) from error


def read_json(path):
    return decode_json(Path(path).read_bytes())


def _json_value(value):
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise CheckError("cannot serialize a non-finite Decimal")
        return int(value) if value == value.to_integral_value() else float(value)
    if isinstance(value, dict):
        return {key: _json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    return value


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(_json_value(value), indent=2, ensure_ascii=True, allow_nan=False) + "\n"
    path.write_text(text, encoding="utf-8")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def rooted(path):
    path = Path(path)
    return path if path.is_absolute() else ROOT / path


def _integer(value, field):
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise CheckError(field + " must be an integer")
    try:
        exact = Decimal(str(value))
    except InvalidOperation as error:
        raise CheckError(field + " must be an integer") from error
    if not exact.is_finite() or exact != exact.to_integral_value():
        raise CheckError(field + " must be an integer")
    return int(exact)


def _number(value, field):
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise CheckError(field + " must be numeric")
    try:
        result = Decimal(str(value))
    except InvalidOperation as error:
        raise CheckError(field + " must be numeric") from error
    if not result.is_finite():
        raise CheckError(field + " must be finite")
    return result


def _tag(element):
    return element.tag.rsplit("}", 1)[-1]


def parse_dax(path):
    """Assign the parser-contract task IDs by job document order, starting at one."""
    try:
        root = ET.parse(str(path)).getroot()
    except (ET.ParseError, OSError) as error:
        raise CheckError("cannot parse DAX " + str(path) + ": " + str(error)) from error
    jobs = [element for element in root if _tag(element) == "job"]
    if not jobs:
        raise CheckError("DAX has no direct job children: " + str(path))
    task_by_name = {}
    for index, job in enumerate(jobs, 1):
        name = job.get("id")
        if not name or name in task_by_name:
            raise CheckError("missing or duplicate DAX job id in " + str(path))
        task_by_name[name] = index
    parents = {task_id: [] for task_id in task_by_name.values()}
    for child in (element for element in root if _tag(element) == "child"):
        child_name = child.get("ref")
        if child_name not in task_by_name:
            raise CheckError("DAX child references an unknown job: " + str(child_name))
        child_id = task_by_name[child_name]
        for parent in (element for element in child if _tag(element) == "parent"):
            parent_name = parent.get("ref")
            if parent_name not in task_by_name:
                raise CheckError("DAX parent references an unknown job: " + str(parent_name))
            parents[child_id].append(task_by_name[parent_name])
    return parents


def resolve_workflow(case, base, observations_path):
    declared = case.get("workflow")
    if not isinstance(declared, str) or not declared:
        raise CheckError("workflow must be a nonempty path string")
    expected_name = base + ".dax" if base in SHAPES else base
    if Path(declared).name != expected_name:
        raise CheckError("case workflow does not match its label: " + declared)
    path = Path(declared)
    candidates = [path] if path.is_absolute() else [ROOT / path, observations_path.parent / path]
    if base in REAL_WORKFLOWS:
        candidates.append(ROOT / REAL_WORKFLOWS[base])
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise CheckError("cannot locate workflow " + declared)


def _issue(issues, code, label, detail):
    issues.append({"code": code, "label": label, "detail": detail})


def inspect_side(raw_tasks, expected_ids, parents, upstream, label, issues):
    side = "upstream" if upstream else "current"
    parsed = {}
    order = []
    complete = True
    if not isinstance(raw_tasks, list):
        _issue(issues, side + "-task-schema", label, "tasks must be an array")
        return {"byId": parsed, "order": order, "complete": False, "makespan": None,
                "dependencyViolations": []}
    for index, task in enumerate(raw_tasks):
        location = side + " task[" + str(index) + "]"
        if not isinstance(task, dict):
            _issue(issues, side + "-task-schema", label, location + " must be an object")
            complete = False
            continue
        try:
            task_id = _integer(task.get("taskId"), location + ".taskId")
            vm_id = _integer(task.get("vmId"), location + ".vmId")
            start = _number(task.get("start"), location + ".start")
            job_finish = _number(task.get("jobFinish"), location + ".jobFinish")
            if str(task.get("status")) != "4":
                raise CheckError(location + " did not finish successfully (status=4 required)")
            minimum_vm, maximum_vm = (0, 3) if upstream else (1, 4)
            if not minimum_vm <= vm_id <= maximum_vm:
                raise CheckError(location + " has a VM ID outside the four-VM contract")
            if start < 0 or job_finish < 0 or start > job_finish + EPSILON:
                raise CheckError(location + " has invalid start/jobFinish timing")
            if upstream:
                finish = _number(task.get("finish"), location + ".finish")
                # Upstream Task and Job finish timestamps have separate update paths. A small
                # Task-finish/Job-finish discrepancy is not interpreted as incomplete work.
                if finish < start - EPSILON:
                    raise CheckError(location + " has a task finish before its start")
        except CheckError as error:
            _issue(issues, side + "-task-contract", label, str(error))
            complete = False
            continue
        if task_id in parsed:
            _issue(issues, side + "-duplicate-task", label, "duplicate task ID " + str(task_id))
            complete = False
            continue
        order.append(task_id)
        parsed[task_id] = {"vmId": vm_id, "start": start, "jobFinish": job_finish}
    missing = sorted(expected_ids - set(parsed))
    extra = sorted(set(parsed) - expected_ids)
    if missing or extra:
        complete = False
        _issue(issues, side + "-task-coverage", label,
               "task IDs differ from DAX; missing=" + str(missing) + ", extra=" + str(extra))
    violations = []
    for child_id, parent_ids in sorted(parents.items()):
        if child_id not in parsed:
            continue
        for parent_id in parent_ids:
            if parent_id not in parsed:
                continue
            child_start = parsed[child_id]["start"]
            parent_finish = parsed[parent_id]["jobFinish"]
            if child_start + EPSILON < parent_finish:
                violation = {"parentTaskId": parent_id, "childTaskId": child_id,
                             "parentJobFinishSeconds": parent_finish, "childStartSeconds": child_start}
                violations.append(violation)
                _issue(issues, side + "-dependency-violation", label,
                       "child " + str(child_id) + " starts before parent " + str(parent_id) + " Job finishes")
    makespan = max((task["jobFinish"] for task in parsed.values()), default=None)
    return {"byId": parsed, "order": order, "complete": complete, "makespan": makespan,
            "dependencyViolations": violations}


def inspect_case(case, base, scheduler, observations_path, issues):
    label = base + "-" + scheduler + "-s7"
    if not isinstance(case, dict):
        _issue(issues, "case-schema", label, "case must be an object")
        return None
    try:
        if case.get("label") != label:
            raise CheckError("unexpected case label")
        if case.get("scheduler") != scheduler:
            raise CheckError("scheduler does not match the registered case")
        if _integer(case.get("vmCount"), "vmCount") != 4 or _integer(case.get("speedRatio"), "speedRatio") != 2:
            raise CheckError("the registered platform is four VMs with speed ratio two")
        workflow = resolve_workflow(case, base, Path(observations_path))
        parents = parse_dax(workflow)
        expected_ids = set(parents)
        if _integer(case.get("logicalTasks"), "logicalTasks") != len(expected_ids):
            raise CheckError("logicalTasks does not equal the DAX job count")
    except CheckError as error:
        _issue(issues, "case-contract", label, str(error))
        return None

    current = inspect_side(case.get("tasks"), expected_ids, parents, False, label, issues)
    upstream = inspect_side(case.get("upstreamTasks"), expected_ids, parents, True, label, issues)
    current_completed = case.get("completed") is True
    if not current_completed:
        _issue(issues, "current-completion-flag", label, "completed must be true")
    current["complete"] = current["complete"] and current_completed

    current_mapping = {task_id: task["vmId"] for task_id, task in current["byId"].items()}
    upstream_mapping = {task_id: task["vmId"] + 1 for task_id, task in upstream["byId"].items()}
    mismatch_ids = [task_id for task_id in sorted(expected_ids)
                    if current_mapping.get(task_id) != upstream_mapping.get(task_id)]
    matched = len(expected_ids) - len(mismatch_ids)
    mapping_matches = not mismatch_ids and set(current_mapping) == expected_ids and set(upstream_mapping) == expected_ids
    current_makespan = current["makespan"]
    upstream_makespan = upstream["makespan"]
    delta = None if current_makespan is None or upstream_makespan is None else current_makespan - upstream_makespan
    return {
        "label": label,
        "scheduler": scheduler,
        "workflow": base + ".dax" if base in SHAPES else REAL_WORKFLOWS[base],
        "workflowSha256": sha256(workflow),
        "logicalTasks": len(expected_ids),
        "currentMakespanSeconds": current_makespan,
        "upstreamMakespanSeconds": upstream_makespan,
        "makespanDeltaSeconds": delta,
        "mappingMatches": mapping_matches,
        "mappingMatchedTasks": matched,
        "mappingMismatchTaskIds": mismatch_ids,
        "taskOrderMatches": current["order"] == upstream["order"],
        "currentAllTasksComplete": current["complete"],
        "upstreamAllTasksComplete": upstream["complete"],
        "currentDependencyViolationCount": len(current["dependencyViolations"]),
        "upstreamDependencyViolationCount": len(upstream["dependencyViolations"]),
    }


def inspect_observations(observations_path):
    observations_path = Path(observations_path).resolve()
    document = read_json(observations_path)
    if not isinstance(document, list):
        raise CheckError("top-level observations must be an array")
    by_label = {}
    issues = []
    for index, case in enumerate(document):
        if not isinstance(case, dict) or not isinstance(case.get("label"), str):
            _issue(issues, "case-schema", "case[" + str(index) + "]", "missing string label")
            continue
        label = case["label"]
        if label in by_label:
            _issue(issues, "duplicate-case", label, "duplicate case label")
            continue
        by_label[label] = case
    expected = set(EXPECTED_LABELS)
    actual = set(by_label)
    if actual != expected:
        _issue(issues, "case-matrix", "study",
               "registered 12-case matrix mismatch; missing=" + str(sorted(expected - actual))
               + ", extra=" + str(sorted(actual - expected)))
    rows = []
    for base in CASE_BASES:
        for scheduler in SCHEDULERS:
            label = base + "-" + scheduler + "-s7"
            if label not in by_label:
                continue
            row = inspect_case(by_label[label], base, scheduler, observations_path, issues)
            if row is not None:
                rows.append(row)
    complete_cases = sum(row["currentAllTasksComplete"] and row["upstreamAllTasksComplete"] for row in rows)
    lock = read_json(LOCK_PATH)
    return {
        "schema": "fgcs-differential-comparison-v1",
        "status": "PASS" if not issues else "FAIL",
        "scope": "OBSERVATION_SCHEMA_TASK_COMPLETION_TIMING_DOMAINS_AND_DAX_DEPENDENCIES",
        "interpretation": "PASS is not algorithmic equivalence or bug proof. Makespan, normalized mapping, and task-order differences are descriptive observations only.",
        "observationsSha256": sha256(observations_path),
        "upstreamLock": lock,
        "summary": {
            "registeredCaseCount": len(EXPECTED_LABELS),
            "comparedCaseCount": len(rows),
            "completeOnBothSidesCaseCount": complete_cases,
            "mappingMatchCaseCount": sum(row["mappingMatches"] for row in rows),
            "taskOrderMatchCaseCount": sum(row["taskOrderMatches"] for row in rows),
            "makespanEqualWithin1e9CaseCount": sum(
                row["makespanDeltaSeconds"] is not None and abs(row["makespanDeltaSeconds"]) <= EQUALITY_TOLERANCE
                for row in rows),
            "currentDependencyViolationCount": sum(row["currentDependencyViolationCount"] for row in rows),
            "upstreamDependencyViolationCount": sum(row["upstreamDependencyViolationCount"] for row in rows),
            "issueCount": len(issues),
        },
        "cases": rows,
        "issues": issues,
    }


def _seconds(value):
    if value is None:
        return "n/a"
    # The table has six decimal places; do not display a negative zero for floating residue.
    if abs(value) < Decimal("0.0000005"):
        value = Decimal(0)
    return format(value, ".6f")


def render_markdown(report):
    lines = [
        "| Case | Current makespan (s) | Upstream makespan (s) | Current - upstream (s) | Mapping matches after +1 | Task order matches | Complete C/U | Dependency violations C/U |",
        "|---|---:|---:|---:|---:|:---:|:---:|---:|",
    ]
    for row in report["cases"]:
        lines.append("| {} | {} | {} | {} | {}/{} {} | {} | {}/{} | {}/{} |".format(
            row["label"], _seconds(row["currentMakespanSeconds"]), _seconds(row["upstreamMakespanSeconds"]),
            _seconds(row["makespanDeltaSeconds"]), row["mappingMatchedTasks"], row["logicalTasks"],
            "yes" if row["mappingMatches"] else "no", "yes" if row["taskOrderMatches"] else "no",
            "yes" if row["currentAllTasksComplete"] else "no",
            "yes" if row["upstreamAllTasksComplete"] else "no",
            row["currentDependencyViolationCount"], row["upstreamDependencyViolationCount"]))
    return "\n".join(lines)


def _verify_archive(path, spec):
    actual_size = path.stat().st_size
    actual_hash = sha256(path)
    if actual_size != spec["sizeBytes"] or actual_hash != spec["sha256"]:
        raise CheckError("upstream archive identity mismatch for " + str(path)
                         + "; expected " + spec["sha256"] + " and " + str(spec["sizeBytes"])
                         + " bytes, got " + actual_hash + " and " + str(actual_size) + " bytes")


def _download_archive(work, lock):
    spec = lock["reproductionArchive"]
    archive = work / "upstream.zip"
    if archive.exists():
        _verify_archive(archive, spec)
        return archive
    temporary = work / "upstream.zip.part"
    request = urllib.request.Request(spec["url"], headers={"User-Agent": "WorkflowSim-FGCS-differential/1"})
    with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    _verify_archive(temporary, spec)
    os.replace(str(temporary), str(archive))
    return archive


def _extract_upstream(archive, work, commit):
    extraction = work / "archive"
    prefix = "WorkflowSim-1.0-" + commit
    source = extraction / prefix
    if source.is_dir():
        return source
    with zipfile.ZipFile(str(archive)) as zipped:
        for member in zipped.infolist():
            parts = Path(member.filename).parts
            if not member.filename.startswith(prefix + "/") or ".." in parts or Path(member.filename).is_absolute():
                raise CheckError("unsafe or unexpected upstream archive member: " + member.filename)
        zipped.extractall(str(extraction))
    if not source.is_dir():
        raise CheckError("pinned archive did not contain " + prefix)
    return source


def _run(command, cwd=ROOT, capture=False):
    return subprocess.run(command, cwd=str(cwd), check=True, text=True,
                          stdout=subprocess.PIPE if capture else None)


def reproduce(work):
    """Download, compile, execute, and merge observations in an ignored work directory."""
    if work.exists() and any(work.iterdir()):
        raise CheckError("reproduction requires a new or empty work directory: " + str(work))
    work.mkdir(parents=True, exist_ok=True)
    lock = read_json(LOCK_PATH)
    supplied = os.environ.get("FGCS_UPSTREAM_ARCHIVE")
    if supplied:
        source = rooted(Path(supplied))
        if not source.is_file():
            raise CheckError("supplied upstream archive does not exist: " + str(source))
        archive = work / "upstream.zip"
        shutil.copyfile(str(source), str(archive))
        _verify_archive(archive, lock["reproductionArchive"])
    else:
        archive = _download_archive(work, lock)
    upstream = _extract_upstream(archive, work, lock["commit"])
    classes = work / "classes"
    classes.mkdir(exist_ok=True)
    adapter = work / "UpstreamRunner.java"
    shutil.copyfile(str(ADAPTER_PATH), str(adapter))
    sources = sorted((upstream / "sources").rglob("*.java")) + [adapter]
    classpath = str(upstream / "lib" / "*")
    _run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes)]
         + [str(source) for source in sources])

    current_path = work / "current-observations.json"
    exec_args = '"' + str(current_path).replace('"', '\\"') + '"'
    _run(["mvn", "-q", "-pl", ":workflowsim-workbench", "-am", "compile", "exec:java",
          "-Dexec.mainClass=" + EXPORTER, "-Dexec.args=" + exec_args])
    current = read_json(current_path)
    if not isinstance(current, list):
        raise CheckError("current exporter did not produce an array")
    current_by_label = {case.get("label"): case for case in current if isinstance(case, dict)}
    missing = set(EXPECTED_LABELS) - set(current_by_label)
    if missing:
        raise CheckError("current exporter is missing cases: " + str(sorted(missing)))
    runtime_classpath = os.pathsep.join((str(classes), classpath))
    merged = []
    for label in EXPECTED_LABELS:
        case = copy.deepcopy(current_by_label[label])
        scheduler = case["scheduler"]
        completed = _run(["java", "-cp", runtime_classpath, "fgcs.upstream.UpstreamRunner",
                          str(rooted(case["workflow"])), scheduler, "4", "2"], capture=True)
        case["upstreamTasks"] = decode_json(completed.stdout)
        merged.append(case)
    observations = work / "upstream-observations.json"
    write_json(observations, merged)
    return observations


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--observations", type=Path, default=DEFAULT_OBSERVATIONS,
                        help="paired current/upstream observation JSON (default: retained paper/tmp file)")
    parser.add_argument("--output", type=Path, help="write the deterministic comparison report as JSON")
    parser.add_argument("--reproduce", action="store_true",
                        help="explicitly download/compile upstream and export the current side before comparing")
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK,
                        help="generated reproduction directory; used only with --reproduce")
    parser.add_argument("--no-table", action="store_true", help="suppress the Markdown result table")
    args = parser.parse_args(argv)
    try:
        if args.reproduce:
            observations = reproduce(rooted(args.work_dir))
            output = rooted(args.output) if args.output else rooted(args.work_dir) / "comparison.json"
        else:
            observations = rooted(args.observations)
            output = rooted(args.output) if args.output else None
        report = inspect_observations(observations)
        if output:
            write_json(output, report)
        if not args.no_table:
            print(render_markdown(report))
        print("STATUS={} CASES={} ISSUES={} OBSERVATIONS_SHA256={}".format(
            report["status"], report["summary"]["comparedCaseCount"], report["summary"]["issueCount"],
            report["observationsSha256"]))
        for issue in report["issues"]:
            print("ISSUE {} {}: {}".format(issue["code"], issue["label"], issue["detail"]), file=sys.stderr)
        return 0 if report["status"] == "PASS" else 1
    except (CheckError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
