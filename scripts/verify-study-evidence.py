#!/usr/bin/env python3

from __future__ import annotations

import argparse
import collections
import math
import sys
from fractions import Fraction
from decimal import Decimal
from pathlib import Path

from _study_audit import (Audit, CheckError, EXECUTION_SEMANTICS, HISTORICAL_EXECUTION_SEMANTICS, LONG_MAX, RUN_METRICS,
                          contained_file, decode_json, digest, exact_equal, integer, java_round,
                          mean, needed, number, ordered_sum, parse_workflow, percentile,
                          protect_output, read_json, resolve_input, union_length, write_report)

SUCCESS, FAILED, COMPUTE, STAGE_IN = 4, 5, 2, 1

REVISION_CONTRACT = "F-006/F-007"
PRE_MODELS = {"PRE_EXECUTION_TRANSFER_DELAY_V1", "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1",
              "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1"}

def tolerant_wallclock_nanos(events, event_type, attribute):

    total = 0
    for event in events:
        if event["type"] != event_type:
            continue
        value = events_attribute(event, attribute)

        if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
            continue
        total += int(value)
    return total

def events_attribute(event, attribute):
    attrs = event.get("attributes")
    return attrs.get(attribute) if isinstance(attrs, dict) else None

CS_SCOPE = "SHARED_STORAGE_NO_CLUSTERING_NO_FAILURE_NO_OVERHEAD_SPACE_SHARED"
CS_OUTSIDE = "UNAVAILABLE_OUTSIDE_CONTROLLED_SHARED_STORAGE_SCOPE"

def task_window_verdict(failed, start, finish, planned_seconds, tol):
    """Checks one Task attempt window against its planned compute seconds.

    FGCS-R5 (2026-11-24): mirrors WorkflowDatacenter. A successful attempt computes exactly its planned
    seconds. A failure ends the attempt at the first failure arrival in the half-open compute window
    [start, start + planned), so a failed window is the executed portion: non-negative and strictly
    shorter than planned. Returns (ok, seconds the envelope must cover).
    """
    executed = finish - start
    if not failed:
        return abs(executed - planned_seconds) <= tol, planned_seconds
    ok = executed >= -tol and executed < planned_seconds - tol
    return ok, min(planned_seconds, max(0.0, executed))

def critical_path_reference(config, platform, raw_tasks):

    import math

    def unavailable(scope):
        return {"available": False, "scope": scope, "lowerBound": None}

    movement = config.get("dataMovementModel")
    movement_kind = movement.get("kind") if isinstance(movement, dict) else movement
    if movement_kind not in ("LEGACY_WORKFLOWSIM_V1", "FIXED_ENDPOINT_NO_CONTENTION_V1"):
        return unavailable(CS_OUTSIDE)
    clustering = config.get("clustering")
    if not isinstance(clustering, dict) or clustering.get("method") != "NONE":
        return unavailable(CS_OUTSIDE)
    failure = config.get("failureModel")
    if isinstance(failure, dict) and (failure.get("generatorMode") not in (None, "FAILURE_NONE")):
        return unavailable(CS_OUTSIDE)
    if str(config.get("fileSystem", "")).upper() != "SHARED":
        return unavailable(CS_OUTSIDE)
    overhead = config.get("overheadModel")
    if not isinstance(overhead, dict):
        return unavailable(CS_OUTSIDE)
    if (overhead.get("workflowEngineDelayInterval") not in (None, 0)
            or overhead.get("bandwidth") not in (None, 0.0)
            or bool(overhead.get("workflowEngineDelays"))
            or bool(overhead.get("queueDelays"))
            or bool(overhead.get("postDelays"))
            or bool(overhead.get("clusteringDelays"))):
        return unavailable(CS_OUTSIDE)
    storage = platform.get("storage")
    transfer_rate = storage.get("maxTransferRateMbPerSecond") if isinstance(storage, dict) else None
    if not transfer_rate or number(transfer_rate, "storage rate") <= 0:
        return unavailable(CS_OUTSIDE)
    vms = platform.get("vms")
    if not isinstance(vms, list) or not vms:
        return unavailable(CS_OUTSIDE)
    for vm in vms:
        if vm.get("schedulerMode") != "SPACE_SHARED" or number(vm.get("mips"), "vm mips") <= 0.0:
            return unavailable(CS_OUTSIDE)
    if not raw_tasks:
        return unavailable("UNAVAILABLE_NO_SOURCE_TASKS")

    vms = sorted(vms, key=lambda v: integer(v.get("id"), "vm id"))
    fastest_mips = max(number(v.get("mips"), "vm mips") for v in vms)

    min_interval = number(config.get("cloudSimMinEventIntervalSeconds", 0.1), "min interval")

    stage_raw = 110.0 / fastest_mips
    # FGCS-R3 (2026-11-24, A4): the completion safety margin is tick / 10 (SimulationTiming).
    stage_secs = max(stage_raw, min_interval + min_interval / 10.0) + min_interval

    matrix = {}
    matrix_spec = config.get("taskCostMatrix")
    if isinstance(matrix_spec, dict):
        for ent in matrix_spec.get("entries", []):
            matrix[(integer(ent["taskId"], "matrix task"), integer(ent["vmId"], "matrix vm"))] = (
                number(ent["executionSeconds"], "matrix seconds"))

    finish_times = {}
    remaining = {tid: len(task["parents"]) for tid, task in raw_tasks.items()}
    ready = [tid for tid in sorted(raw_tasks) if remaining[tid] == 0]
    lower_bound = 0.0
    processed = 0
    while ready:
        tid = ready.pop(0)
        task = raw_tasks[tid]
        ready_time = stage_secs if not task["parents"] else max(
            finish_times[parent] for parent in task["parents"])

        external_seconds = 0.0
        for name, kind, size in task["files"]:
            if kind != "input":
                continue
            if any(n2 == name and k2 == "output" for (n2, k2, _) in task["files"]):
                continue
            external_seconds += number(size, "file size") / (1_000_000.0 * number(transfer_rate, "rate"))
        if not math.isfinite(external_seconds) or external_seconds < 0.0:
            return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
        best = None
        unsupported = False
        for vm in vms:
            vm_id = integer(vm.get("id"), "vm id")
            mips = number(vm.get("mips"), "vm mips")

            if matrix:
                key = (tid, vm_id)
                if key not in matrix:
                    unsupported = True
                    break
                compute_mi = java_round(matrix[key] * mips)
                if compute_mi <= 0:
                    unsupported = True
                    break
            else:
                compute_mi = task["lengthMi"]
                if compute_mi <= 0:
                    return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")

            transfer_mi = int(mips * external_seconds)
            envelope_mi = compute_mi + max(transfer_mi, 0)
            candidate = envelope_mi / mips
            if not math.isfinite(candidate) or candidate <= 0.0:
                return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
            if best is None or candidate < best:
                best = candidate
        if unsupported:
            return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
        if best is None:
            return unavailable("UNAVAILABLE_NO_COMPATIBLE_VM")
        finish_time = ready_time + best
        if not math.isfinite(finish_time) or finish_time < 0.0:
            return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
        finish_times[tid] = finish_time
        lower_bound = max(lower_bound, finish_time)
        processed += 1
        for child in sorted(task["children"]):
            remaining[child] -= 1
            if remaining[child] == 0:
                ready.append(child)
    if processed != len(raw_tasks):
        return unavailable("UNAVAILABLE_CYCLIC_SOURCE_TASK_GRAPH")
    return {"available": True, "scope": CS_SCOPE, "lowerBound": lower_bound}

