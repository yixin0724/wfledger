#!/usr/bin/env python3
"""Small self-contained stdlib tests. No JVM, Maven, retained study, or network is used."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from fractions import Fraction

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from _study_audit import Audit, EXECUTION_SEMANTICS, exact_equal, java_round, layered_xml, registered_plan


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


evidence = load("independent_evidence", "verify-study-evidence.py")
statistics = load("independent_statistics", "verify-study-statistics.py")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def fingerprint(path):
    raw = path.read_bytes()
    return hashlib.sha256(raw).hexdigest(), len(raw)


def bind_sidecars(manifest_path, doc):
    metrics = manifest_path.parent / "run.metrics.json"
    events = manifest_path.parent / "run.events.jsonl"
    write_json(metrics, {"schema": "workflowsim-simulation-metrics-v2", "metrics": doc["metrics"]})
    doc["artifacts"] = [{"role": role, "path": path.name, "sha256": fingerprint(path)[0], "sizeBytes": fingerprint(path)[1]}
                        for role, path in (("metrics", metrics), ("events", events))]
    write_json(manifest_path, doc)


def make_bundle(root, duration=1.0, effective=1000, matrix=False):
    """Hand-calculated single task: 110 MI stage-in, then one 1000-MI declared task.

    Optional matrix duration/effective MI are explicitly supplied by each test. The input is 0.5 B.
    Expected metrics below use these elementary quantities, never a checker calculation.
    """
    root.mkdir(parents=True)
    source = root / "flow.dax"
    source.write_text('<adag version="2.1"><job id="root" name="root" runtime="1"><uses file="x" link="input" size="0.5"/></job></adag>', encoding="utf-8")
    start, end = 0.21, 0.21 + duration
    busy = 0.11 + duration
    transfer = 0.5 / 1_000_000.0
    bw = 0.1 * 0.5 / 1_000_000.0
    def job(jid, cls, a, b, cpu, tids):
        return {"jobId": jid, "vmId": 0, "status": 4, "classType": cls, "submissionTime": a,
                "startTime": a, "finishTime": b, "waitingTime": 0.0, "executionTime": cpu,
                "responseTime": cpu, "cpuTime": cpu, "modeledCpuEnvelopeCost": 3.0 * cpu,
                "modeledDeclaredFileBandwidthCost": bw, "modeledDeclaredFileBytes": 0.5,
                "modeledProcessingCost": 3.0 * cpu + bw, "taskCount": len(tids), "taskIds": tids}
    jobs = [job(1, 1, 0.1, 0.21, 0.11, []), job(0, 2, start, end, duration, [1])]
    task = {"taskId": 1, "jobId": 0, "vmId": 0, "jobStatus": 4, "taskStatus": 4, "lengthMi": 1000,
            "effectiveExecutionLengthMi": effective, "startTime": start, "finishTime": end, "exactJobTiming": True, "depth": 1}
    events = []
    def event(kind, time, jid=None, tids=(), **attrs):
        events.append({"sequence": len(events), "simulationTime": time, "type": kind, "jobId": jid,
                       "taskIds": list(tids), "attributes": attrs})
    event("STAGE_IN_JOB_CREATED", 0.0, 1, inputFileCount=1, inputBytes=0.5)
    event("JOB_READY", 0.1, 1)
    event("SCHEDULING_CYCLE", 0.1, decisionElapsedNanos=100_000)
    event("SCHEDULING_DECISION", 0.1, 1)
    event("JOB_RETURNED", 0.21, 1)
    event("JOB_READY", start, 0, [1])
    event("SCHEDULING_CYCLE", start, decisionElapsedNanos=250_750)
    event("SCHEDULING_DECISION", start, 0, [1])
    event("DATA_STAGE_IN_MODELED", start, 0, [1], requiredFileBytes=0.5, modeledTransferSeconds=transfer, modeledTransferFileCount=1)
    event("TASK_EXECUTION_MODELED", end, 0, [1], taskStartTime=start, taskFinishTime=end, effectiveExecutionLengthMi=effective)
    event("JOB_RETURNED", end, 0, [1])
    (root / "run.events.jsonl").write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
    metrics = {
        "makespanSeconds": end, "logicalTaskCompletionSeconds": end, "logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY",
        "terminalLifecycleTailSeconds": 0.0, "jobOutcomeCount": 2, "computeJobOutcomeCount": 1, "stageInJobOutcomeCount": 1,
        "successfulJobOutcomeCount": 2, "failedJobOutcomeCount": 0, "successfulComputeJobOutcomeCount": 1,
        "failedComputeJobOutcomeCount": 0, "successfulComputeJobOutcomeRate": 1.0,
        "logicalTaskCount": 1, "successfullyCompletedLogicalTaskCount": 1, "logicalTasksNotYetSuccessfullyCompletedCount": 0,
        "successfulLogicalTaskCompletionRate": 1.0, "allLogicalTasksCompletedSuccessfully": True,
        "initialComputeJobOutcomeCount": 1, "retryJobCreatedCount": 0, "completedRetryComputeJobOutcomeCount": 0,
        "logicalTaskAttemptCount": 1, "retriedLogicalTaskCount": 0, "failedComputeAttemptEnvelopeSeconds": 0.0,
        "failedComputeAttemptModeledProcessingCost": 0.0, "retryComputeAttemptModeledProcessingCost": 0.0,
        "computeJobOutcomeThroughputPerSecond": 1.0 / end, "meanComputeJobRunTimeSeconds": duration,
        "meanJobVmQueueWaitingTimeSeconds": 0.0, "meanJobResponseTimeSeconds": duration, "meanJobVmLevelSlowdown": 1.0,
        "vmLevelSlowdownObservationCount": 1, "meanComputeTotalWaitingTimeSeconds": 0.0, "meanComputeTrueSlowdown": 1.0,
        "trueSlowdownObservationCount": 1, "totalWaitingTimeObservationCount": 1,
        "medianComputeTotalWaitingTimeSeconds": 0.0, "p95ComputeTotalWaitingTimeSeconds": 0.0, "maxComputeTotalWaitingTimeSeconds": 0.0,
        "medianComputeTrueSlowdown": 1.0, "p95ComputeTrueSlowdown": 1.0, "maxComputeTrueSlowdown": 1.0,
        "successOnlyMeanComputeTotalWaitingTimeSeconds": 0.0, "successOnlyMeanComputeTrueSlowdown": 1.0,
        "successOnlyWaitingObservationCount": 1, "retryAmplificationRatio": 1.0,
        "meanComputeReadyToDecisionDelaySeconds": 0.0, "meanComputeDecisionToStartDelaySeconds": 0.0,
        "readyToDecisionObservationCount": 1, "decisionToStartObservationCount": 1, "schedulingCycleCount": 2,
        "totalSchedulingDecisionWallClockNanos": 350_750, "totalPlanningDecisionWallClockNanos": 0,
        "deadlineObservationEnabled": False, "deadlineObservationOutcome": "NOT_REQUESTED",
        "deadlineObservationTimeBasis": "SIMULATION_END_SECONDS", "deadlineMet": False,
        "deadlineSlackSeconds": 0.0, "deadlineTardinessSeconds": 0.0,
        "controlledSharedStorageCriticalPathReferenceAvailable": False,
        "controlledSharedStorageCriticalPathReferenceScope": "UNAVAILABLE_OUTSIDE_CONTROLLED_SHARED_STORAGE_SCOPE",
        "controlledSharedStorageCriticalPathLowerBoundSeconds": 0.0,
        "controlledSharedStorageScheduleLengthRatio": 0.0,
        "explicitPlannerDecisionObservationCount": 0, "dataStageInModelObservationCount": 1, "modeledDataTransferFileCount": 1,
        "totalModeledDataTransferSeconds": transfer, "totalModeledRequiredInputBytes": 0.5, "meanModeledDataTransferSeconds": transfer,
        "totalModeledProcessingCost": 3.0 * busy + 2 * bw, "totalModeledCpuEnvelopeCost": 3.0 * busy,
        "totalModeledDeclaredFileBandwidthCost": 2 * bw, "totalModeledDeclaredFileBytes": 1.0,
        "vmMetrics": {"0": {"vmId": 0, "jobOutcomeCount": 2, "reportedCpuTimeSeconds": busy,
                             "modeledBusyIntervalSeconds": busy, "modeledIntervalUtilization": busy / end,
                             "reportedCpuTimeOverMakespan": busy / end}},
        "totalVmModeledBusyIntervalSeconds": busy, "meanVmModeledIntervalUtilization": busy / end,
        "vmModeledBusyTimeCoefficientOfVariation": 0.0, "vmUtilizationJainFairnessIndex": 1.0,
        "exactTaskTimingObservationCount": 1, "modeledApproximateTaskTimingObservationCount": 0,
    }
    config = {"executionSemantics": EXECUTION_SEMANTICS, "vmCount": 1, "rootSeed": 9007199254740993,
              "workflowPaths": [str(source)], "workflowArrivalSeconds": [0.0], "runtimeScale": 1.0, "runtimeReferenceMips": 1000.0,
              "clustering": {"method": "NONE"}, "costModel": "DATACENTER", "taskCostMatrix": None}
    if matrix:
        config["taskCostMatrix"] = {"entries": [{"taskId": 1, "vmId": 0, "executionSeconds": duration}]}
    doc = {"schema": "workflowsim-experiment-manifest-v4", "configuration": config,
           "platform": {"vms": [{"id": 0, "mips": 1000.0, "pes": 1, "schedulerMode": "SPACE_SHARED"}],
                        "costs": {"cpuPerSecond": 3.0, "bandwidth": 0.1}},
           "inputs": [{"path": str(source), "sha256": fingerprint(source)[0], "sizeBytes": fingerprint(source)[1],
                       "format": "DAX_XML", "declaredVersion": "2.1", "taskCount": 1}],
           "workflowProfile": {"taskCount": 1}, "workflowGraph": [{"taskId": 1, "parentIds": [], "childIds": []}],
           "result": {"simulationEndSeconds": end, "jobs": jobs, "tasks": [task]}, "metrics": metrics,
           "events": {"eventCount": len(events)}, "provenance": {"execution": {"workingDirectory": str(root)}}}
    manifest = root / "run.manifest.json"
    bind_sidecars(manifest, doc)
    return manifest


def make_large_clock_bundle(root, lost_cpu_seconds=0.0):
    """One 300-MI task arrives near 1e9 s; optional 0.02 s loss exceeds the 0.001 s MI quantum."""
    manifest = make_bundle(root, duration=0.3, effective=300, matrix=True)
    doc = json.loads(manifest.read_text())
    job = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
    task = doc["result"]["tasks"][0]
    origin = 1_000_000_000.0
    honest_end = origin + job["finishTime"]
    job["submissionTime"] += origin
    job["startTime"] += origin
    job["finishTime"] = honest_end - lost_cpu_seconds
    observed_cpu = job["finishTime"] - job["startTime"]
    job.update(executionTime=observed_cpu, responseTime=observed_cpu, cpuTime=observed_cpu,
               modeledCpuEnvelopeCost=3.0 * observed_cpu,
               modeledProcessingCost=3.0 * observed_cpu + job["modeledDeclaredFileBandwidthCost"])
    task["startTime"] += origin
    task["finishTime"] = honest_end  # Full declared 300-MI modeled window remains unchanged.
    task["exactJobTiming"] = lost_cpu_seconds == 0.0
    doc["configuration"]["workflowArrivalSeconds"] = [job["startTime"]]
    doc["result"]["simulationEndSeconds"] = honest_end
    events_path = manifest.parent / "run.events.jsonl"
    events = [json.loads(line) for line in events_path.read_text().splitlines()]
    first_compute = next(e["sequence"] for e in events if e["type"] == "JOB_READY" and e["jobId"] == 0)
    for event in events:
        if event["sequence"] >= first_compute:
            event["simulationTime"] += origin
        if event["type"] == "TASK_EXECUTION_MODELED":
            event["attributes"]["taskStartTime"] = task["startTime"]
            event["attributes"]["taskFinishTime"] = task["finishTime"]
    events_path.write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
    # Elementary accounting from the edited observations, not an invocation of the checker.
    stage = next(j for j in doc["result"]["jobs"] if j["classType"] == 1)
    busy = (stage["finishTime"] - stage["startTime"]) + observed_cpu
    reported_cpu = stage["cpuTime"] + observed_cpu
    m = doc["metrics"]
    m.update(makespanSeconds=honest_end, logicalTaskCompletionSeconds=job["finishTime"],
             terminalLifecycleTailSeconds=honest_end - job["finishTime"], computeJobOutcomeThroughputPerSecond=1.0 / honest_end,
             meanComputeJobRunTimeSeconds=observed_cpu, meanJobResponseTimeSeconds=observed_cpu,
             totalModeledCpuEnvelopeCost=stage["modeledCpuEnvelopeCost"] + job["modeledCpuEnvelopeCost"],
             totalModeledProcessingCost=stage["modeledProcessingCost"] + job["modeledProcessingCost"],
             totalVmModeledBusyIntervalSeconds=busy, meanVmModeledIntervalUtilization=busy / honest_end,
             exactTaskTimingObservationCount=1 if lost_cpu_seconds == 0.0 else 0,
             modeledApproximateTaskTimingObservationCount=0 if lost_cpu_seconds == 0.0 else 1)
    m["vmMetrics"]["0"].update(reportedCpuTimeSeconds=reported_cpu, modeledBusyIntervalSeconds=busy,
                                 modeledIntervalUtilization=busy / honest_end,
                                 reportedCpuTimeOverMakespan=reported_cpu / honest_end)
    bind_sidecars(manifest, doc)
    return manifest


def make_statistics_study(root):
    """Registered smoke declaration with elementary, manually chosen deterministic observations.

    Registration data supplies only fixture identifiers/parameters; all expected results below
    are literal 100/90/95 or 200/180/190 seconds and exact 10%/5%, p=Holm p=1.
    """
    root.mkdir(parents=True)
    _, _, plan, _ = registered_plan("peft-comparison-r12-v2", "smoke")
    for w in plan["workflows"]:
        w["path"] = str(root / w["path"])
    write_json(root / "protocol.json", plan)
    sha = fingerprint(root / "protocol.json")[0]
    rows, aggregates, comparisons = [], [], []
    for workflow in plan["workflows"]:
        baseline = 100.0 if workflow["id"] == "paper-10" else 200.0
        for network in ("endpoint", "fat-tree-constrained", "fat-tree-wide"):
            for planner, ratio in (("LOCAL_HEFT", 1.0), ("LOCAL_CPOP", 0.9), ("LOCAL_PEFT", 0.95)):
                seconds = baseline * ratio
                rid = workflow["id"] + "-v4-" + network + "-" + planner + "-s11"
                relative = "runs/" + rid + "/result.manifest.json"
                row = {"runId": rid, "workflowId": workflow["id"], "family": workflow["family"], "population": workflow["population"],
                       "vmCount": 4, "network": network, "planner": planner, "seed": 11, "status": "COMPLETED_SUCCESSFULLY",
                       "manifest": relative, "inputSha256": workflow["sha256"], "taskCount": 10 if workflow["id"] == "paper-10" else 16,
                       "makespanSeconds": seconds, "logicalCompletionSeconds": seconds, "meanWaitingSeconds": 0.0,
                       "p95WaitingSeconds": 0.0, "meanVmUtilization": 0.5}
                rows.append(row)
                aggregate = {k: row[k] for k in ("workflowId", "family", "population", "vmCount", "network", "planner")}
                aggregate.update(observedRuns=1, meanSeconds=seconds, medianSeconds=seconds, minimumSeconds=seconds, maximumSeconds=seconds)
                aggregates.append(aggregate)
                config = {"vmCount": 4, "rootSeed": 11, "planningAlgorithm": planner, "schedulingAlgorithm": "STATIC", "fileSystem": "LOCAL",
                          "runtimeScale": 1, "runtimeReferenceMips": 1000, "cloudSimMinEventIntervalSeconds": 0.1, "deadline": 0,
                          "taskCostMatrix": None, "reduceMethod": None, "costModel": "DATACENTER", "workflowArrivalSeconds": [0],
                          "workflowPaths": [workflow["path"]], "executionSemantics": EXECUTION_SEMANTICS,
                          "clustering": {"method": "NONE", "clustersNum": 0, "clustersSize": 0, "code": None},
                          "overheadModel": {"workflowEngineDelayInterval": 0, "bandwidth": 0, "workflowEngineDelays": {}, "queueDelays": {}, "postDelays": {}, "clusteringDelays": {}},
                          "failureModel": {"generatorMode": "FAILURE_NONE", "clusteringAlgorithm": "FTCLUSTERING_NOOP", "monitorMode": "MONITOR_NONE",
                                           "distributionFamily": "WEIBULL", "maxTotalRetryJobs": 0, "generatorAddressing": "DENSE_VM_ID_MATRIX", "generators": [], "generatorsByVmId": {}},
                          "algorithmContract": {"planner": {"id": planner}, "scheduler": {"id": "STATIC"}},
                          "dataMovementModel": {"kind": "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1" if network == "endpoint" else "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1",
                                                "accessLinkBandwidthMbPerSecond": 0, "accessLinkLatencySeconds": 0, "sourceEndpointBandwidthMbPerSecond": 0,
                                                "transferStartSemantics": "ALL_GROUPS_START_AT_JOB_READY;NO_RETROACTIVE_PARENT_PROGRESS", "bandwidthUnit": "DECIMAL_MB_PER_SECOND"}}
                platform = {"hostCount": 4, "vmCount": 4,
                            "hosts": [{"id": i, "pes": 2, "mipsPerPe": 2000, "ramMb": 2048, "bandwidth": 10000, "storageMb": 1000000} for i in range(4)],
                            "vms": [{"id": i, "mips": 1000, "pes": 1, "ramMb": 512, "bandwidth": 1, "imageSizeMb": 10000, "vmm": "Xen",
                                     "schedulerMode": "SPACE_SHARED", "pinnedHostId": i, "preflightHostId": i, "costs": None} for i in range(4)],
                            "storage": {"capacityMb": 1000000000000, "maxTransferRateMbPerSecond": 15},
                            "costs": {"cpuPerSecond": 3, "memory": 0.05, "storage": 0.1, "bandwidth": 0.1},
                            "networkTopology": None if network == "endpoint" else {"kind": "FAT_TREE", "k": 4,
                                "linkBandwidthMbPerSecond": 0.125 if network == "fat-tree-constrained" else 1.25, "coreSwitchCount": None, "hostEdgePlacements": None,
                                "defaultPlacementPolicy": "HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES",
                                "routingPolicy": "DETERMINISTIC_AL_FARES_FAT_TREE_V1",
                                "linkDirectionality": "INDEPENDENT_DIRECTED_LINKS",
                                "externalSourceRouting": "BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY"}}
                write_json(root / relative, {"schema": "workflowsim-experiment-manifest-v4", "configuration": config, "platform": platform,
                    "result": {"workflowCompletedSuccessfully": True, "logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY", "actualVmHostAssignments": {str(i): i for i in range(4)}},
                    "inputs": [{"path": workflow["path"], "sha256": workflow["sha256"], "taskCount": row["taskCount"]}],
                    "metrics": {"makespanSeconds": seconds, "logicalTaskCompletionSeconds": seconds, "meanComputeTotalWaitingTimeSeconds": 0.0,
                                "p95ComputeTotalWaitingTimeSeconds": 0.0, "meanVmModeledIntervalUtilization": 0.5,
                                "logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY", "allLogicalTasksCompletedSuccessfully": True},
                    "provenance": {"study": {"id": plan["protocol"], "protocol": {"logicalId": plan["protocol"], "available": True, "sha256": sha},
                                             "component": {"groupId": "org.workflowsim", "artifactId": "network-study", "version": "1.0", "anchorClass": "org.workflowsim.experiments.network.NetworkStudyExecutor"}}}})
            for candidate, effect in (("LOCAL_CPOP", 10.0), ("LOCAL_PEFT", 5.0)):
                comparisons.append({"population": workflow["population"], "vmCount": 4, "network": network, "baseline": "LOCAL_HEFT", "candidate": candidate,
                                    "dagPairs": 1, "wins": 1, "ties": 0, "losses": 0, "medianImprovementPercent": effect, "pValue": 1.0,
                                    "holmAdjustedPValue": 1.0, "significantAfterHolm": False, "test": "EXACT_TWO_SIDED_SIGN_TEST_ON_DAG_SEED_MEANS",
                                    "inferenceScope": "EXPLORATORY_SELECTED_CORPUS_NOT_RANDOM_WORKLOAD_POPULATION"})
    index = {"schema": "workflowsim-network-study-v1", "plan": plan, "runs": rows,
             "summary": {"runCount": 18, "failedRunCount": 0, "status": "COMPLETE", "aggregates": aggregates, "comparisons": comparisons}}
    write_json(root / "network-study.json", index)
    return root / "network-study.json"


class IndependentAuditorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def cli(self, script, source, output):
        return subprocess.run([sys.executable, str(HERE / script), str(source), "--output", str(output)], capture_output=True, text=True)

    def test_java_round_ties_and_near_ties(self):
        self.assertEqual(0, java_round(0.49999999999999994))
        self.assertEqual(1, java_round(0.5))
        self.assertEqual(0, java_round(-0.5))
        self.assertEqual(2**52 + 1, java_round(float(2**52 + 1)))

    def test_independent_registration_counts_and_frozen_synthetic_hashes(self):
        for protocol, counts in (("network-limited-r10-v3", (504, 36)), ("peft-comparison-r12-v2", (126, 18)), ("sensitivity-response-r13-v2", (546, 30))):
            for mode, count in zip(("full", "smoke"), counts):
                self.assertEqual(count, len(registered_plan(protocol, mode)[3]))
        self.assertEqual("2a7a23bed50abc515523036ea220f292be0a8196a554e8dafeb2e8aba2407ee6", hashlib.sha256(layered_xml(8).encode()).hexdigest())
        self.assertEqual("342347cfb5cb139b52d903a611cb84ff205078250d3bdb754152cfb239af97e2", hashlib.sha256(layered_xml(32).encode()).hexdigest())

    def test_exact_sign_and_holm(self):
        self.assertEqual(Fraction(1, 32), statistics.exact_sign_test(6, 0))
        self.assertEqual(Fraction(1), statistics.exact_sign_test(3, 3))
        self.assertIsNone(statistics.exact_sign_test(0, 0))
        rows = [{"pExact": p} for p in (Fraction(2, 100), Fraction(1, 100), Fraction(4, 100))]
        statistics.holm(rows)
        self.assertEqual([Fraction(4, 100), Fraction(3, 100), Fraction(4, 100)], [r["holmExact"] for r in rows])


    def test_deadline_family_recompute_matches_java_semantics(self):
        # Chain E: build a bundle that requests a deadline and verify exact Python recomputation of the 6 fields.
        manifest = make_bundle(self.root / "deadline", 1.0, 1000, False)
        doc = json.loads(manifest.read_text())
        doc["configuration"]["deadline"] = 200  # 200 = 2x 100 in place (the threshold value input)
        m = doc["metrics"]
        m.update(deadlineObservationEnabled=True, deadlineObservationOutcome="MET",
                 deadlineObservationTimeBasis="SIMULATION_END_SECONDS", deadlineMet=True,
                 deadlineSlackSeconds=200 - m["makespanSeconds"], deadlineTardinessSeconds=0.0)
        bind_sidecars(manifest, doc)
        audit = Audit()
        result = evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())
        # Second round: the MISSED_LATE branch
        manifest2 = make_bundle(self.root / "deadline-late", 1.0, 1000, False)
        doc2 = json.loads(manifest2.read_text())
        doc2["configuration"]["deadline"] = 1  # 1 < makespan(1.21) -> late
        m2 = doc2["metrics"]
        slack2 = 1 - m2["makespanSeconds"]  # negative value -0.21
        m2.update(deadlineObservationEnabled=True, deadlineObservationOutcome="MISSED_LATE",
                  deadlineObservationTimeBasis="SIMULATION_END_SECONDS", deadlineMet=False,
                  deadlineSlackSeconds=slack2, deadlineTardinessSeconds=-slack2)  # +0.21
        bind_sidecars(manifest2, doc2)
        audit2 = Audit()
        result2 = evidence.inspect_manifest(manifest2, audit2)
        self.assertEqual(0, audit2.issue_count, audit2.report())
        for key in ("deadlineObservationEnabled", "deadlineObservationOutcome",
                    "deadlineObservationTimeBasis", "deadlineMet",
                    "deadlineSlackSeconds", "deadlineTardinessSeconds"):
            self.assertIn(key, result["coverage"]["checkedMetrics"], key)
            self.assertIn(key, result2["coverage"]["checkedMetrics"], key)

    def test_wallclock_nanos_recompute_matches_java_number_semantics(self):
        manifest = make_bundle(self.root / "wallclock", 1.0, 1000, False)
        doc = json.loads(manifest.read_text())
        events_path = manifest.parent / "run.events.jsonl"
        events = [json.loads(line) for line in events_path.read_text().splitlines()]
        # 1) Numeric truncation: 250.75 nanoseconds truncates to 250 under Java Number.longValue().
        end = float(doc["result"]["simulationEndSeconds"])
        events.append({"sequence": len(events), "simulationTime": end, "type": "SCHEDULING_CYCLE",
                       "jobId": None, "taskIds": [], "attributes": {"decisionElapsedNanos": 250.75}})
        # 2) A non-numeric value (a string) is skipped under instanceof Number semantics, not judged a failure.
        events.append({"sequence": len(events), "simulationTime": end, "type": "SCHEDULING_CYCLE",
                       "jobId": None, "taskIds": [], "attributes": {"decisionElapsedNanos": "not-a-number"}})
        # 3) An explicit planning event contributes 7 nanoseconds; 4) a non-explicit event carrying 99 nanoseconds must be excluded (same rule as Java).
        events.append({"sequence": len(events), "simulationTime": end, "type": "PLANNING_COMPLETED",
                       "jobId": None, "taskIds": [], "attributes": {"explicitPlannerDecision": True,
                                                                    "planningDecisionElapsedNanos": 7}})
        events.append({"sequence": len(events), "simulationTime": end, "type": "PLANNING_COMPLETED",
                       "jobId": None, "taskIds": [], "attributes": {"explicitPlannerDecision": False,
                                                                    "planningDecisionElapsedNanos": 99}})
        events_path.write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
        metrics = doc["metrics"]
        metrics.update(schedulingCycleCount=4, totalSchedulingDecisionWallClockNanos=351_000,
                       totalPlanningDecisionWallClockNanos=7, explicitPlannerDecisionObservationCount=1)
        doc["events"]["eventCount"] = len(events)
        bind_sidecars(manifest, doc)
        audit = Audit()
        result = evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())
        expected = {"totalSchedulingDecisionWallClockNanos": 351_000, "totalPlanningDecisionWallClockNanos": 7}
        for key, value in expected.items():
            self.assertEqual(value, result["recomputedMetrics"][key], key)
            self.assertIn(key, result["coverage"]["checkedMetrics"])

    def test_energy_conservation_recompute_and_corruption_catch(self):
        """BAT-E2 (2026-10-27): energy-conservation recompute: the positive case (powered platform) passes;
        tampered total energy is killed.

        Math (Beloglazov Eq.(2) binary specialization, single VM idle=100W max=400W):
        E = 100*T_sim + (400-100)*T_busy = 121 + 333 = 454 J (default horizon).
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "powered"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            # Add the platform power fields
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            end = manifest["metrics"]["makespanSeconds"]
            busy = manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"]
            expected_total = 100.0 * end + 300.0 * busy
            manifest["energyMetrics"] = {
                "totalEnergyJoules": expected_total,
                "meanVmEnergyJoules": expected_total,
                "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": expected_total * end,
                "vmEnergyJoules": {"0": expected_total},
            }
            (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            report_path = Path(tmp) / "energy-report.json"
            evidence.main(["--output", str(report_path), str(root / "run.manifest.json")])
            self.assertEqual(json.loads(report_path.read_text(encoding="utf-8"))["status"], "PASS")
            # Counterexample: tampered total energy -> the conservation law kills it
            manifest["energyMetrics"]["totalEnergyJoules"] = expected_total + 500.0
            (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            bad_report = Path(tmp) / "energy-bad.json"
            evidence.main(["--output", str(bad_report), str(root / "run.manifest.json")])
            bad = json.loads(bad_report.read_text(encoding="utf-8"))
            self.assertEqual(bad["status"], "FAIL")
            self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in bad["issues"]),
                            "corrupted total energy must be caught: " + json.dumps(bad["issues"][:2]))

    def test_event_stream_order_sequence_and_blank_lines_are_killed(self):
        """Review round: the event-stream order/sequence/blank-line checks had no negative case pinning them
        (implemented but never verified).

        One case per shape: clock regression (event-clock-order), duplicate sequence (event-sequence),
        blank line (blank line). The verdict is the auditor subprocess exit code (hard errors and FAIL must both be nonzero).
        """
        def run_case(mutate):
            root = Path(self.root / f"stream-{len(list(self.root.iterdir()))}")
            make_bundle(root)
            events_path = root / "run.events.jsonl"
            events = [json.loads(line) for line in events_path.read_text(encoding="utf-8").splitlines() if line.strip()]
            mutate(events, events_path)
            output = root / "stream-report.json"
            completed = self.cli("verify-study-evidence.py", root / "run.manifest.json", output)
            body = output.read_text(encoding="utf-8") if output.exists() else ""
            return completed.returncode, body

        def clock_backwards(events, path):
            events[1]["simulationTime"] = 0.0
            path.write_text("".join(json.dumps(e) + "\n" for e in events), encoding="utf-8")

        def duplicate_sequence(events, path):
            events[1]["sequence"] = events[0]["sequence"]
            path.write_text("".join(json.dumps(e) + "\n" for e in events), encoding="utf-8")

        def blank_line(events, path):
            body = "\n".join(json.dumps(e) for e in events)
            path.write_text(body.replace("\n", "\n\n", 1) + "\n", encoding="utf-8")

        for name, mutate, needle in (
                ("clock-order", clock_backwards, "event-clock-order"),
                ("sequence", duplicate_sequence, "event-sequence"),
                ("blank-line", blank_line, "blank")):
            code, body = run_case(mutate)
            self.assertNotEqual(0, code, name + " must be rejected")
            if body:
                self.assertIn(needle, body, name + " must be reported with its contract code")

    def test_event_sequence_bounds_must_match_the_stream(self):
        """Review round (found by cross-language fuzzing): the manifest's self-reported first/lastSequence must bind
        to the event stream.

        Previously both sides (the Java validator and the Python auditor) only checked eventCount, so tampered
        sequence bounds could pass the whole bundle -- declared context disagreed with the evidence and nobody noticed.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "sequence"
            make_bundle(root)
            manifest_path = root / "run.manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            events = [json.loads(line) for line in (root / "run.events.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
            manifest["events"]["firstSequence"] = events[0]["sequence"]
            manifest["events"]["lastSequence"] = events[-1]["sequence"]
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            good = Path(tmp) / "seq-good.json"
            evidence.main(["--output", str(good), str(manifest_path)])
            self.assertEqual(json.loads(good.read_text(encoding="utf-8"))["status"], "PASS")

            manifest["events"]["lastSequence"] = events[-1]["sequence"] + 1
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            bad = Path(tmp) / "seq-bad.json"
            evidence.main(["--output", str(bad), str(manifest_path)])
            report = json.loads(bad.read_text(encoding="utf-8"))
            self.assertEqual(report["status"], "FAIL")
            self.assertTrue(any("lastSequence" in json.dumps(issue) for issue in report["issues"]),
                            "corrupted lastSequence must be caught: " + json.dumps(report["issues"][:2]))

    def test_healthy_single_manifest_and_matrix_override(self):
        for name, duration, effective, matrix in (("plain", 1.0, 1000, False), ("matrix", 0.5, 500, True)):
            manifest = make_bundle(self.root / name, duration, effective, matrix)
            audit = Audit()
            evidence.inspect_manifest(manifest, audit)
            self.assertEqual(0, audit.issue_count, audit.report())

    def test_large_clock_fractional_duration_control_remains_valid(self):
        manifest = make_large_clock_bundle(self.root / "large-valid")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())

    def test_large_clock_cannot_hide_cpu_shortfall_beyond_one_mi(self):
        manifest = make_large_clock_bundle(self.root / "large-short", lost_cpu_seconds=0.02)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts["cpu-lower-bound"], 0,
                           "20 MI of lost CPU work must be detected independently of other checks: " + str(audit.report()))

    def test_cli_failure_is_nonzero_for_coherently_missing_required_metric(self):
        manifest = make_bundle(self.root / "bundle")
        doc = json.loads(manifest.read_text())
        del doc["metrics"]["p95ComputeTotalWaitingTimeSeconds"]
        bind_sidecars(manifest, doc)
        result = self.cli("verify-study-evidence.py", manifest, self.root / "reports" / "bad.json")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        report = json.loads((self.root / "reports" / "bad.json").read_text())
        self.assertGreater(report["issueCounts"].get("missing-required-metric", 0), 0)

    def test_overlap_and_raw_mi_forgery_are_detected(self):
        manifest = make_bundle(self.root / "bundle")
        doc = json.loads(manifest.read_text())
        doc["result"]["jobs"][1]["startTime"] = 0.15
        doc["result"]["tasks"][0]["lengthMi"] = 2000
        write_json(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts["vm-overlap"], 0, audit.report())
        self.assertGreater(audit.counts["raw-runtime-mi"], 0, audit.report())
        self.assertGreater(audit.counts["cpu-lower-bound"], 0, audit.report())

    def test_report_never_overwrites_or_writes_into_bundle(self):
        manifest = make_bundle(self.root / "bundle")
        before = fingerprint(manifest)
        forbidden = self.cli("verify-study-evidence.py", manifest, manifest.parent / "audit.json")
        self.assertEqual(2, forbidden.returncode)
        self.assertFalse((manifest.parent / "audit.json").exists())
        target = self.root / "report.json"
        first = self.cli("verify-study-evidence.py", manifest, target)
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        saved = target.read_bytes()
        second = self.cli("verify-study-evidence.py", manifest, target)
        self.assertEqual(2, second.returncode)
        self.assertEqual(saved, target.read_bytes())
        self.assertEqual(before, fingerprint(manifest))

    def test_statistics_reject_each_changed_or_extra_topology_semantic(self):
        source = make_statistics_study(self.root / "topology-study")
        index = json.loads(source.read_text())
        row = next(r for r in index["runs"] if r["network"] == "fat-tree-constrained")
        manifest = source.parent / row["manifest"]
        original = json.loads(manifest.read_text())
        for field in ("defaultPlacementPolicy", "routingPolicy", "linkDirectionality", "externalSourceRouting", "unregisteredSemantic"):
            changed = copy.deepcopy(original)
            changed["platform"]["networkTopology"][field] = "WRONG_OR_UNREGISTERED"
            write_json(manifest, changed)
            audit = Audit()
            statistics.inspect_index(source, audit)
            self.assertTrue(any(i["code"] == "fixed-condition" and i["location"].endswith("platform/networkTopology")
                                for i in audit.issues), (field, audit.report()))
        write_json(manifest, original)

    def test_statistics_emit_full_per_dag_effects_and_reject_pruning(self):
        source = make_statistics_study(self.root / "study")
        audit = Audit()
        report, _ = statistics.inspect_index(source, audit)
        self.assertEqual(0, audit.issue_count, audit.report())
        self.assertEqual(12, len(report["perDagEffects"]))
        self.assertEqual({5.0, 10.0}, {r["improvementPercent"] for r in report["perDagEffects"]})
        doc = json.loads(source.read_text())
        doc["plan"]["workflows"] = []
        doc["plan"]["runCount"] = 0
        doc["runs"] = []
        write_json(source.parent / "protocol.json", doc["plan"])
        write_json(source, doc)
        outcome = self.cli("verify-study-statistics.py", source, self.root / "pruned.json")
        self.assertEqual(1, outcome.returncode, outcome.stdout + outcome.stderr)


def inject_contract_events(manifest, terminal_delta=0.0, start_delta=0.0, duplicate_start=False):
    """Insert the F-006/F-007 revision events into a make_bundle fixture.

    Elementary quantities only: the job envelope start is the fixture's known 0.21 and the
    modeled post-stage start equals envelope + effective stage. The tampering knobs corrupt
    exactly one derivation or identity, proving the independent auditor rejects each."""
    doc = json.loads(manifest.read_text())
    end = float(doc["result"]["simulationEndSeconds"])
    events = [json.loads(line) for line in (manifest.parent / "run.events.jsonl").read_text().splitlines() if line.strip()]
    insert_at = next(i for i, e in enumerate(events) if e["type"] == "TASK_EXECUTION_MODELED")
    start_event = {"sequence": insert_at, "simulationTime": 0.21, "type": "JOB_EXECUTION_STARTED",
                   "jobId": 0, "taskIds": [1],
                   "attributes": {"jobEnvelopeStartSeconds": 0.21, "effectiveDataStageSeconds": 0.0,
                                  "requestedDataStageSeconds": 5e-07,
                                  "modeledJobStartSeconds": 0.21 + start_delta}}
    events.insert(insert_at, start_event)
    if duplicate_start:
        events.insert(insert_at + 1, dict(start_event))
    events.append({"sequence": len(events), "simulationTime": end + terminal_delta,
                   "type": "SIMULATION_TERMINATED", "jobId": None, "taskIds": [],
                   "attributes": {"simulationEndSeconds": end + terminal_delta}})
    for index, event in enumerate(events):
        event["sequence"] = index
    (manifest.parent / "run.events.jsonl").write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
    doc["events"]["eventCount"] = len(events)
    bind_sidecars(manifest, doc)
    return manifest


class ContractEventAuditTests(unittest.TestCase):
    """F-006/F-007 (adjudicated 2026-10-23): terminal and execution-start event cross-checks.

    Legacy streams without the revision events must keep passing unchanged; revision streams
    must be validated for terminal-clock identity, per-instance uniqueness, derivation
    arithmetic, and executed-job coverage."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_healthy_revision_bundle_and_legacy_bundle_both_pass(self):
        legacy_report = Audit()
        evidence.inspect_manifest(make_bundle(self.root / "legacy"), legacy_report)
        self.assertEqual(0, legacy_report.issue_count, legacy_report.report())
        revision_report = Audit()
        revision_manifest = inject_contract_events(make_bundle(self.root / "revisioned"))
        evidence.inspect_manifest(revision_manifest, revision_report)
        self.assertEqual(0, revision_report.issue_count, revision_report.report())

    def test_terminal_clock_identity_mismatch_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "terminal-mismatch"), terminal_delta=0.05)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("terminal-clock-identity", 0), 0, audit.report())

    def test_job_start_derivation_mismatch_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "start-mismatch"), start_delta=0.05)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("job-start-derivation", 0), 0, audit.report())

    def test_duplicate_job_execution_started_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "duplicate-start"), duplicate_start=True)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("duplicate-JOB_EXECUTION_STARTED", 0), 0, audit.report())

    def test_job_start_missing_for_executed_job_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "missing-start"))
        events = [json.loads(line) for line in (manifest.parent / "run.events.jsonl").read_text().splitlines()]
        start_at = next(i for i, e in enumerate(events) if e["type"] == "JOB_EXECUTION_STARTED")
        del events[start_at]
        for index, event in enumerate(events):
            event["sequence"] = index
        (manifest.parent / "run.events.jsonl").write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
        doc = json.loads(manifest.read_text())
        doc["events"]["eventCount"] = len(events)
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("job-start-coverage", 0), 0, audit.report())

    def test_a_declared_revision_contract_requires_its_terminal_marker(self):
        """AUD-F23: declaring the contract turns the conditional terminal check into a requirement."""
        manifest = inject_contract_events(make_bundle(self.root / "declared-terminal"))
        rewrite_stream(manifest, {"SIMULATION_TERMINATED"}, declared="F-006/F-007")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("revision-contract-terminal", 0), 0, audit.report())

    def test_a_declared_contract_rejects_a_stream_with_every_marker_removed(self):
        """AUD-F23: the reproduced evasion -- remove both markers, re-sign, keep result.jobs intact."""
        manifest = inject_contract_events(make_bundle(self.root / "declared-markers"))
        rewrite_stream(manifest, {"SIMULATION_TERMINATED", "JOB_EXECUTION_STARTED"},
                       declared="F-006/F-007")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("revision-contract-terminal", 0), 0, audit.report())

    def test_an_undeclared_markerless_stream_keeps_the_legacy_boundary(self):
        """AUD-F23 compatibility pin: retained pre-declaration bundles stay readable (0 issues)."""
        manifest = make_bundle(self.root / "undeclared-legacy")
        rewrite_stream(manifest, {"SIMULATION_TERMINATED", "JOB_EXECUTION_STARTED"}, declared=None)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())

    def test_a_declared_contract_with_its_markers_intact_passes(self):
        """AUD-F23 positive case: the declaration is not a blanket failure."""
        manifest = inject_contract_events(make_bundle(self.root / "declared-healthy"))
        rewrite_stream(manifest, set(), declared="F-006/F-007")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())

    def test_an_unknown_declared_contract_is_rejected(self):
        """AUD-F23: an unknown declaration must not be read as the implemented contract."""
        manifest = inject_contract_events(make_bundle(self.root / "declared-unknown"))
        rewrite_stream(manifest, set(), declared="v0")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("revision-contract", 0), 0, audit.report())