def inspect_manifest(path, audit, historical=False, input_roots=(), cache=None):
    path = Path(path).resolve()
    before_checks, before_issues = audit.checks, audit.issue_count
    prefix = str(path)
    cache = cache if cache is not None else {}
    coverage = {"rawInputs": [], "networkReplay": "NOT_PERFORMED_NO_COMPLETE_PER_FLOW_LEDGER",
                "scope": "STANDARD_RUNNER_NONE_CLUSTERING_SPACE_SHARED_ONE_JOB_PER_VM"}
    derived = {}
    try:
        doc = read_json(path)
        if needed(doc, "schema") != "workflowsim-experiment-manifest-v4":
            raise CheckError("only manifest v4 is supported; old schemas are not silently upgraded")
        config, platform = needed(doc, "configuration"), needed(doc, "platform")
        result, metrics = needed(doc, "result"), needed(doc, "metrics")
        marker = config.get("executionSemantics")
        old = marker is None
        # FGCS-R3 (2026-11-24): a V2 declaration is a recognized historical identity; like an absent marker it
        # is accepted only under --historical and is never relabelled as the current V3 execution.
        historical_marker = marker in HISTORICAL_EXECUTION_SEMANTICS
        audit.ensure(not (old or historical_marker) or historical, "historical-mode-required", prefix, marker)
        audit.ensure(old or historical_marker or marker == EXECUTION_SEMANTICS, "execution-model-identity", prefix,
                     marker, EXECUTION_SEMANTICS)
        coverage["executionMode"] = "HISTORICAL_DECLARATION" if old else marker
        if needed(needed(config, "clustering"), "method") != "NONE":
            raise CheckError("clustering is outside the supported independent raw-task/job projection")
        vms = {integer(needed(v, "id"), "vm id", True): v for v in needed(platform, "vms")}
        audit.ensure(len(vms) == len(platform["vms"]), "unique-vm-ids", prefix)
        audit.ensure(len(vms) == integer(needed(config, "vmCount"), "vmCount", True), "vm-count", prefix)
        for vm in vms.values():
            if needed(vm, "schedulerMode") != "SPACE_SHARED":
                raise CheckError("only standard SPACE_SHARED evidence is supported")
            number(needed(vm, "mips"), "VM MIPS", positive=True)
        seed = integer(needed(config, "rootSeed"), "rootSeed")
        audit.ensure(-(1 << 63) <= seed <= LONG_MAX, "signed-long-root-seed", prefix, seed)
        end = number(needed(result, "simulationEndSeconds"), "simulation end", nonnegative=True)
        def tolerance(*timestamps):

            magnitude = max((abs(float(value)) for value in timestamps), default=0.0)
            return audit.abs_tol + 4.0 * math.ulp(magnitude)

        def time_equal(actual, expected, *timestamps):
            try:
                a = number(actual, "time observation", nonnegative=True)
                b = number(expected, "expected time", nonnegative=True)
            except CheckError:
                return False
            return abs(a - b) <= tolerance(*(timestamps or (a, b)))

        def time_metric(record, key, expected, location, *timestamps):
            if key not in record:
                audit.problem("missing-time-observation", location + "/" + key)
                return
            audit.ensure(time_equal(record[key], expected, *timestamps), "time-observation-mismatch",
                         location + "/" + key, record[key], expected)

        refs = {}
        for item in needed(doc, "artifacts"):
            role = needed(item, "role")
            if role in refs:
                raise CheckError("duplicate artifact role: " + role)
            referenced = contained_file(path.parent, needed(item, "path"))
            refs[role] = referenced
            sha, size = digest(referenced)
            audit.ensure(sha == needed(item, "sha256"), "artifact-sha256", str(referenced), sha, item["sha256"])
            audit.ensure(size == integer(needed(item, "sizeBytes"), "artifact size", True), "artifact-size", str(referenced), size, item["sizeBytes"])
        if "metrics" not in refs or "events" not in refs:
            raise CheckError("bundle must contain metrics and events sidecars")
        side = read_json(refs["metrics"])
        audit.ensure(needed(side, "schema") == "workflowsim-simulation-metrics-v2", "metrics-schema", prefix)
        audit.ensure(exact_equal(needed(side, "metrics"), metrics), "manifest-sidecar-metrics", prefix)

        events, by_type = [], collections.defaultdict(dict)
        executed_job_ids, terminal_events = set(), []
        previous = 0.0
        with refs["events"].open(encoding="utf-8") as stream:
            for sequence, line in enumerate(stream):
                if not line.strip():
                    raise CheckError("blank JSONL event line")
                event = decode_json(line)
                audit.ensure(integer(needed(event, "sequence"), "event sequence", True) == sequence,
                             "event-sequence", prefix + ":event:" + str(sequence), event.get("sequence"), sequence)
                time = number(needed(event, "simulationTime"), "event time", nonnegative=True)
                audit.ensure(time + tolerance(time, previous) >= previous and time <= end + tolerance(time, end),
                             "event-clock-order", prefix + ":event:" + str(sequence), time, {"previous": previous, "end": end})
                previous = time
                typ = needed(event, "type")
                if not isinstance(typ, str) or not isinstance(needed(event, "attributes"), dict):
                    raise CheckError("event type/attributes have invalid JSON types")
                event["taskIds"] = [integer(tid, "event task id", True) for tid in needed(event, "taskIds")]
                if typ in ("JOB_READY", "SCHEDULING_DECISION", "JOB_RETURNED", "DATA_STAGE_IN_MODELED",
                           "STAGE_IN_JOB_CREATED", "JOB_EXECUTION_STARTED"):
                    jid = integer(needed(event, "jobId"), "event jobId", True)
                    audit.ensure(set(event) >= {"attributes", "taskIds"}, "event-required-members", prefix)
                    audit.ensure(jid not in by_type[typ], "duplicate-" + typ, prefix, jid)
                    by_type[typ][jid] = event
                if typ == "TASK_EXECUTION_MODELED":
                    executed_job_ids.add(integer(needed(event, "jobId"), "event jobId", True))
                if typ == "SIMULATION_TERMINATED":
                    terminal_events.append(event)
                events.append(event)
        event_summary = needed(doc, "events")
        audit.ensure(len(events) == integer(needed(event_summary, "eventCount"), "event count", True),
                     "event-count", prefix, len(events), event_summary.get("eventCount"))

        if events:
            for key, bound in (("firstSequence", events[0]["sequence"]), ("lastSequence", events[-1]["sequence"])):
                if key in event_summary:
                    audit.ensure(integer(needed(event_summary, key), "event " + key, True) == bound,
                                 "event-" + key, prefix, bound, event_summary.get(key))

        declared_contract = event_summary.get("revisionContract")
        contract_declared = declared_contract is not None
        if contract_declared:
            audit.ensure(declared_contract == REVISION_CONTRACT, "revision-contract", prefix,
                         REVISION_CONTRACT, declared_contract)
            audit.ensure(len(terminal_events) == 1, "revision-contract-terminal", prefix,
                         1, len(terminal_events))

        if terminal_events:
            audit.ensure(len(terminal_events) == 1, "duplicate-SIMULATION_TERMINATED", prefix, len(terminal_events))
            terminal = terminal_events[0]
            clock = number(needed(terminal, "simulationTime"), "terminal event time", nonnegative=True)
            declared_end = number(needed(needed(terminal, "attributes"), "simulationEndSeconds"),
                                  "terminal simulationEndSeconds", nonnegative=True)
            audit.ensure(clock == end and declared_end == end, "terminal-clock-identity",
                         prefix + ":event:" + str(terminal.get("sequence")), [clock, declared_end], end)
            audit.ensure(events[-1]["type"] == "SIMULATION_TERMINATED", "terminal-event-position", prefix,
                         events[-1]["type"], "SIMULATION_TERMINATED")

        started_jobs = by_type.get("JOB_EXECUTION_STARTED", {})
        for jid, event in started_jobs.items():
            attributes = needed(event, "attributes")
            envelope = number(needed(attributes, "jobEnvelopeStartSeconds"), "job envelope start", nonnegative=True)
            effective = number(needed(attributes, "effectiveDataStageSeconds"), "effective data stage", nonnegative=True)
            number(needed(attributes, "requestedDataStageSeconds"), "requested data stage", nonnegative=True)
            modeled = number(needed(attributes, "modeledJobStartSeconds"), "modeled job start", nonnegative=True)
            audit.ensure(modeled == envelope + effective, "job-start-derivation",
                         prefix + ":event:" + str(event.get("sequence")), modeled, [envelope, effective])
        if contract_declared or started_jobs or terminal_events:

            audit.ensure(executed_job_ids.issubset(set(started_jobs)), "job-start-coverage", prefix,
                         sorted(set(started_jobs)), sorted(executed_job_ids))

        raw_tasks, raw_external = {}, collections.OrderedDict()
        inputs = needed(doc, "inputs")
        declared_paths = needed(config, "workflowPaths")
        audit.ensure(isinstance(inputs, list) and bool(inputs), "nonempty-inputs", prefix)
        audit.ensure(exact_equal(declared_paths, [needed(entry, "path") for entry in inputs]), "ordered-input-paths", prefix)
        arrivals = needed(config, "workflowArrivalSeconds")
        audit.ensure(len(arrivals) == len(inputs), "arrival-count", prefix)
        first_id = 1
        for index, entry in enumerate(inputs):
            try:
                source, tried = resolve_input(doc, path, entry, input_roots)
                stat = source.stat()
                key = (str(source), stat.st_size, stat.st_mtime_ns, first_id, index, len(inputs),
                       str(config.get("runtimeScale")), str(config.get("runtimeReferenceMips")))
                if cache.get(key) is None:
                    fingerprint = digest(source)
                    parsed = parse_workflow(source, first_id, "workflow-" + str(index) if len(inputs) > 1 else None, config)
                    cache[key] = fingerprint, parsed
                (sha, size), (tasks, kind, version, warnings) = cache[key]
                audit.ensure(sha == needed(entry, "sha256") and size == integer(needed(entry, "sizeBytes"), "input size", True),
                             "input-fingerprint", prefix + ":input:" + str(index), [sha, size], [entry.get("sha256"), entry.get("sizeBytes")])
                audit.ensure(kind == needed(entry, "format"), "raw-input-format", prefix, kind, entry.get("format"))
                audit.ensure(version == needed(entry, "declaredVersion"), "raw-input-version", prefix, version, entry.get("declaredVersion"))
                audit.ensure(len(tasks) == integer(needed(entry, "taskCount"), "input task count", True), "raw-input-task-count", prefix)
                arrival = number(arrivals[index], "workflow arrival", nonnegative=True)
                for task in tasks.values():
                    raw_tasks[task["taskId"]] = dict(task, arrival=arrival)
                coverage["rawInputs"].append({"recorded": entry["path"], "resolved": str(source), "sha256": sha,
                                               "taskCount": len(tasks), "format": kind, "normalizationNotes": warnings})
                first_id += len(tasks)
            except (CheckError, OSError, KeyError, ValueError, TypeError) as error:
                audit.problem("raw-input-reparse-unavailable-or-invalid", prefix + ":input:" + str(index), str(error))
                coverage["rawInputs"].append({"recorded": entry.get("path"), "status": "UNAVAILABLE", "reason": str(error)})
                first_id += integer(needed(entry, "taskCount"), "input task count", True)
        parsed_complete = len(raw_tasks) == sum(integer(needed(i, "taskCount"), "input task count", True) for i in inputs)
        all_outputs = {name for task in raw_tasks.values() for name, kind, _ in task["files"] if kind == "output"}
        for task in raw_tasks.values():
            for name, kind, size in task["files"]:
                if kind == "input" and name not in all_outputs:
                    raw_external.setdefault(name, size)
        graph = {integer(needed(n, "taskId"), "graph task id", True): n for n in needed(doc, "workflowGraph")}
        audit.ensure(len(graph) == len(doc["workflowGraph"]), "unique-graph-task-ids", prefix)
        if parsed_complete:
            audit.ensure(set(graph) == set(raw_tasks), "raw-graph-task-ids", prefix)
            for tid, task in raw_tasks.items():
                if tid not in graph:
                    continue
                audit.ensure(set(needed(graph[tid], "parentIds")) == task["parents"]
                             and set(needed(graph[tid], "childIds")) == task["children"], "raw-dag-dependencies", prefix, tid)
            audit.ensure(integer(needed(needed(doc, "workflowProfile"), "taskCount"), "profile task count", True) == len(raw_tasks),
                         "raw-profile-task-count", prefix)
        logical_ids = set(raw_tasks) if parsed_complete else set(graph)

        vm_power = {}
        for vmid, vm in vms.items():
            idle = number(vm.get("idlePowerWatts", 0.0), "VM idlePowerWatts", nonnegative=True)
            peak = number(vm.get("maxPowerWatts", 0.0), "VM maxPowerWatts", nonnegative=True)
            if peak < idle:
                raise CheckError("maxPowerWatts below idlePowerWatts on VM " + str(vmid))
            vm_power[vmid] = (idle, peak)
        vm_tiers = {}
        tier_max_by_vm = {}
        for vmid, vm in vms.items():
            raw_tiers = vm.get("frequencyTiers")
            if raw_tiers is None:
                continue
            if not isinstance(raw_tiers, list) or not raw_tiers:
                raise CheckError("VM frequencyTiers must be a non-empty list when declared (BAT-D3)")
            tiers = []
            for item in raw_tiers:
                if not isinstance(item, dict):
                    raise CheckError("frequencyTiers entries must be objects on VM " + str(vmid))
                tier_mips = number(needed(item, "mips"), "tier mips", positive=True)
                tier_idle = number(needed(item, "idlePowerWatts"), "tier idlePowerWatts", nonnegative=True)
                tier_max = number(needed(item, "maxPowerWatts"), "tier maxPowerWatts", nonnegative=True)
                if tier_max < tier_idle:
                    raise CheckError("tier maxPowerWatts below idlePowerWatts on VM " + str(vmid))
                if tiers and tier_mips >= tiers[-1][0]:
                    raise CheckError(
                        "frequencyTiers must be strictly descending by mips on VM " + str(vmid)
                        + " (BAT-D1 tier-order contract)")
                tiers.append((tier_mips, tier_idle, tier_max))
            if vm_power[vmid][0] > 0.0 or vm_power[vmid][1] > 0.0:
                raise CheckError("VM " + str(vmid)
                                 + " declares BOTH frequencyTiers and plain power fields — one source of"
                                 + " truth required (BAT-D1)")
            vm_tiers[vmid] = tiers
            tier_max_by_vm[vmid] = {t[0]: t[2] for t in tiers}
        # FGCS-R3 (2026-11-24, A5): mirror EnergyAccounting.requireAllOrNonePowered. A fleet in
        # which only part of the VMs declares power would book the rest at 0 J.
        powered_vms = sorted(vmid for vmid, p in vm_power.items() if p[0] > 0.0 or p[1] > 0.0 or vmid in vm_tiers)
        if powered_vms and len(powered_vms) != len(vm_power):
            raise CheckError("energy accounting requires a power model on every VM or on none; powered VMs "
                             + str(powered_vms) + " of " + str(sorted(vm_power)))
        tier_of_job = {}
        tier_intervals_by_vm = {}
        jobs, task_outcomes = needed(result, "jobs"), needed(result, "tasks")
        jobs_by_id, jobs_by_task, vm_intervals = {}, collections.defaultdict(list), collections.defaultdict(list)
        first_success = {}
        computes = []
        for job in jobs:
            jid = integer(needed(job, "jobId"), "jobId", True)
            audit.ensure(jid not in jobs_by_id, "unique-job-id", prefix, jid)
            jobs_by_id[jid] = job
            vmid = integer(needed(job, "vmId"), "job vmId", True)
            if vmid not in vms:
                raise CheckError("job uses undeclared VM: " + str(vmid))
            status = integer(needed(job, "status"), "job status", True)
            cls = integer(needed(job, "classType"), "job class", True)
            audit.ensure(status in (SUCCESS, FAILED) and cls in (COMPUTE, STAGE_IN), "supported-job-outcome", prefix, [jid, status, cls])
            submission, start, finish = [number(needed(job, f), f, nonnegative=True) for f in ("submissionTime", "startTime", "finishTime")]
            audit.ensure(submission <= start + tolerance(submission, start) and start <= finish + tolerance(start, finish)
                         and finish <= end + tolerance(finish, end), "job-interval", prefix, {"jobId": jid, "submission": submission, "start": start, "finish": finish, "end": end})
            vm_intervals[vmid].append((start, finish))

            tiers = vm_tiers.get(vmid)
            raw = job.get("tierMips")
            if tiers is None:
                if raw is not None:
                    raise CheckError("job " + str(jid) + " on non-tiered VM " + str(vmid)
                                     + " carries tierMips: the per-Job tier evidence chain is"
                                     " broken (BAT-D3)")
            else:
                if raw is None:
                    tier_mips = tiers[0][0]
                else:
                    tier_mips = number(raw, "job tierMips", positive=True)
                    if tier_mips not in tier_max_by_vm[vmid]:
                        raise CheckError(
                            "job " + str(jid) + " carries tier mips " + str(raw)
                            + " which matches no declared frequency tier of VM " + str(vmid)
                            + ": the per-Job tier evidence chain is broken (BAT-D2)")
                tier_of_job[jid] = tier_mips
                buckets = tier_intervals_by_vm.setdefault(vmid, {})
                buckets.setdefault(tier_mips, []).append((start, finish))
            for name, expected in (("waitingTime", max(0.0, start - submission)), ("executionTime", max(0.0, finish - start)),
                                   ("responseTime", max(0.0, finish - submission)), ("cpuTime", max(0.0, finish - start))):
                time_metric(job, name, expected, prefix + ":job:" + str(jid), submission, start, finish)
            for typ in ("JOB_READY", "SCHEDULING_DECISION", "JOB_RETURNED"):
                audit.ensure(jid in by_type[typ], "missing-" + typ, prefix, jid)
            if jid in by_type["JOB_READY"] and jid in by_type["SCHEDULING_DECISION"]:
                ready = number(by_type["JOB_READY"][jid]["simulationTime"])
                decision = number(by_type["SCHEDULING_DECISION"][jid]["simulationTime"])
                audit.ensure(ready <= decision + tolerance(ready, decision) and decision <= start + tolerance(decision, start), "ready-decision-start", prefix, jid)
            tids = [integer(tid, "job task id", True) for tid in needed(job, "taskIds")]
            job["taskIds"] = tids
            audit.ensure(len(tids) == integer(needed(job, "taskCount"), "job task count", True) and len(set(tids)) == len(tids), "job-task-list", prefix, jid)
            if cls == COMPUTE:
                computes.append(job)
                audit.ensure(len(tids) == 1 and all(tid in logical_ids for tid in tids), "unclustered-job-source-task", prefix, [jid, tids])
                audit.ensure(jid in by_type["DATA_STAGE_IN_MODELED"], "missing-data-stage-in", prefix, jid)
                for tid in tids:
                    jobs_by_task[tid].append(job)
                    if status == SUCCESS:
                        first_success[tid] = min(finish, first_success.get(tid, math.inf))
            else:
                audit.ensure(not tids, "stage-in-has-no-logical-task", prefix, jid)
                minimum = 110.0 / number(vms[vmid]["mips"])
                allowance = 1.0 / number(vms[vmid]["mips"]) + tolerance(start, finish)
                audit.ensure(finish - start + allowance >= minimum, "stage-in-cpu-lower-bound", prefix, jid, minimum)

        matrix = config.get("taskCostMatrix")
        costs = {}
        if matrix is not None:
            for entry in needed(matrix, "entries"):
                key = (integer(needed(entry, "taskId"), "matrix task id", True), integer(needed(entry, "vmId"), "matrix vm id", True))
                audit.ensure(key not in costs, "duplicate-cost-coordinate", prefix, key)
                costs[key] = number(needed(entry, "executionSeconds"), "matrix seconds", positive=True)
            audit.ensure(set(costs) >= {(tid, vmid) for tid in logical_ids for vmid in vms}, "matrix-complete-coverage", prefix)
        seen_attempts = set()
        failure_cfg = config.get("failureModel")
        crch_attempts = (config.get("schedulingAlgorithm") == "CRCH"
                         or (isinstance(failure_cfg, dict) and failure_cfg.get("clusteringAlgorithm") == "FTCLUSTERING_CRCH"))
        if crch_attempts:
            coverage["crchExecutedWork"] = "DECLARED_NOT_RECOMPUTED_CHECKPOINT_PARAMETERS_ABSENT_FROM_MANIFEST"
        for task in task_outcomes:
            tid, jid = integer(needed(task, "taskId"), "task id", True), integer(needed(task, "jobId"), "task job id", True)
            audit.ensure((tid, jid) not in seen_attempts, "unique-task-attempt", prefix, [tid, jid])
            seen_attempts.add((tid, jid))
            if jid not in jobs_by_id:
                raise CheckError("task outcome has unknown Job")
            job = jobs_by_id[jid]
            vmid = integer(needed(job, "vmId"))
            mips = number(vms[vmid]["mips"], positive=True)
            audit.ensure(tid in job["taskIds"] and integer(needed(task, "vmId")) == vmid,
                         "task-job-vm-identity", prefix, [tid, jid])
            audit.ensure(integer(needed(task, "jobStatus")) == job["status"], "task-job-status", prefix, [tid, jid])
            raw_mi = integer(needed(task, "lengthMi"), "raw task MI", True)
            if tid in raw_tasks:
                audit.ensure(raw_mi == raw_tasks[tid]["lengthMi"], "raw-runtime-mi", prefix, [tid, raw_mi], raw_tasks[tid]["lengthMi"])
            if matrix is not None and (tid, vmid) not in costs:
                audit.problem("missing-effective-cost-coordinate", prefix, [tid, vmid])
                continue
            effective = java_round(costs[(tid, vmid)] * mips) if matrix is not None else raw_mi
            if crch_attempts:
                # FGCS-R5 (2026-11-24): a CRCH attempt executes its work plus ceil(n*gamma*mips) checkpoint MI,
                # and a resumption on the checkpointing VM computes only the work after the latest checkpoint.
                # The checkpoint interval and overhead are not manifest fields, so the declared executed MI is
                # taken as given (bounded by the raw work plus overhead) and the window is checked against it.
                declared = integer(needed(task, "effectiveExecutionLengthMi"), "effective MI", True)
                audit.ensure(0 < declared, "crch-effective-mi-positive", prefix, [tid, declared])
                effective = declared
            audit.ensure(0 < effective <= LONG_MAX // 1_000_000, "representable-effective-mi", prefix, [tid, effective])
            if crch_attempts:
                pass
            elif not old or "effectiveExecutionLengthMi" in task:
                audit.ensure("effectiveExecutionLengthMi" in task, "missing-effective-mi-declaration", prefix, tid)
                if "effectiveExecutionLengthMi" in task:
                    audit.ensure(integer(task["effectiveExecutionLengthMi"], "effective MI", True) == effective, "effective-mi-declaration", prefix, [tid, task["effectiveExecutionLengthMi"]], effective)
            start, finish = number(needed(task, "startTime"), nonnegative=True), number(needed(task, "finishTime"), nonnegative=True)
            jstart, jfinish = number(job["startTime"]), number(job["finishTime"])

            expected_seconds = effective / (tier_of_job[jid] if jid in tier_of_job else mips)
            allowance = 1.0 / mips + tolerance(start, finish, jstart, jfinish)
            audit.ensure(start + tolerance(start, jstart) >= jstart and finish <= jfinish + allowance and finish + tolerance(start, finish) >= start,
                         "task-window-within-job", prefix, {"taskId": tid, "jobId": jid, "start": start, "finish": finish, "jobStart": jstart, "jobFinish": jfinish}, allowance)
            window_ok, expected_seconds = task_window_verdict(job["status"] == FAILED, start, finish, expected_seconds,
                                                             tolerance(start, finish))
            audit.ensure(window_ok, "failed-task-window-within-compute" if job["status"] == FAILED else "effective-task-window",
                         prefix, [tid, finish - start], expected_seconds)
            audit.ensure(jfinish - jstart + allowance >= expected_seconds + max(0.0, start - jstart), "cpu-lower-bound", prefix,
                         {"taskId": tid, "jobId": jid, "actualEnvelope": jfinish - jstart, "allowance": allowance}, expected_seconds + max(0.0, start - jstart))
            if needed(task, "exactJobTiming") is True:
                audit.ensure(time_equal(start, jstart) and time_equal(finish, jfinish), "exact-task-timing-claim", prefix, tid)
        expected_attempts = {(tid, j["jobId"]) for j in computes for tid in j["taskIds"]}
        audit.ensure(seen_attempts == expected_attempts, "task-attempt-coverage", prefix)
        execution_events = [e for e in events if e["type"] == "TASK_EXECUTION_MODELED"]
        event_attempts = collections.Counter()
        for event in execution_events:
            jid = integer(needed(event, "jobId"), "task event job id", True)
            attrs = needed(event, "attributes")
            tids = needed(event, "taskIds")
            for tid in event["taskIds"]:
                event_attempts[(tid, jid)] += 1
                matches = [t for t in task_outcomes if t["taskId"] == tid and t["jobId"] == jid]
                if len(matches) != 1:
                    audit.problem("task-event-outcome-identity", prefix, [tid, jid])
                    continue
                task = matches[0]
                for ek, tk in (("taskStartTime", "startTime"), ("taskFinishTime", "finishTime")):
                    time_metric(attrs, ek, number(task[tk]), prefix + ":task-event:" + str(tid))
                if not old:
                    audit.ensure(integer(needed(attrs, "effectiveExecutionLengthMi"), "event effective MI", True)
                                 == integer(needed(task, "effectiveExecutionLengthMi")), "task-event-effective-mi", prefix, tid)
        audit.ensure(set(event_attempts) == expected_attempts and all(n == 1 for n in event_attempts.values()), "task-event-coverage", prefix)
        for tid, node in graph.items():
            for job in jobs_by_task[tid]:
                for parent in needed(node, "parentIds"):
                    audit.ensure(parent in first_success and first_success[parent] <= number(job["startTime"]) + tolerance(first_success[parent], job["startTime"]),
                                 "dag-parent-before-child", prefix, [parent, tid, job["jobId"]])
                if tid in raw_tasks:
                    audit.ensure(number(job["startTime"]) + tolerance(job["startTime"], raw_tasks[tid]["arrival"]) >= raw_tasks[tid]["arrival"], "workflow-arrival-gate", prefix, tid)

        def field_values(rows, field):
            return [number(needed(row, field), field, nonnegative=True) for row in rows]
        waits, slow, ready_dec, dec_start, success_waits, success_slow = [], [], [], [], [], []
        # FGCS-R3 (2026-11-24): mirror SimulationMetrics' three independent sample sets exactly.
        # Waiting/bounded slowdown need only JOB_READY with start >= ready; ready->decision needs
        # both events with decision >= ready; decision->start needs the decision with
        # start >= decision. Missing or out-of-order observations are excluded, never clamped to
        # a zero sample (METRICS_CARD / CONTRACTS "Waiting, Slowdown, and Missing Observations").
        for job in computes:
            jid = job["jobId"]
            ready = number(by_type["JOB_READY"][jid]["simulationTime"]) if jid in by_type["JOB_READY"] else None
            decision = (number(by_type["SCHEDULING_DECISION"][jid]["simulationTime"])
                        if jid in by_type["SCHEDULING_DECISION"] else None)
            start, finish = number(job["startTime"]), number(job["finishTime"])
            if ready is not None and decision is not None and decision >= ready:
                ready_dec.append(decision - ready)
            if decision is not None and start >= decision:
                dec_start.append(start - decision)
            if ready is None or not start >= ready:
                continue
            waiting = start - ready
            execution = max(0.0, finish - start)
            slowdown = max(1.0, (waiting + execution) / max(10.0, execution))
            waits.append(waiting); slow.append(slowdown)
            if job["status"] == SUCCESS:
                success_waits.append(waiting); success_slow.append(slowdown)
        data = list(by_type["DATA_STAGE_IN_MODELED"].values())
        demand_values, transfer_seconds, file_counts = [], [], []
        for event in data:
            attrs = needed(event, "attributes")
            demand = number(needed(attrs, "requiredFileBytes"), "required bytes", nonnegative=True)
            seconds = number(needed(attrs, "modeledTransferSeconds"), "transfer seconds", nonnegative=True)
            count = integer(needed(attrs, "modeledTransferFileCount"), "input file count", True)
            demand_values.append(demand); transfer_seconds.append(seconds); file_counts.append(count)
            job = jobs_by_id[integer(event["jobId"])]
            if parsed_complete and len(job["taskIds"]) == 1:
                files = raw_tasks[job["taskIds"][0]]["files"]
                outputs = {n for n, k, _ in files if k == "output"}
                required = [size for n, k, size in files if k == "input" and n not in outputs]
                expected_demand = ordered_sum(required)
                audit.ensure(math.isclose(demand, expected_demand, rel_tol=0.0, abs_tol=1e-8)
                             and (expected_demand <= 0.0 or demand > 0.0), "raw-file-demand", prefix,
                             {"jobId": job["jobId"], "bytes": demand}, expected_demand)
                audit.ensure(count == len(required), "raw-demand-file-count", prefix, count, len(required))
        if parsed_complete:
            stage_events = list(by_type["STAGE_IN_JOB_CREATED"].values())
            audit.ensure(len(stage_events) == 1, "single-standard-stage-in", prefix, len(stage_events), 1)
            for event in stage_events:
                attrs = needed(event, "attributes")
                audit.metric(attrs, "inputFileCount", len(raw_external), prefix + ":stage-in-created")
                actual_bytes = number(needed(attrs, "inputBytes"), "stage-in input bytes", nonnegative=True)
                expected_bytes = ordered_sum(raw_external.values())
                audit.ensure(math.isclose(actual_bytes, expected_bytes, rel_tol=0.0, abs_tol=1e-8)
                             and (expected_bytes <= 0.0 or actual_bytes > 0.0), "raw-stage-in-bytes", prefix, actual_bytes, expected_bytes)
        total_costs = collections.defaultdict(list)
        for job in jobs:
            vm = vms[job["vmId"]]
            prices = needed(platform, "costs") if needed(config, "costModel") == "DATACENTER" else needed(vm, "costs")
            cpu = number(needed(prices, "cpuPerSecond"), "CPU price", nonnegative=True) * number(job["cpuTime"], nonnegative=True)
            declared_bytes = number(needed(job, "modeledDeclaredFileBytes"), "declared bytes", nonnegative=True)
            if parsed_complete:
                expected_bytes = ordered_sum(raw_external.values()) if job["classType"] == STAGE_IN else ordered_sum(
                    size for tid in job["taskIds"] for _, _, size in raw_tasks[tid]["files"])
                audit.ensure(math.isclose(declared_bytes, expected_bytes, rel_tol=0.0, abs_tol=1e-8), "raw-billable-file-bytes", prefix,
                             {"jobId": job["jobId"], "actual": declared_bytes}, expected_bytes)
            bandwidth_cost = number(needed(prices, "bandwidth"), "bandwidth price", nonnegative=True) * declared_bytes / 1_000_000.0
            for key, expected in (("modeledCpuEnvelopeCost", cpu), ("modeledDeclaredFileBandwidthCost", bandwidth_cost),
                                  ("modeledProcessingCost", cpu + bandwidth_cost)):
                audit.metric(job, key, expected, prefix + ":job:" + str(job["jobId"]))
                total_costs[key].append(expected)
            total_costs["modeledDeclaredFileBytes"].append(declared_bytes)
        retry_ids = {integer(e["jobId"]) for e in events if e["type"] == "RETRY_JOB_CREATED"}
        retry_jobs = [j for j in computes if j["jobId"] in retry_ids]
        failed_jobs = [j for j in computes if j["status"] == FAILED]
        complete = bool(logical_ids) and logical_ids <= set(first_success)
        completion = max(first_success.values()) if complete else None
        runtime = [max(0.0, number(j["finishTime"]) - number(j["startTime"])) for j in computes]
        vm_slows = [(number(j["finishTime"]) - number(j["submissionTime"])) / duration for j, duration in zip(computes, runtime) if duration > 0.001]
        derived.update(makespanSeconds=end, jobOutcomeCount=len(jobs), computeJobOutcomeCount=len(computes),
                       stageInJobOutcomeCount=len(jobs) - len(computes), successfulJobOutcomeCount=sum(j["status"] == SUCCESS for j in jobs),
                       failedJobOutcomeCount=sum(j["status"] == FAILED for j in jobs), successfulComputeJobOutcomeCount=sum(j["status"] == SUCCESS for j in computes),
                       failedComputeJobOutcomeCount=len(failed_jobs), logicalTaskCount=len(logical_ids),
                       successfullyCompletedLogicalTaskCount=len(first_success), logicalTasksNotYetSuccessfullyCompletedCount=len(logical_ids - set(first_success)),
                       allLogicalTasksCompletedSuccessfully=complete, logicalTaskCompletionSeconds=completion,
                       logicalTaskCompletionStatus="COMPLETED_SUCCESSFULLY" if complete else ("INCOMPLETE_LOGICAL_TASKS" if logical_ids else "NO_LOGICAL_TASKS"),
                       terminalLifecycleTailSeconds=max(0.0, end - completion) if complete else None,
                       initialComputeJobOutcomeCount=len(computes) - len(retry_jobs), retryJobCreatedCount=len(retry_ids),
                       completedRetryComputeJobOutcomeCount=len(retry_jobs), logicalTaskAttemptCount=sum(len(j["taskIds"]) for j in computes),
                       retriedLogicalTaskCount=len({tid for j in retry_jobs for tid in j["taskIds"]}),
                       failedComputeAttemptEnvelopeSeconds=math.fsum(number(j["finishTime"]) - number(j["startTime"]) for j in failed_jobs),
                       failedComputeAttemptModeledProcessingCost=math.fsum(number(j["modeledProcessingCost"]) for j in failed_jobs),
                       retryComputeAttemptModeledProcessingCost=math.fsum(number(j["modeledProcessingCost"]) for j in retry_jobs),
                       meanComputeJobRunTimeSeconds=mean(runtime), meanComputeTotalWaitingTimeSeconds=mean(waits), meanComputeTrueSlowdown=mean(slow),
                       totalWaitingTimeObservationCount=len(waits), trueSlowdownObservationCount=len(slow),
                       meanComputeReadyToDecisionDelaySeconds=mean(ready_dec), meanComputeDecisionToStartDelaySeconds=mean(dec_start),
                       readyToDecisionObservationCount=len(ready_dec), decisionToStartObservationCount=len(dec_start),
                       meanJobVmQueueWaitingTimeSeconds=mean([max(0.0, number(j["startTime"]) - number(j["submissionTime"])) for j in computes]),
                       meanJobResponseTimeSeconds=mean([max(0.0, number(j["finishTime"]) - number(j["submissionTime"])) for j in computes]),
                       meanJobVmLevelSlowdown=mean(vm_slows), vmLevelSlowdownObservationCount=len(vm_slows),
                       computeJobOutcomeThroughputPerSecond=len(computes) / end if end > 0 else 0.0,
                       successfulComputeJobOutcomeRate=sum(j["status"] == SUCCESS for j in computes) / len(computes) if computes else 0.0,
                       successfulLogicalTaskCompletionRate=len(first_success) / len(logical_ids) if logical_ids else 0.0,
                       retryAmplificationRatio=sum(len(j["taskIds"]) for j in computes) / len(logical_ids) if logical_ids else 0.0,
                       successOnlyMeanComputeTotalWaitingTimeSeconds=mean(success_waits), successOnlyMeanComputeTrueSlowdown=mean(success_slow),
                       successOnlyWaitingObservationCount=len(success_waits),
                       dataStageInModelObservationCount=len(data), modeledDataTransferFileCount=sum(file_counts),
                       totalModeledRequiredInputBytes=ordered_sum(demand_values), totalModeledDataTransferSeconds=ordered_sum(transfer_seconds),
                       meanModeledDataTransferSeconds=mean(transfer_seconds), schedulingCycleCount=sum(e["type"] == "SCHEDULING_CYCLE" for e in events),
                       explicitPlannerDecisionObservationCount=sum(e["type"] == "PLANNING_COMPLETED" and e["attributes"].get("explicitPlannerDecision") is True for e in events),
                       totalSchedulingDecisionWallClockNanos=tolerant_wallclock_nanos(events, "SCHEDULING_CYCLE", "decisionElapsedNanos"),
                       totalPlanningDecisionWallClockNanos=tolerant_wallclock_nanos(
                           [e for e in events if e["type"] == "PLANNING_COMPLETED"
                            and e["attributes"].get("explicitPlannerDecision") is True],
                           "PLANNING_COMPLETED", "planningDecisionElapsedNanos"))
        for base, values in (("ComputeTotalWaitingTimeSeconds", waits), ("ComputeTrueSlowdown", slow)):
            for label, quantile in (("median", 0.5), ("p95", 0.95), ("max", 1.0)):
                derived[label + base] = percentile(values, quantile)
        for key, source in (("totalModeledProcessingCost", "modeledProcessingCost"), ("totalModeledCpuEnvelopeCost", "modeledCpuEnvelopeCost"),
                            ("totalModeledDeclaredFileBandwidthCost", "modeledDeclaredFileBandwidthCost"), ("totalModeledDeclaredFileBytes", "modeledDeclaredFileBytes")):
            derived[key] = math.fsum(total_costs[source])
        saved_vms = needed(metrics, "vmMetrics")
        audit.ensure(set(saved_vms) == {str(v) for v in vms}, "vm-metric-key-set", prefix)
        busy_values = []
        busy_by_vm = {}
        for vmid in vms:
            intervals = sorted(vm_intervals[vmid])
            previous_finish = None
            for start, finish in intervals:
                audit.ensure(previous_finish is None or start + tolerance(start, previous_finish) >= previous_finish,
                             "vm-overlap", prefix, {"vmId": vmid, "start": start, "previousFinish": previous_finish})
                previous_finish = max(finish, previous_finish if previous_finish is not None else finish)
            busy = union_length(intervals)
            busy_values.append(busy)
            busy_by_vm[vmid] = busy
            saved = needed(saved_vms, str(vmid))
            vm_jobs = [j for j in jobs if j["vmId"] == vmid]
            for key, expected in (("vmId", vmid), ("jobOutcomeCount", len(vm_jobs)), ("modeledBusyIntervalSeconds", busy),
                                  ("modeledIntervalUtilization", busy / end if end > 0 else 0.0),
                                  ("reportedCpuTimeSeconds", math.fsum(number(j["cpuTime"]) for j in vm_jobs)),
                                  ("reportedCpuTimeOverMakespan", math.fsum(number(j["cpuTime"]) for j in vm_jobs) / end if end > 0 else 0.0)):
                audit.metric(saved, key, expected, prefix + ":VM:" + str(vmid))
        avg_busy = mean(busy_values)
        cv = math.sqrt(mean([(x - avg_busy) ** 2 for x in busy_values])) / avg_busy if avg_busy > 0 else 0.0
        fairness = math.fsum(busy_values) ** 2 / (len(busy_values) * math.fsum(x*x for x in busy_values)) if any(busy_values) else 1.0

        expected_vm_energy = {}
        expected_total_energy = 0.0
        expected_peak_power = 0.0
        for vmid, (idle, peak) in vm_power.items():
            e = idle * end + (peak - idle) * busy_by_vm[vmid]
            expected_vm_energy[vmid] = e
            expected_total_energy += e
            expected_peak_power += peak
        powered = any(p[0] > 0.0 or p[1] > 0.0 for p in vm_power.values())

        if vm_tiers:

            powered = True

            expected_vm_energy = {}
            expected_total_energy = 0.0
            expected_peak_power = 0.0
            all_at_default_total = 0.0
            nondefault_busy_observed = False
            for vmid, (idle, peak) in vm_power.items():
                tiers = vm_tiers.get(vmid)
                if tiers is None:
                    e = idle * end + (peak - idle) * busy_by_vm[vmid]
                    expected_peak_power += peak
                    all_at_default_total += e
                else:
                    default_mips, default_idle, default_max = tiers[0]

                    expected_peak_power += max(tier[2] for tier in tiers)

                    ordered_tiers = sorted(
                        (m, union_length(w)) for m, w in tier_intervals_by_vm.get(vmid, {}).items())
                    attributed = ordered_sum([busy for _, busy in ordered_tiers])
                    if abs(attributed - busy_by_vm[vmid]) > 1e-9:
                        raise CheckError(
                            "per-tier busy seconds of VM " + str(vmid) + " (sum=" + str(attributed)
                            + ") do not equal its total modeled busy interval ("
                            + str(busy_by_vm[vmid]) + "): the per-tier conservation evidence chain"
                            + " is broken (BAT-D2)")
                    e = default_idle * end
                    for tier_mips, tier_busy in ordered_tiers:
                        e += (tier_max_by_vm[vmid][tier_mips] - default_idle) * tier_busy
                        if tier_mips != default_mips and tier_busy > 0.0:
                            nondefault_busy_observed = True
                    all_at_default_total += (default_idle * end
                                             + (default_max - default_idle) * busy_by_vm[vmid])
                expected_vm_energy[vmid] = e
                expected_total_energy += e

            audit.notes.append({
                "code": "dvfs-tier-accounting-observation",
                "perTierTotalEnergyJoules": expected_total_energy,
                "allAtDefaultEnergyJoules": all_at_default_total,
                "nonDefaultTierBusyObserved": nondefault_busy_observed,
            })
        energy_doc = doc.get("energyMetrics")
        if powered and energy_doc is None:
            raise CheckError("powered VMs require an energyMetrics section")
        if powered:
            for key, expected in (("totalEnergyJoules", expected_total_energy),
                                  ("meanVmEnergyJoules", expected_total_energy / len(vm_power)),
                                  ("peakPowerWatts", expected_peak_power),
                                  ("energyMakespanProductJouleSeconds", expected_total_energy * end)):
                audit.metric(energy_doc, key, expected, prefix + ":energyMetrics")
            stored_per_vm = needed(energy_doc, "vmEnergyJoules")
            for vmid, expected in expected_vm_energy.items():
                audit.metric(stored_per_vm, str(vmid), expected, prefix + ":energyMetrics:VM:" + str(vmid))

        vm_intensities = {}
        for vmid, vm in vms.items():
            raw = vm.get("carbonIntensityGramsPerKWh")
            if raw is not None:
                vm_intensities[vmid] = number(raw, "VM carbonIntensityGramsPerKWh", nonnegative=True)
        raw_windows = platform.get("carbonWindows")
        if raw_windows is not None and not isinstance(raw_windows, list):
            raise CheckError("platform.carbonWindows must be a list of window objects")
        carbon_windows = []
        for window in raw_windows or []:
            if not isinstance(window, dict):
                raise CheckError("carbonWindows entries must be objects")
            wstart = number(needed(window, "startSeconds"), "window startSeconds", nonnegative=True)
            wend = number(needed(window, "endSeconds"), "window endSeconds", nonnegative=True)
            green = number(needed(window, "greenWatts"), "window greenWatts", nonnegative=True)
            if wend <= wstart:
                raise CheckError("carbon window endSeconds must exceed startSeconds: " + str(window))
            carbon_windows.append((wstart, wend, green))
        for earlier, later in zip(carbon_windows, carbon_windows[1:]):

            if later[0] < earlier[1]:
                raise CheckError("carbonWindows must be sorted and non-overlapping: window start "
                                 + str(later[0]) + " overlaps previous end " + str(earlier[1]))
        if vm_intensities and carbon_windows:

            raise CheckError("Both carbon models declared (per-VM carbonIntensityGramsPerKWh AND"
                             " carbonWindows) — the two accountings are mutually exclusive by contract (BAT-C1)")
        carbon_doc = doc.get("carbonMetrics")
        if not vm_intensities and not carbon_windows:

            audit.ensure(carbon_doc is None, "carbon-metrics-without-declared-carbon-model",
                        prefix + ":carbonMetrics")
        if vm_intensities:

            unpowered = [vmid for vmid, (idle, peak) in vm_power.items()
                         if not (idle > 0.0 or peak > 0.0) and vmid not in vm_tiers]
            half_declared = [vmid for vmid in vms if vmid not in vm_intensities]
            if unpowered or half_declared:
                raise CheckError("Intensity carbon model requires powered VMs with declared"
                                 " carbonIntensityGramsPerKWh on every VM (BAT-C1); unpowered VMs: "
                                 + str(unpowered) + "; VMs missing the intensity: " + str(half_declared))
            if carbon_doc is None:
                raise CheckError("platform carbon intensity declarations require a carbonMetrics"
                                 " section (BAT-C2 declared->required)")
            expected_vm_grams = {}
            total_grams = 0.0
            for vmid, intensity in vm_intensities.items():
                grams = expected_vm_energy[vmid] / 3.6e6 * intensity
                expected_vm_grams[vmid] = grams
                total_grams += grams
            for key, expected in (("totalCarbonGrams", total_grams),
                                  ("meanVmCarbonGrams", total_grams / len(vm_power)),
                                  ("carbonMakespanProductGramSeconds", total_grams * end)):
                audit.metric(carbon_doc, key, expected, prefix + ":carbonMetrics")
            stored_carbon_vm = needed(carbon_doc, "perVmCarbonGrams")
            for vmid, expected in expected_vm_grams.items():
                audit.metric(stored_carbon_vm, str(vmid), expected,
                             prefix + ":carbonMetrics:VM:" + str(vmid))
        elif carbon_windows:

            if not powered:
                raise CheckError("carbonWindows declarations require platform power fields"
                                 " (idlePowerWatts/maxPowerWatts); the fleet-power curve cannot be"
                                 " built on an unpowered profile (BAT-C1)")
            if carbon_doc is None:
                raise CheckError("platform carbonWindows declarations require a carbonMetrics"
                                 " section (BAT-C2 declared->required)")
            idle_sum = 0.0
            planning_algorithm = config.get("planningAlgorithm")
            cawosched = planning_algorithm in ("CAWOSCHED_SLACK", "CAWOSCHED_PRESSURE")
            cawosched_horizon = end
            if cawosched and not vm_tiers:
                cawosched_horizon = number(config.get("deadline", 0.0),
                                           "CaWoSched configured deadline", positive=True)
            if vm_tiers:

                for vmid, (idle, _peak) in vm_power.items():
                    idle_sum += vm_tiers[vmid][0][1] if vmid in vm_tiers else idle
                fleet = {}
                for job in computes:
                    a = number(job["startTime"], "job start", nonnegative=True)
                    b = number(job["finishTime"], "job finish", nonnegative=True)
                    if b > a:
                        vmid = integer(job["vmId"], "job vmId", True)
                        if vmid in vm_tiers:
                            job_tier = tier_of_job[job["jobId"]]
                            dyn = tier_max_by_vm[vmid][job_tier] - vm_tiers[vmid][0][1]
                        else:
                            dyn = vm_power[vmid][1] - vm_power[vmid][0]
                        fleet[a] = fleet.get(a, 0.0) + dyn
                        fleet[b] = fleet.get(b, 0.0) - dyn
                segments = []
                dynamic = 0.0
                prev = 0.0
                for t in sorted(fleet):
                    if t > prev:
                        segments.append((prev, t, idle_sum + dynamic))
                    dynamic += fleet[t]
                    prev = t
                if prev < end:
                    segments.append((prev, end, idle_sum + dynamic))
            else:
                for idle, _peak in vm_power.values():
                    idle_sum += idle
                if cawosched:

                    fleet = {}
                    for job in computes:
                        a = number(job["startTime"], "job start", nonnegative=True)
                        b = number(job["finishTime"], "job finish", nonnegative=True)
                        if b > a:
                            vmid = integer(job["vmId"], "job vmId", True)
                            idle, peak = vm_power[vmid]
                            dynamic = peak - idle
                            fleet[a] = fleet.get(a, 0.0) + dynamic
                            fleet[b] = fleet.get(b, 0.0) - dynamic
                    segments = []
                    active = 0.0
                    prev = 0.0
                    for t in sorted(fleet):
                        if t >= cawosched_horizon:
                            break
                        if t > prev:
                            segments.append((prev, t, idle_sum + active))
                        active += fleet[t]
                        prev = t
                    if prev < cawosched_horizon:
                        segments.append((prev, cawosched_horizon, idle_sum + active))
                else:

                    first_idle, first_peak = next(iter(vm_power.values()))
                    dynamic_per_vm = first_peak - first_idle
                    fleet = {}
                    for job in computes:
                        a = number(job["startTime"], "job start", nonnegative=True)
                        b = number(job["finishTime"], "job finish", nonnegative=True)
                        if b > a:
                            fleet[a] = fleet.get(a, 0) + 1
                            fleet[b] = fleet.get(b, 0) - 1
                    segments = []
                    concurrent = 0
                    prev = 0.0
                    for t in sorted(fleet):
                        if t > prev:
                            segments.append((prev, t, idle_sum + concurrent * dynamic_per_vm))
                        concurrent += fleet[t]
                        prev = t
                    if prev < end:
                        segments.append((prev, end, idle_sum + concurrent * dynamic_per_vm))
            per_window_excess = []
            total_excess = 0.0
            for wstart, wend, green in carbon_windows:
                excess = 0.0
                for lo, hi, power in segments:
                    a = max(lo, wstart)
                    b = min(hi, wend)
                    if b > a:
                        excess += max(0.0, power - green) * (b - a)
                per_window_excess.append(excess)
                if excess > 0.0:
                    total_excess += excess
            audit.metric(carbon_doc, "totalCarbonExcessWattSeconds", total_excess,
                         prefix + ":carbonMetrics")
            stored_windows = needed(carbon_doc, "windowExcessWattSeconds")
            for index, excess in enumerate(per_window_excess):
                key = str(index)
                if excess > 0.0 or key in stored_windows:

                    audit.metric(stored_windows, key, excess,
                                 prefix + ":carbonMetrics:window:" + key)
            for key in stored_windows:
                if not (key.isdigit() and int(key) < len(carbon_windows)):
                    audit.problem("invalid-window-index", prefix + ":carbonMetrics:windowExcessWattSeconds", key)
            window_sum = math.fsum(number(value, "window excess", nonnegative=True)
                                   for value in stored_windows.values())
            saved_total = number(needed(carbon_doc, "totalCarbonExcessWattSeconds"),
                                "totalCarbonExcessWattSeconds", nonnegative=True)
            audit.ensure(math.isclose(window_sum, saved_total, rel_tol=audit.rel_tol, abs_tol=audit.abs_tol),
                         "carbon-window-sum-equals-total", prefix + ":carbonMetrics:windowExcessWattSeconds",
                         window_sum, saved_total)
        derived.update(totalVmModeledBusyIntervalSeconds=math.fsum(busy_values), meanVmModeledIntervalUtilization=avg_busy / end if end > 0 else 0.0,
                       vmModeledBusyTimeCoefficientOfVariation=cv, vmUtilizationJainFairnessIndex=fairness,
                       exactTaskTimingObservationCount=sum(t.get("exactJobTiming") is True for t in task_outcomes),
                       modeledApproximateTaskTimingObservationCount=sum(t.get("exactJobTiming") is not True for t in task_outcomes))

        deadline_seconds = config.get("deadline")
        deadline_threshold = 0 if deadline_seconds is None else int(deadline_seconds)
        if deadline_threshold > 0:
            frac_deadline = Fraction(deadline_threshold)
            frac_make = Fraction(end)
            slack_frac = frac_deadline - frac_make
            slack_double = float(slack_frac)
        else:
            slack_frac = Fraction(0)
            slack_double = 0.0
        workflow_completed = metrics.get("logicalTaskCompletionStatus") == "COMPLETED_SUCCESSFULLY"
        if deadline_threshold <= 0:
            deadline_outcome = "NOT_REQUESTED"
        elif not workflow_completed:
            deadline_outcome = "MISSED_INCOMPLETE_WORKFLOW"
        else:
            deadline_outcome = "MET" if slack_frac >= 0 else "MISSED_LATE"
        derived.update(deadlineObservationEnabled=deadline_threshold > 0,
                       deadlineObservationOutcome=deadline_outcome,
                       deadlineObservationTimeBasis="SIMULATION_END_SECONDS",
                       deadlineMet=deadline_outcome == "MET",
                       deadlineSlackSeconds=slack_double,
                       deadlineTardinessSeconds=max(0.0, -slack_double))

        cp = critical_path_reference(config, platform, raw_tasks)
        derived.update(controlledSharedStorageCriticalPathReferenceAvailable=cp["available"],
                       controlledSharedStorageCriticalPathReferenceScope=cp["scope"])
        if cp["available"]:
            derived["controlledSharedStorageCriticalPathLowerBoundSeconds"] = cp["lowerBound"]
            derived["controlledSharedStorageScheduleLengthRatio"] = (
                end / cp["lowerBound"] if cp["lowerBound"] > 0.0 else 0.0)
        else:
            derived["controlledSharedStorageCriticalPathLowerBoundSeconds"] = 0.0
            derived["controlledSharedStorageScheduleLengthRatio"] = 0.0

        for key, expected in derived.items():
            audit.metric(metrics, key, expected, prefix + ":metrics")
        coverage["checkedMetrics"] = sorted(derived)
        coverage["outsideMetricScope"] = sorted(set(metrics) - set(derived) - {"vmMetrics"})
        coverage["outsideMetricScopeNote"] = ("Decision wall-clock nanos + deadline family are recomputed "
                                              "(review-engineering C10 + chain E, 2026-10-19/20); "
                                              "critical-path references and fields not listed above remain uncertified.")
    except (CheckError, OSError, ValueError, KeyError, TypeError, ArithmeticError) as error:
        audit.problem("invalid-or-unsupported-bundle", prefix, str(error))
        coverage["aborted"] = str(error)
    return {"manifest": prefix, "checks": audit.checks - before_checks, "issueCount": audit.issue_count - before_issues,
            "coverage": coverage, "recomputedMetrics": derived}

def audit_sources(paths, audit, historical=False, input_roots=()):
    results, cache, seen = [], {}, set()
    for source in paths:
        source = Path(source).resolve()
        try:
            doc = read_json(source)
            schema = needed(doc, "schema")
            if schema == "workflowsim-experiment-manifest-v4":
                manifests = [(source, None)]
            elif schema in ("workflowsim-network-study-v1", "workflowsim-workbench-experiment-v1"):
                rows = needed(doc, "runs")
                audit.ensure(bool(rows), "nonempty-study", str(source))
                planned = needed(doc["plan"], "runCount") if schema == "workflowsim-network-study-v1" else needed(doc, "plannedRuns")
                audit.ensure(len(rows) == integer(planned, "planned runs", True), "index-run-count", str(source), len(rows), planned)
                manifests = []
                for row in rows:
                    if row.get("status") != "COMPLETED_SUCCESSFULLY" or "manifest" not in row:
                        audit.problem("failed-or-unretained-index-run", str(source), row.get("runId", row.get("candidate")))
                        continue
                    manifests.append((contained_file(source.parent, row["manifest"]), row))
            else:
                raise CheckError("unsupported evidence input schema: " + str(schema))
            for manifest, row in manifests:
                if manifest in seen:
                    audit.problem("duplicate-manifest-input", str(manifest))
                    continue
                seen.add(manifest)
                results.append(inspect_manifest(manifest, audit, historical, input_roots, cache))
                if row is not None:
                    evidence = read_json(manifest)
                    checked = RUN_METRICS if schema == "workflowsim-network-study-v1" else {"makespanSeconds": "makespanSeconds"}
                    for key, metric_key in checked.items():
                        audit.metric(row, key, needed(evidence["metrics"], metric_key), str(source) + ":run")
                    audit.ensure(integer(needed(row, "seed"), "index seed") == integer(needed(evidence["configuration"], "rootSeed"), "rootSeed"),
                                 "index-seed", str(manifest))
        except (CheckError, OSError, ValueError, KeyError, TypeError) as error:
            audit.problem("invalid-source", str(source), str(error))
    return results

def main(argv=None):
    parser = argparse.ArgumentParser(description='Independently audit saved WorkflowSim bundles; this is not a simulation/network replay.')
    parser.add_argument("inputs", nargs="+", type=Path, help="study/workbench index JSON or a single manifest")
    parser.add_argument("--output", required=True, type=Path, help="new JSON report outside all source study/bundle directories")
    parser.add_argument("--historical", action="store_true", help="explicitly permit old v4 model declarations; physics failures still fail")
    parser.add_argument("--input-root", action="append", default=[], type=Path, help="explicit relocation root for unavailable original inputs")
    parser.add_argument("--rel-tol", type=float, default=1e-10)
    parser.add_argument("--abs-tol", type=float, default=1e-8)
    parser.add_argument("--max-issues", type=int, default=200)
    args = parser.parse_args(argv)
    try:
        if not all(math.isfinite(v) and v >= 0 for v in (args.rel_tol, args.abs_tol)) or args.max_issues < 1:
            raise CheckError("tolerances must be finite/nonnegative and max-issues must be positive")
        output = protect_output(args.output, args.inputs)
        audit = Audit(args.rel_tol, args.abs_tol, args.max_issues)
        runs = audit_sources(args.inputs, audit, args.historical, args.input_root)
        report = {"schema": "workflowsim-independent-evidence-audit-v1", "scope": "RAW_EVIDENCE_AND_METRICS_NOT_SIMULATION_OR_NETWORK_REPLAY",
                  "tolerance": {"relative": args.rel_tol, "absolute": args.abs_tol,
                                "relativeScope": "metric/cost comparisons only, never absolute clocks",
                                "physicalTimeSeconds": "abs_tol + 4 * ulp(max absolute checked timestamp)",
                                "cpuQuantizationSeconds": "1 / assigned VM MIPS, added to physicalTimeSeconds"},
                  "inputs": list(map(str, args.inputs)), "runs": runs, **audit.report()}
        write_report(output, report)
        print("{}: {} bundles, {} issues; {}".format(report["status"], len(runs), audit.issue_count, output))
        return 1 if audit.issue_count else 0
    except (CheckError, OSError) as error:
        print("audit refused: " + str(error), file=sys.stderr)
        return 2

if __name__ == "__main__":
    sys.exit(main())