def rewrite_stream(manifest, drop_types, declared="keep"):
    """Delete the named event types, renumber the survivors, and re-sign the manifest.

    AUD-F23 helper: a tamperer who removes markers must also renumber sequences and update the
    declared count/digest, otherwise the attack is stopped by a plumbing check and never reaches
    the contract check under test. ``declared`` replaces the events ``revisionContract`` value;
    the default keeps whatever the fixture already declared, and None removes the key."""
    path = manifest.parent / "run.events.jsonl"
    events = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    kept = [event for event in events if event["type"] not in drop_types]
    for index, event in enumerate(kept):
        event["sequence"] = index
    path.write_text(''.join(json.dumps(event) + '\n' for event in kept), encoding="utf-8")
    doc = json.loads(manifest.read_text())
    doc["events"]["eventCount"] = len(kept)
    if kept:
        doc["events"]["lastSequence"] = kept[-1]["sequence"]
    if declared is None:
        doc["events"].pop("revisionContract", None)
    elif declared != "keep":
        doc["events"]["revisionContract"] = declared
    bind_sidecars(manifest, doc)
    return manifest


class CarbonDimensionAuditTests(unittest.TestCase):
    """BAT-C2 (2026-10-28): carbon-dimension recompute self-test -- expected values come only from the
    dimensional law (E[J]/3.6e6 * I[g/kWh]) and the budget-excess integral (sum max(0, P_fleet(t)-green)*dt),
    i.e. the two model families contracted in CARBON_DIMENSION_DESIGN.md §1; hand-derived, never the
    implementation output. declared->required mirrors the energyMetrics powered->required rule
    (corruption must be killed)."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def cli(self, script, source, output):
        return subprocess.run([sys.executable, str(HERE / script), str(source), "--output", str(output)],
                              capture_output=True, text=True)

    def audit_status(self, manifest, report_name):
        """Run the independent audit CLI once and return the report dict."""
        report = Path(self.temp.name) / (report_name + ".json")
        evidence.main(["--output", str(report), str(manifest)])
        return json.loads(report.read_text(encoding="utf-8"))

    @staticmethod
    def elementary_energy(manifest):
        """Elementary quantity: E = 100*end + 300*busy (fixture platform idle=100W max=400W)."""
        end = float(manifest["metrics"]["makespanSeconds"])
        busy = float(manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"])
        return 100.0 * end + 300.0 * busy, end, busy

    def test_carbon_intensity_recompute_and_corruption(self):
        """Positive case: intensity 380 g/kWh -> grams = E/3.6e6 * 380 passes the audit; tampering +5g must be killed."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "carbon-intensity"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            manifest["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
            energy, end, _busy = self.elementary_energy(manifest)
            # energyMetrics is declared too (powered->required, an existing rule).
            manifest["energyMetrics"] = {
                "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
            }
            grams = energy / 3.6e6 * 380.0  # unit law: J -> kWh divides by 3.6e6, then times g/kWh
            manifest["carbonMetrics"] = {
                "totalCarbonGrams": grams, "meanVmCarbonGrams": grams / 1,
                "carbonMakespanProductGramSeconds": grams * end, "perVmCarbonGrams": {"0": grams},
                "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
            }
            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            good = self.audit_status(bundle, "carbon-good")
            self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
            # Counterexample: tampered total grams -> recompute delta > tolerance -> audit fails, citing totalCarbonGrams
            manifest["carbonMetrics"]["totalCarbonGrams"] = grams + 5.0
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad = self.audit_status(bundle, "carbon-bad")
            self.assertEqual("FAIL", bad["status"])
            self.assertTrue(any("totalCarbonGrams" in str(i.get("location", "")) for i in bad["issues"]),
                            "corrupted total carbon must be caught: " + json.dumps(bad["issues"][:2]))

    def test_carbon_missing_when_intensities_declared(self):
        """BAT-C2 declared->required: carbon intensity declared but the carbonMetrics section missing -> the audit must fail."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "carbon-missing"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            manifest["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
            energy, end, _busy = self.elementary_energy(manifest)
            manifest["energyMetrics"] = {
                "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
            }
            # Do not write carbonMetrics (deliberate: declared -> required)
            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            report = self.audit_status(bundle, "carbon-missing")
            self.assertEqual("FAIL", report["status"],
                             "intensity declarations without carbonMetrics must fail (BAT-C2 declared→required)")
            self.assertTrue(any("carbonMetrics" in str(i) for i in report["issues"]),
                            "failure must cite the carbonMetrics section: " + json.dumps(report["issues"][:3]))

    def test_carbon_budget_window_recompute_and_corruption(self):
        """Budget model: hand-derived fleet timeline (compute Job intervals plus the sum of idle/concurrency*deltaP) and
        per-window excess.

        Math (fixture single VM idle=100W max=400W; compute Job interval [0.21, 1.21)):
        P(t)=400W on [0.21,1.21), 100W elsewhere; window [0,1.5) green 250W -> excess (400-250)*1.0=150 W*s;
        window [2,5) green 250W -> outside the covered interval, nothing accumulates -> 0. Total 150 agrees with
        the window sub-map {"0":150}.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "carbon-budget"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            manifest["platform"]["carbonWindows"] = [
                {"startSeconds": 0.0, "endSeconds": 1.5, "greenWatts": 250.0},
                {"startSeconds": 2.0, "endSeconds": 5.0, "greenWatts": 250.0},
            ]
            energy, end, _busy = self.elementary_energy(manifest)
            manifest["energyMetrics"] = {
                "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
            }
            manifest["carbonMetrics"] = {
                "totalCarbonGrams": 0.0, "meanVmCarbonGrams": 0.0,
                "carbonMakespanProductGramSeconds": 0.0, "perVmCarbonGrams": {},
                "totalCarbonExcessWattSeconds": 150.0, "windowExcessWattSeconds": {"0": 150.0},
            }
            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            good = self.audit_status(bundle, "budget-good")
            self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
            # Counterexample 1: tampered total excess -> killed
            manifest["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 155.0
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad1 = self.audit_status(bundle, "budget-bad-total")
            self.assertEqual("FAIL", bad1["status"])
            self.assertTrue(any("totalCarbonExcessWattSeconds" in str(i.get("location", "")) for i in bad1["issues"]),
                            "corrupted total excess must be caught: " + json.dumps(bad1["issues"][:2]))
            # Counterexample 2: window sub-map inconsistent with the total (sub-map != recomputed per-window values) -> killed
            manifest["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 150.0
            manifest["carbonMetrics"]["windowExcessWattSeconds"] = {"0": 100.0}
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad2 = self.audit_status(bundle, "budget-bad-window")
            self.assertEqual("FAIL", bad2["status"])
            self.assertTrue(any("windowExcess" in str(i) for i in bad2["issues"]),
                            "corrupted window sub-map must be caught: " + json.dumps(bad2["issues"][:2]))
            # Counterexample 3: both models declared (per-VM intensity + platform windows) -> the contract's mutual-exclusion clause kills it
            manifest["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
            manifest["carbonMetrics"]["windowExcessWattSeconds"] = {"0": 150.0}
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            both = self.audit_status(bundle, "budget-bad-both")
            self.assertEqual("FAIL", both["status"])
            self.assertTrue(any("mutually exclusive" in str(i) or "intensity" in str(i) for i in both["issues"]),
                            "dual declaration must be rejected citing the exclusion clause: "
                            + json.dumps(both["issues"][:2]))

    def test_carbon_windows_must_be_sorted_non_overlapping(self):
        """Window ordering check: out-of-order/overlap -> the contract clause kills it (mirrors the Java builder validation)."""
        for where, windows in (
            ("overlap", [{"startSeconds": 0.0, "endSeconds": 2.0, "greenWatts": 250.0},
                         {"startSeconds": 1.0, "endSeconds": 5.0, "greenWatts": 100.0}]),
            ("unsorted", [{"startSeconds": 2.0, "endSeconds": 5.0, "greenWatts": 250.0},
                          {"startSeconds": 0.0, "endSeconds": 1.5, "greenWatts": 250.0}]),
        ):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp) / ("carbon-window-" + where)
                make_bundle(root)
                manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
                manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
                manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
                manifest["platform"]["carbonWindows"] = windows
                energy, end, _busy = self.elementary_energy(manifest)
                manifest["energyMetrics"] = {
                    "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                    "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
                }
                manifest["carbonMetrics"] = {
                    "totalCarbonGrams": 0.0, "meanVmCarbonGrams": 0.0,
                    "carbonMakespanProductGramSeconds": 0.0, "perVmCarbonGrams": {},
                    "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
                }
                bundle = root / "run.manifest.json"
                bundle.write_text(json.dumps(manifest), encoding="utf-8")
                report = self.audit_status(bundle, "window-" + where)
                self.assertEqual("FAIL", report["status"], where)
                self.assertTrue(any("sorted" in str(i) or "overlapping" in str(i) for i in report["issues"]),
                                where + ": window order clause must fire: " + json.dumps(report["issues"][:2]))


class CaWoSchedBudgetAuditTests(unittest.TestCase):
    """Hand-derived CaWoSched report horizon and flat heterogeneous-fleet oracles."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def audit_status(self, manifest_path, report_name):
        report = Path(self.temp.name) / (report_name + ".json")
        evidence.main(["--output", str(report), str(manifest_path)])
        return json.loads(report.read_text(encoding="utf-8"))

    def make_flat_budget(self, name, planner, deadline=10, green=0.0):
        root = self.root / name
        make_bundle(root)
        source = root / "flow.dax"
        source.write_text(
            '<adag version="3.3"><job id="a" name="a" runtime="1"><uses file="x" link="input" size="0.5"/></job>'
            '<job id="b" name="b" runtime="2"><uses file="x" link="input" size="0.5"/></job>'
            '<child ref="b"><parent ref="a"/></child></adag>', encoding="utf-8")
        doc = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        doc["inputs"][0].update({
            "sha256": fingerprint(source)[0],
            "sizeBytes": fingerprint(source)[1],
            "declaredVersion": "3.3",
            "taskCount": 2,
        })
        doc["workflowProfile"]["taskCount"] = 2
        doc["workflowGraph"] = [
            {"taskId": 1, "parentIds": [], "childIds": [2]},
            {"taskId": 2, "parentIds": [1], "childIds": []},
        ]
        second_job = copy.deepcopy(doc["result"]["jobs"][1])
        second_job.update({
            "jobId": 2,
            "vmId": 1,
            "submissionTime": 1.21,
            "startTime": 1.21,
            "finishTime": 3.21,
            "executionTime": 2.0,
            "responseTime": 2.0,
            "cpuTime": 2.0,
            "modeledCpuEnvelopeCost": 6.0,
            "modeledProcessingCost": 6.0 + 0.1 * 0.5 / 1_000_000.0,
            "taskIds": [2],
        })
        doc["result"]["jobs"].append(second_job)
        second_task = copy.deepcopy(doc["result"]["tasks"][0])
        second_task.update({
            "taskId": 2,
            "jobId": 2,
            "vmId": 1,
            "startTime": 1.21,
            "finishTime": 3.21,
            "lengthMi": 2000,
            "effectiveExecutionLengthMi": 2000,
            "depth": 2,
        })
        doc["result"]["tasks"].append(second_task)
        events_path = root / "run.events.jsonl"
        events = [json.loads(line) for line in events_path.read_text(encoding="utf-8").splitlines()]
        transfer = 0.5 / 1_000_000.0
        for event_type, time, attrs in (
            ("JOB_READY", 1.21, {}),
            ("SCHEDULING_CYCLE", 1.21, {"decisionElapsedNanos": 250_750}),
            ("SCHEDULING_DECISION", 1.21, {}),
            ("DATA_STAGE_IN_MODELED", 1.21, {
                "requiredFileBytes": 0.5,
                "modeledTransferSeconds": transfer,
                "modeledTransferFileCount": 1,
            }),
            ("TASK_EXECUTION_MODELED", 3.21, {
                "taskStartTime": 1.21,
                "taskFinishTime": 3.21,
                "effectiveExecutionLengthMi": 2000,
            }),
            ("JOB_RETURNED", 3.21, {}),
        ):
            events.append({
                "sequence": len(events),
                "simulationTime": time,
                "type": event_type,
                "jobId": 2,
                "taskIds": [2],
                "attributes": attrs,
            })
        events_path.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
        doc["events"]["eventCount"] = len(events)
        doc["configuration"].update({
            "planningAlgorithm": planner,
            "schedulingAlgorithm": "STATIC",
            "deadline": deadline,
            "fileSystem": "SHARED",
            "dataMovementModel": {"kind": "LEGACY_WORKFLOWSIM_V1"},
            "failureModel": {"generatorMode": "FAILURE_NONE"},
            "overheadModel": {},
        })
        first, second = doc["platform"]["vms"][0], copy.deepcopy(doc["platform"]["vms"][0])
        first.update({"idlePowerWatts": 10.0, "maxPowerWatts": 110.0})
        second.update({"id": 1, "idlePowerWatts": 20.0, "maxPowerWatts": 220.0})
        doc["platform"]["vms"] = [first, second]
        doc["configuration"]["vmCount"] = 2
        doc["result"]["actualVmHostAssignments"] = {"0": 0, "1": 1}
        doc["platform"]["carbonWindows"] = [
            {"startSeconds": 0.0, "endSeconds": 5.0, "greenWatts": green},
            {"startSeconds": 5.0, "endSeconds": 10.0, "greenWatts": green},
        ]
        bandwidth = 0.1 * 0.5 / 1_000_000.0
        transfer = 0.5 / 1_000_000.0
        end = 3.21
        doc["result"]["simulationEndSeconds"] = end
        doc["metrics"]["makespanSeconds"] = end
        doc["metrics"]["logicalTaskCompletionSeconds"] = end
        doc["metrics"].update({
            "deadlineObservationEnabled": True,
            "deadlineObservationOutcome": "MET",
            "deadlineMet": True,
            "deadlineSlackSeconds": float(deadline) - end,
            "jobOutcomeCount": 3,
            "computeJobOutcomeCount": 2,
            "successfulJobOutcomeCount": 3,
            "successfulComputeJobOutcomeCount": 2,
            "logicalTaskCount": 2,
            "successfullyCompletedLogicalTaskCount": 2,
            "logicalTasksNotYetSuccessfullyCompletedCount": 0,
            "initialComputeJobOutcomeCount": 2,
            "logicalTaskAttemptCount": 2,
            "computeJobOutcomeThroughputPerSecond": 2.0 / end,
            "meanComputeJobRunTimeSeconds": 1.5,
            "meanJobResponseTimeSeconds": 1.5,
            "vmLevelSlowdownObservationCount": 2,
            "trueSlowdownObservationCount": 2,
            "totalWaitingTimeObservationCount": 2,
            "successOnlyWaitingObservationCount": 2,
            "readyToDecisionObservationCount": 2,
            "decisionToStartObservationCount": 2,
            "schedulingCycleCount": 3,
            "totalSchedulingDecisionWallClockNanos": 601_500,
            "dataStageInModelObservationCount": 2,
            "modeledDataTransferFileCount": 2,
            "totalModeledDataTransferSeconds": 2.0 * transfer,
            "totalModeledRequiredInputBytes": 1.0,
            "totalModeledProcessingCost": 9.33 + 3.0 * bandwidth,
            "totalModeledCpuEnvelopeCost": 9.33,
            "totalModeledDeclaredFileBandwidthCost": 3.0 * bandwidth,
            "totalModeledDeclaredFileBytes": 1.5,
            "totalVmModeledBusyIntervalSeconds": 3.11,
            "meanVmModeledIntervalUtilization": 3.11 / (2.0 * end),
            "vmModeledBusyTimeCoefficientOfVariation": 0.445 / 1.555,
            "vmUtilizationJainFairnessIndex": 3.11**2 / (2.0 * (1.11**2 + 2.0**2)),
            "exactTaskTimingObservationCount": 2,
        })
        doc["metrics"]["vmMetrics"]["0"].update({
            "reportedCpuTimeSeconds": 1.11,
            "modeledBusyIntervalSeconds": 1.11,
            "modeledIntervalUtilization": 1.11 / end,
            "reportedCpuTimeOverMakespan": 1.11 / end,
        })
        doc["metrics"]["vmMetrics"]["1"] = {
            "vmId": 1,
            "jobOutcomeCount": 1,
            "reportedCpuTimeSeconds": 2.0,
            "modeledBusyIntervalSeconds": 2.0,
            "modeledIntervalUtilization": 2.0 / end,
            "reportedCpuTimeOverMakespan": 2.0 / end,
        }
        vm0_energy = 10.0 * end + 100.0 * (0.11 + 1.0)
        vm1_energy = 20.0 * end + 200.0 * 2.0
        total_energy = vm0_energy + vm1_energy
        doc["energyMetrics"] = {
            "totalEnergyJoules": total_energy,
            "meanVmEnergyJoules": total_energy / 2.0,
            "peakPowerWatts": 330.0,
            "energyMakespanProductJouleSeconds": total_energy * end,
            "vmEnergyJoules": {"0": vm0_energy, "1": vm1_energy},
        }
        manifest = root / "run.manifest.json"
        bind_sidecars(manifest, doc)
        return manifest, doc

    def test_cawosched_full_deadline_and_heterogeneous_flat_power(self):
        """Dynamic 100*1+200*2=500 plus idle 30*10=300 => 800 W*s, split 650+150."""
        bundle, doc = self.make_flat_budget("cawosched-budget", "CAWOSCHED_SLACK")
        doc["carbonMetrics"] = {
            "totalCarbonGrams": 0.0,
            "meanVmCarbonGrams": 0.0,
            "carbonMakespanProductGramSeconds": 0.0,
            "perVmCarbonGrams": {},
            "totalCarbonExcessWattSeconds": 800.0,
            "windowExcessWattSeconds": {"0": 650.0, "1": 150.0},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "cawosched-budget-report")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))

    def test_cawosched_pressure_uses_same_horizon_and_power_law(self):
        bundle, doc = self.make_flat_budget("cawosched-pressure-budget", "CAWOSCHED_PRESSURE")
        doc["carbonMetrics"] = {
            "totalCarbonGrams": 0.0,
            "meanVmCarbonGrams": 0.0,
            "carbonMakespanProductGramSeconds": 0.0,
            "perVmCarbonGrams": {},
            "totalCarbonExcessWattSeconds": 800.0,
            "windowExcessWattSeconds": {"0": 650.0, "1": 150.0},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "cawosched-pressure-report")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))

    def test_non_cawosched_keeps_first_vm_and_makespan(self):
        """Legacy: idle 30*3.21 + first-VM dynamic 100*(1+2) = 396.3 W*s."""
        bundle, doc = self.make_flat_budget("ordinary-budget", "SHARED_STORAGE_HEFT")
        doc["carbonMetrics"] = {
            "totalCarbonGrams": 0.0,
            "meanVmCarbonGrams": 0.0,
            "carbonMakespanProductGramSeconds": 0.0,
            "perVmCarbonGrams": {},
            "totalCarbonExcessWattSeconds": 396.3,
            "windowExcessWattSeconds": {"0": 396.3},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "ordinary-budget-report")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))


class DvfsTierAuditTests(unittest.TestCase):
    """BAT-D3 (2026-10-29): DVFS per-tier energy/carbon recompute self-test -- expected values come only
    from the hand-derived extended conservation law
    (DVFS_DIMENSION_DESIGN.md §2.3: E_vm = P_idle(default tier)*T_sim + sum over tiers
    [(P_max(tier)-P_idle(default tier))*busy(tier)]) plus the per-tier fleet power excess integral; the
    hand-derived numbers sit in comments next to the assertions, never the implementation output.
    The positive case passes, per-tier attribution/energy/tier-table tampering is killed one by one, and the
    v1 inert semantic bit is unchanged (tierless platform + flat power -> D1 energy law passes at its original value)."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def audit_status(self, manifest_path, report_name):
        """Run the independent audit CLI once and return the report dict."""
        report = Path(self.temp.name) / (report_name + ".json")
        evidence.main(["--output", str(report), str(manifest_path)])
        return json.loads(report.read_text(encoding="utf-8"))

    # -- fixture: 2-tier platform (2000 default tier 100/400W; 1000 tier 90/200W), VM base speed 1000 --
    # The compute Job (1000 MI, base speed 1000 -> 1.0s) rides the 1000 tier (== base speed -> the time
    # surface is bit-for-bit isomorphic to make_bundle, with the tier evidence surface layered on top); the
    # stage-in bookkeeping envelope (0.11s) carries no tier -> attributed to the default tier.
    TWO_TIERS = [{"mips": 2000.0, "idlePowerWatts": 100.0, "maxPowerWatts": 400.0},
                 {"mips": 1000.0, "idlePowerWatts": 90.0, "maxPowerWatts": 200.0}]

    def make_tier_manifest(self, name="dvfs"):
        """Synthetic manifest path with a tiered platform; the caller writes energyMetrics and friends as needed."""
        root = self.root / name
        make_bundle(root, 1.0, 1000, False)
        doc = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in self.TWO_TIERS]
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 1000.0  # non-default tier (the default tier is the first table entry, 2000)
        return root / "run.manifest.json", doc

    def store_energy(self, doc, total):
        """Hand-derived quadruple: single-VM total energy total -> mean/peak/EDP computed from the same source."""
        end = float(doc["metrics"]["makespanSeconds"])
        doc["energyMetrics"] = {
            "totalEnergyJoules": total,
            "meanVmEnergyJoules": total,  # single-VM mean = total
            "peakPowerWatts": 400.0,      # max declared tier P_max (the default is also the maximum in these fixtures)
            "energyMakespanProductJouleSeconds": total * end,
            "vmEnergyJoules": {"0": total},
        }

    def test_nonmonotonic_tier_peak_uses_max_declared_tier(self):
        """The default tier is 400 W but the occupied non-default tier is 900 W: nameplate peak is 900 W.

        The same hand-conservation partition as the positive two-tier case gives
        E = 100*1.21 + (400-100)*0.11 + (900-100)*1.0 = 121+33+800 = 954 J.
        A default-only 400 W peak is neither the declared maximum nor an upper bound on active power.
        """
        bundle, doc = self.make_tier_manifest("dvfs-nonmonotonic-peak")
        doc["platform"]["vms"][0]["frequencyTiers"][1]["maxPowerWatts"] = 900.0
        self.store_energy(doc, 954.0)
        doc["energyMetrics"]["peakPowerWatts"] = 900.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-nonmonotonic-peak")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))

    def test_tiered_energy_recompute_matches_hand_law(self):
        """Positive case: E = 100*1.21 + 0.11*(400-100) + 1.0*(200-100) = 121+33+100 = 254 J.

        Per-tier attribution: stage-in bookkeeping [0.1,0.21) has no tier -> default tier for 0.11s; the
        compute Job [0.21,1.21) carries the 1000 tier for 1.0s (a non-default tier -- the D1 all-at-default
        view gives 121+300*1.11 = 454 J, and the two must be told apart: this case stores 254 and passes,
        while the note records the honest observed value 454.)
        """
        bundle, doc = self.make_tier_manifest("dvfs-good")
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-good")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))
        note = next((n for n in report["notes"] if n["code"] == "dvfs-tier-accounting-observation"), None)
        self.assertIsNotNone(note, "the DVFS observation note must exist")
        # The observed values are not assertion inputs: the hand-derived values carry a floating tolerance pair (last-digit noise at the 453.99999999999994 level is legal).
        self.assertAlmostEqual(254.0, note["perTierTotalEnergyJoules"], delta=1e-9)
        self.assertAlmostEqual(454.0, note["allAtDefaultEnergyJoules"], delta=1e-9)
        self.assertTrue(note["nonDefaultTierBusyObserved"])

    def test_tiered_energy_discriminates_all_at_default_forgery(self):
        """Counterexample 1 (convention discrimination): the manifest records the D1 all-at-default value 454 J
        while the real per-tier attribution is still present -> killed.

        If the auditor fell back to the D1 convention (or the v1 flat-power convention of 0 J), 454 must not
        be judged PASS -- paired with the previous case (254 passes), the only recomputation law that satisfies
        both is the per-tier extension.
        """
        bundle, doc = self.make_tier_manifest("dvfs-454")
        self.store_energy(doc, 454.0)  # forged: energy declared under the "all-at-default" convention while the attribution evidence is split
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-454")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in report["issues"]),
                        "forged all-at-default energy must be caught: " + json.dumps(report["issues"][:2]))

    def test_tier_attribution_corruption_is_killed(self):
        """Counterexample 2 ("change one tier's busy seconds"): the compute Job's tier attribution is moved to
        the default tier -> tier busy times reshuffle (1000 tier 0s / default tier 1.11s) -> recomputed energy
        454 != stored 254 -> killed."""
        bundle, doc = self.make_tier_manifest("dvfs-retier")
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 2000.0  # tampered attribution (still a declared tier -- tier identity legal, energy evidence broken)
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-retier")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in report["issues"]),
                        "re-tiered attribution must break the conserved energy: "
                        + json.dumps(report["issues"][:2]))

    def test_undeclared_tier_mips_is_chain_broken(self):
        """Counterexample 3: tier mips=1500 is not in the declared table -> the evidence-chain clause mirroring Java tierByMips kills it."""
        bundle, doc = self.make_tier_manifest("dvfs-undeclared")
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 1500.0
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-undeclared")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("frequency tier" in str(i) or "tier evidence" in str(i) for i in report["issues"]),
                        "undeclared tier mips must be rejected citing the chain clause: "
                        + json.dumps(report["issues"][:2]))

    def test_tier_table_ordering_is_validated(self):
        """Counterexample 4: the tier table is not strictly descending (1000 before 2000) -> the table-order contract (BAT-D1) kills it."""
        bundle, doc = self.make_tier_manifest("dvfs-unsorted")
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in reversed(self.TWO_TIERS)]
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-unsorted")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("descending" in str(i) for i in report["issues"]),
                        "non-descending tier table must be rejected: " + json.dumps(report["issues"][:2]))

    # -- BAT-D6 (2026-10-29): expected task-window seconds = work/assigned tier speed (the Java/Python shared-law seam) --

    THREE_TIERS = [
        {"mips": 2000.0, "idlePowerWatts": 100.0, "maxPowerWatts": 400.0},
        {"mips": 1000.0, "idlePowerWatts": 90.0, "maxPowerWatts": 200.0},
        {"mips": 500.0, "idlePowerWatts": 85.0, "maxPowerWatts": 150.0},
    ]

    def test_tier_speed_drives_task_window_recompute(self):
        """Positive case: a 1000 MI task on the 500 tier -> expected window = 1000/500 = 2.0s (not the VM base
        speed's 1.0s).

        Hand-derived (make_bundle(duration=2.0)): task/Job window [0.21, 2.21), end=2.21,
        busy=0.11+2.0=2.11; per-tier energy
        E = 100*2.21 + (400-100)*0.11 (bookkeeping, default tier) + (150-100)*2.0 (compute, 500 tier)
          = 221 + 33 + 100 = 354 J; the all-at-default observed value = 221 + 300*2.11 = 854 J.
        """
        root = self.root / "tier-speed"
        make_bundle(root, 2.0, 1000)
        doc = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in self.THREE_TIERS]
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 500.0
        self.store_energy(doc, 354.0)
        bundle = root / "run.manifest.json"
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-speed-good")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))
        note = next((n for n in report["notes"] if n["code"] == "dvfs-tier-accounting-observation"), None)
        self.assertIsNotNone(note, "the DVFS observation note must exist")
        self.assertAlmostEqual(354.0, note["perTierTotalEnergyJoules"], delta=1e-9)
        self.assertAlmostEqual(854.0, note["allAtDefaultEnergyJoules"], delta=1e-9)
        self.assertTrue(note["nonDefaultTierBusyObserved"])

    def test_v1_speed_task_window_with_slow_tier_claim_is_killed(self):
        """Counterexample 5 (seam kill): the duration still follows the VM base speed (1.0s) while the manifest
        claims the 500 tier -> the expected 2.0s does not match.

        The old convention (task windows always at the VM declared speed) "passes" on this manifest; the
        tier-speed convention must kill it item by item through effective-task-window / cpu-lower-bound --
        this is the regression pin for the BAT-D6 Java/Python shared-law seam
        (WorkflowDatacenter.updateTaskExecTime takes the assigned tier speed). The energy is filled in under
        the per-tier law (hand-derived: 100*1.21 + 300*0.11 + 50*1.0 = 204 J), so the failure must come from
        the time window, not from energy noise.
        """
        root = self.root / "tier-speed-stale"
        make_bundle(root, 1.0, 1000)
        doc = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in self.THREE_TIERS]
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 500.0
        self.store_energy(doc, 204.0)
        bundle = root / "run.manifest.json"
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-speed-stale")
        self.assertEqual("FAIL", report["status"])
        codes = {i["code"] for i in report["issues"]}
        self.assertIn("effective-task-window", codes, "duration/tier-speed mismatch must hit the window recompute: "
                        + json.dumps(report["issues"][:3]))
        self.assertIn("cpu-lower-bound", codes, "the Job envelope must not be smaller than the tier-speed work: "
                        + json.dumps(report["issues"][:3]))

    def test_tiered_carbon_budget_uses_per_tier_fleet_power(self):
        """Budget model with per-tier fleet power: hand-derived timeline (compute Job [0.21,1.21) @1000 tier).

        P(t) = P_idle(default tier)=100 (static) + 100 (1000 tier dyn=200-100) = 200W on
        [0.21,1.21); window [0,1.5) green 150W -> excess (200-150)*1.0 = 50 W*s; window
        [2,5) with the same green -> 0 outside the interval; total 50 = window sub-map {"0":50}. Reusing the v1 uniform
        deltaP (tiered VM flat power=0 -> deltaP=0 -> 0W throughout) would make the expected excess always
        0 != 50 -- this case passing at 50 is the sole ruling that the per-tier convention is in force
        (tier-ifying the Java-side budget model is a later batch; the D3 forward convention is
        DVFS_DIMENSION_DESIGN.md §1.5).
        """
        bundle, doc = self.make_tier_manifest("dvfs-budget")
        doc["platform"]["carbonWindows"] = [
            {"startSeconds": 0.0, "endSeconds": 1.5, "greenWatts": 150.0},
            {"startSeconds": 2.0, "endSeconds": 5.0, "greenWatts": 150.0},
        ]
        self.store_energy(doc, 254.0)
        doc["carbonMetrics"] = {
            "totalCarbonGrams": 0.0, "meanVmCarbonGrams": 0.0,
            "carbonMakespanProductGramSeconds": 0.0, "perVmCarbonGrams": {},
            "totalCarbonExcessWattSeconds": 50.0, "windowExcessWattSeconds": {"0": 50.0},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        good = self.audit_status(bundle, "tier-budget-good")
        self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
        # Counterexample: total excess tampered by +1 -> killed
        doc["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 51.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        bad = self.audit_status(bundle, "tier-budget-bad")
        self.assertEqual("FAIL", bad["status"])
        self.assertTrue(any("totalCarbonExcessWattSeconds" in str(i.get("location", "")) for i in bad["issues"]),
                        "corrupted per-tier fleet excess must be caught: " + json.dumps(bad["issues"][:2]))

    def test_tiered_carbon_intensity_rides_per_tier_energy(self):
        """Intensity model ride-through: grams = E/3.6e6 * I (E = the per-tier law's 254 J, intensity 380 g/kWh).

        This case also pins the tier extension of the powered predicate (a tiered VM whose flat fields are
        all 0 would be misread as unpowered by the v1 predicate -> half-declaration kill; once the tier power
        gate is idempotent over (flat or tier) it passes under the dimensional law).
        """
        bundle, doc = self.make_tier_manifest("dvfs-intensity")
        doc["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
        self.store_energy(doc, 254.0)
        grams = 254.0 / 3.6e6 * 380.0  # unit law: J -> kWh divides by 3.6e6, then times g/kWh
        doc["carbonMetrics"] = {
            "totalCarbonGrams": grams, "meanVmCarbonGrams": grams,
            "carbonMakespanProductGramSeconds": grams * float(doc["metrics"]["makespanSeconds"]),
            "perVmCarbonGrams": {"0": grams},
            "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        good = self.audit_status(bundle, "tier-intensity-good")
        self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
        # Counterexample: tampered by +5g -> killed
        doc["carbonMetrics"]["totalCarbonGrams"] = grams + 5.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        bad = self.audit_status(bundle, "tier-intensity-bad")
        self.assertEqual("FAIL", bad["status"])
        self.assertTrue(any("totalCarbonGrams" in str(i.get("location", "")) for i in bad["issues"]),
                        "corrupted per-tier ridden grams must be caught: " + json.dumps(bad["issues"][:2]))

    def test_plain_v1_fixture_stays_on_the_v1_law(self):
        """v1 inert-bit anchor: the same fixture without frequencyTiers and with flat power 100/400 ->
        passes at the D1 energy law's original value (121+300*1.11=454 J); audit behavior is bit-for-bit
        identical to pre-D3."""
        root = self.root / "v1-plain"
        make_bundle(root)
        manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
        manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
        end = manifest["metrics"]["makespanSeconds"]
        busy = manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"]
        expected = 100.0 * end + 300.0 * busy  # = 121 + 333 = 454 J (hand-derived under the D1 law)
        manifest["energyMetrics"] = {
            "totalEnergyJoules": expected, "meanVmEnergyJoules": expected,
            "peakPowerWatts": 400.0, "energyMakespanProductJouleSeconds": expected * end,
            "vmEnergyJoules": {"0": expected},
        }
        (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        report = self.audit_status(root / "run.manifest.json", "v1-plain-good")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))
        # The v1 platform carries no DVFS observation note (the short-circuit bit).
        self.assertFalse([n for n in report["notes"] if n["code"] == "dvfs-tier-accounting-observation"],
                         str(report["notes"]))

    # -- BAT-V1/V3 (2026-10-29 test-engineer adversarial round / DVFS round five) -- extra manifest-surface anchors --

    def test_declared_but_empty_tier_list_is_killed(self):
        """BAT-V1 manifest surface: frequencyTiers declared as an empty table -> the BAT-D3 clause kills it
        (declared-but-empty; mirrors the workbench JSON surface's non-empty-array rejection -- on the
        programming surface a null/empty List is the v1 degradation of D1, and the manifest emitter never
        produces an empty table)."""
        bundle, doc = self.make_tier_manifest("dvfs-empty-tiers")
        self.store_energy(doc, 254.0)
        doc["platform"]["vms"][0]["frequencyTiers"] = []
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-empty")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("non-empty" in str(i) for i in report["issues"]),
                        "empty declared tier table must be rejected citing the clause: "
                        + json.dumps(report["issues"][:2]))

    def test_platform_default_tier_idle_corruption_is_killed(self):
        """BAT-V3(c) (the probe number from the real frozen bundle, ported to a synthetic regression anchor):
        the platform's first tier (the default tier) has idlePowerWatts 100->110 (a 10W idle inflation) ->
        the conservation law's idle coefficient is tampered with -> the recomputed energy mismatches across
        the whole family -> killed. Hand-derived: E = 110*1.21 + (400-110)*0.11 + (200-110)*1.0
        = 133.1+31.9+90 = 255 J != stored 254 J."""
        bundle, doc = self.make_tier_manifest("dvfs-idle-corrupt")
        self.store_energy(doc, 254.0)
        doc["platform"]["vms"][0]["frequencyTiers"][0]["idlePowerWatts"] = 110.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-idle-corrupt")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in report["issues"]),
                        "corrupted default-tier idle must break the conserved energy: "
                        + json.dumps(report["issues"][:2]))

    def test_law_inert_nondefault_tier_corruption_is_honest_observation(self):
        """BAT-V3 honest boundary (a guard-seam disclosure, not a fix): change a non-default tier's
        idlePowerWatts that has <b>no busy-second attribution</b> (in-domain legal: <=max) -> the per-tier
        conservation law consumes only the default tier's idle and each attributed tier's max; there is no
        second source of truth to compare against -> audit behavior is unchanged. This is the inherent
        boundary of single-source evidence: any constant inside the bundle is self-consistent with the
        platform section (the frozen tar's manifest hash is the archive-level tamper surface); pinning this
        behavior keeps future regressions from raising false alarms (so that "coordination
        self-consistency" is not mistaken for forgery)."""
        bundle, doc = self.make_tier_manifest("dvfs-inert-corrupt")
        self.store_energy(doc, 254.0)
        # The 1000 tier (attributed 1.0s) stays untouched; the 2000 tier is the default tier -- both are
        # within the law. This fixture has only two tiers, so only changing the max of a non-default
        # attributed tier would kill it; constructing "no attribution + non-default" needs a third tier:
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in self.THREE_TIERS]
        doc["platform"]["vms"][0]["frequencyTiers"][2]["idlePowerWatts"] = 85.5  # 500 tier: no attribution
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-inert-corrupt")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
