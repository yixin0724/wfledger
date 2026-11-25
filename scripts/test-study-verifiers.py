#!/usr/bin/env python3

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
        # FGCS-R6 (2026-11-25): the single compute Job runs `duration` <= 10 s, so it is clamped by the
        # default floor (1 of 1 samples); no JOB_EXECUTION_STARTED event, so no read decomposition sample.
        "boundedSlowdownThresholdSeconds": 10.0, "boundedSlowdownFloorClampedObservationCount": 1,
        "boundedSlowdownFloorClampedFraction": 1.0, "totalInputReadSecondsInEnvelope": 0.0,
        "totalComputeOnlyBusySeconds": busy, "inputReadShareOfBusyTime": 0.0, "inputReadInEnvelopeObservationCount": 0,
    }
    metrics["vmMetrics"]["0"].update(inputReadSecondsInEnvelope=0.0, computeOnlyBusySeconds=busy)
    config = {"executionSemantics": EXECUTION_SEMANTICS, "vmCount": 1, "rootSeed": 9007199254740993,
              "workflowPaths": [str(source)], "workflowArrivalSeconds": [0.0], "runtimeScale": 1.0, "runtimeReferenceMips": 1000.0,
              "clustering": {"method": "NONE"}, "costModel": "DATACENTER", "taskCostMatrix": None,
              "boundedSlowdownThresholdSeconds": 10.0}
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
    task["finishTime"] = honest_end
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
             totalComputeOnlyBusySeconds=busy,
             exactTaskTimingObservationCount=1 if lost_cpu_seconds == 0.0 else 0,
             modeledApproximateTaskTimingObservationCount=0 if lost_cpu_seconds == 0.0 else 1)
    m["vmMetrics"]["0"].update(reportedCpuTimeSeconds=reported_cpu, modeledBusyIntervalSeconds=busy,
                                 modeledIntervalUtilization=busy / honest_end,
                                 reportedCpuTimeOverMakespan=reported_cpu / honest_end,
                                 computeOnlyBusySeconds=busy)
    bind_sidecars(manifest, doc)
    return manifest

def make_statistics_study(root, protocol="peft-comparison-r12-v3"):

    root.mkdir(parents=True)
    _, _, plan, _ = registered_plan(protocol, "smoke")
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
                          "workflowPaths": [workflow["path"]], "executionSemantics": plan["executionSemantics"],
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

    def test_failed_attempt_window_is_the_executed_compute_portion(self):
        # FGCS-R5 (2026-11-24): hand-derived from the mid-execution failure contract. A 2 s compute window
        # [0.21, 2.21) failed by the arrival 2.1 executes 1.89 s; the envelope must cover only those 1.89 s.
        verdict = evidence.task_window_verdict
        self.assertEqual((True, 2.0), verdict(False, 0.21, 2.21, 2.0, 1e-9))
        ok, covered = verdict(True, 0.21, 2.1, 2.0, 1e-9)
        self.assertTrue(ok)
        self.assertAlmostEqual(1.89, covered, delta=1e-12)
        self.assertTrue(verdict(True, 0.21, 0.21, 2.0, 1e-9)[0], "a failure at compute start executes nothing")
        self.assertFalse(verdict(True, 0.21, 2.21, 2.0, 1e-9)[0], "an arrival at the window end is not a failure")
        self.assertFalse(verdict(True, 0.21, 2.5, 2.0, 1e-9)[0], "a failed window cannot outlast its compute")
        self.assertFalse(verdict(False, 0.21, 2.1, 2.0, 1e-9)[0], "a successful window must be complete")

    def test_java_round_ties_and_near_ties(self):
        self.assertEqual(0, java_round(0.49999999999999994))
        self.assertEqual(1, java_round(0.5))
        self.assertEqual(0, java_round(-0.5))
        self.assertEqual(2**52 + 1, java_round(float(2**52 + 1)))

    def test_independent_registration_counts_and_frozen_synthetic_hashes(self):
        # FGCS-R3 (2026-11-24): current V3 identities, V2-era historical identities, and the oldest identities.
        registry = (("network-limited-r10-v4", False, "WORK_CONSERVING_TASK_EXECUTION_V3", (504, 36)),
                    ("network-limited-r10-v3", True, "WORK_CONSERVING_TASK_EXECUTION_V2", (504, 36)),
                    ("network-limited-r10-v2", True, None, (504, 36)),
                    ("peft-comparison-r12-v3", False, "WORK_CONSERVING_TASK_EXECUTION_V3", (126, 18)),
                    ("peft-comparison-r12-v2", True, "WORK_CONSERVING_TASK_EXECUTION_V2", (126, 18)),
                    ("peft-comparison-r12-v1", True, None, (126, 18)),
                    ("sensitivity-response-r13-v3", False, "WORK_CONSERVING_TASK_EXECUTION_V3", (546, 30)),
                    ("sensitivity-response-r13-v2", True, "WORK_CONSERVING_TASK_EXECUTION_V2", (546, 30)),
                    ("sensitivity-response-r13-v1", True, None, (546, 30)))
        self.assertEqual("WORK_CONSERVING_TASK_EXECUTION_V3", EXECUTION_SEMANTICS)
        for protocol, historical, semantics, counts in registry:
            for mode, count in zip(("full", "smoke"), counts):
                _, old, plan, cells = registered_plan(protocol, mode)
                self.assertEqual(count, len(cells))
                self.assertEqual(historical, old, protocol)
                self.assertEqual(semantics, plan.get("executionSemantics"), protocol)
        self.assertEqual("2a7a23bed50abc515523036ea220f292be0a8196a554e8dafeb2e8aba2407ee6", hashlib.sha256(layered_xml(8).encode()).hexdigest())
        self.assertEqual("342347cfb5cb139b52d903a611cb84ff205078250d3bdb754152cfb239af97e2", hashlib.sha256(layered_xml(32).encode()).hexdigest())

    def test_std_v1_fractional_shared_rate_reference_matches_hand_derivation(self):
        # STD-V1 (2026-11-25): the SHARED rate is a finite positive double. Hand derivation mirrors the Java test:
        # 25e6 B / 1e6 / 2.5 MB/s = 10 s; (2000 + floor(1000 * 10)) / 1000 = 12 s; stage-in release
        # max(0.11, 0.1 + 0.01) + 0.1 = 0.21 s; lower bound 12.21 s. An integer rate (historical evidence) keeps working.
        config = {"dataMovementModel": {"kind": "LEGACY_WORKFLOWSIM_V1"}, "clustering": {"method": "NONE"},
                  "failureModel": {"generatorMode": "FAILURE_NONE"}, "fileSystem": "SHARED",
                  "overheadModel": {"workflowEngineDelayInterval": 0, "bandwidth": 0.0}, "cloudSimMinEventIntervalSeconds": 0.1}
        tasks = {1: {"parents": [], "children": [], "lengthMi": 2000, "files": [("external", "input", 25000000.0)]}}
        for rate, expected in ((2.5, 12.21), (25, 3.21)):  # 25 MB/s: 1 s read, (2000 + 1000) / 1000 = 3 s
            platform = {"storage": {"maxTransferRateMbPerSecond": rate},
                        "vms": [{"id": 0, "mips": 1000.0, "schedulerMode": "SPACE_SHARED"}]}
            reference = evidence.critical_path_reference(config, platform, tasks)
            self.assertTrue(reference["available"], rate)
            self.assertAlmostEqual(expected, reference["lowerBound"], places=9)

    def test_exact_sign_and_holm(self):
        self.assertEqual(Fraction(1, 32), statistics.exact_sign_test(6, 0))
        self.assertEqual(Fraction(1), statistics.exact_sign_test(3, 3))
        self.assertIsNone(statistics.exact_sign_test(0, 0))
        rows = [{"pExact": p} for p in (Fraction(2, 100), Fraction(1, 100), Fraction(4, 100))]
        statistics.holm(rows)
        self.assertEqual([Fraction(4, 100), Fraction(3, 100), Fraction(4, 100)], [r["holmExact"] for r in rows])

    def test_deadline_family_recompute_matches_java_semantics(self):

        manifest = make_bundle(self.root / "deadline", 1.0, 1000, False)
        doc = json.loads(manifest.read_text())
        doc["configuration"]["deadline"] = 200
        m = doc["metrics"]
        m.update(deadlineObservationEnabled=True, deadlineObservationOutcome="MET",
                 deadlineObservationTimeBasis="SIMULATION_END_SECONDS", deadlineMet=True,
                 deadlineSlackSeconds=200 - m["makespanSeconds"], deadlineTardinessSeconds=0.0)
        bind_sidecars(manifest, doc)
        audit = Audit()
        result = evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())

        manifest2 = make_bundle(self.root / "deadline-late", 1.0, 1000, False)
        doc2 = json.loads(manifest2.read_text())
        doc2["configuration"]["deadline"] = 1
        m2 = doc2["metrics"]
        slack2 = 1 - m2["makespanSeconds"]
        m2.update(deadlineObservationEnabled=True, deadlineObservationOutcome="MISSED_LATE",
                  deadlineObservationTimeBasis="SIMULATION_END_SECONDS", deadlineMet=False,
                  deadlineSlackSeconds=slack2, deadlineTardinessSeconds=-slack2)
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

        end = float(doc["result"]["simulationEndSeconds"])
        events.append({"sequence": len(events), "simulationTime": end, "type": "SCHEDULING_CYCLE",
                       "jobId": None, "taskIds": [], "attributes": {"decisionElapsedNanos": 250.75}})

        events.append({"sequence": len(events), "simulationTime": end, "type": "SCHEDULING_CYCLE",
                       "jobId": None, "taskIds": [], "attributes": {"decisionElapsedNanos": "not-a-number"}})

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

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "powered"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))

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

            manifest["energyMetrics"]["totalEnergyJoules"] = expected_total + 500.0
            (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            bad_report = Path(tmp) / "energy-bad.json"
            evidence.main(["--output", str(bad_report), str(root / "run.manifest.json")])
            bad = json.loads(bad_report.read_text(encoding="utf-8"))
            self.assertEqual(bad["status"], "FAIL")
            self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in bad["issues"]),
                            "corrupted total energy must be caught: " + json.dumps(bad["issues"][:2]))

    def test_mixed_powered_and_unpowered_fleet_is_rejected(self):
        # FGCS-R3 (2026-11-24, A5): all-or-none power declaration, mirrored from
        # EnergyAccounting.requireAllOrNonePowered; a second unpowered VM next to a powered one
        # must fail instead of contributing a silent 0 J.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "mixed"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            manifest["platform"]["vms"].append({"id": 1, "mips": 1000.0, "pes": 1, "schedulerMode": "SPACE_SHARED"})
            (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            report_path = Path(tmp) / "mixed-report.json"
            evidence.main(["--output", str(report_path), str(root / "run.manifest.json")])
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual("FAIL", report["status"])
            self.assertIn("every VM or on none", json.dumps(report["issues"]))

    def test_event_stream_order_sequence_and_blank_lines_are_killed(self):

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

    def rewrite_compute_events(self, name, mutate, **metric_updates):
        manifest = make_bundle(self.root / name)
        doc = json.loads(manifest.read_text())
        events_path = manifest.parent / "run.events.jsonl"
        events = [json.loads(line) for line in events_path.read_text().splitlines() if line.strip()]
        events = mutate(events)
        for index, event in enumerate(events):
            event["sequence"] = index
        events_path.write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
        doc["events"]["eventCount"] = len(events)
        doc["metrics"].update(metric_updates)
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        return audit

    def test_waiting_sample_does_not_require_a_scheduling_decision(self):
        # FGCS-R3 (2026-11-24): METRICS_CARD/CONTRACTS define the waiting and bounded-slowdown
        # sample by JOB_READY plus start >= ready only; SimulationMetrics does not require
        # SCHEDULING_DECISION or JOB_RETURNED. Dropping the compute Job's decision keeps one
        # waiting/slowdown observation (wait = 0.21 - 0.21 = 0) and removes both decision
        # split samples, so their counts become 0 by the same contract.
        audit = self.rewrite_compute_events(
            "no-decision",
            lambda events: [e for e in events if not (e["type"] == "SCHEDULING_DECISION" and e["jobId"] == 0)],
            readyToDecisionObservationCount=0, decisionToStartObservationCount=0)
        # The stream-completeness layer still reports the missing decision; only the metric
        # recomputation must agree with SimulationMetrics.
        self.assertEqual(0, audit.counts.get("metric-mismatch", 0), audit.report())
        self.assertEqual(1, audit.counts.get("missing-SCHEDULING_DECISION", 0), audit.report())

    def test_out_of_order_ready_is_excluded_not_clamped_to_zero(self):
        # FGCS-R3 (2026-11-24): a compute Job whose JOB_READY (0.5) follows its start (0.21) is
        # an out-of-order observation; the contract excludes it instead of substituting a zero
        # wait. The waiting/slowdown/success samples are therefore empty (count 0, API mean 0.0),
        # ready->decision is excluded (decision 0.21 < ready 0.5), and decision->start keeps
        # its one sample (0.21 - 0.21 = 0).
        def late_ready(events):
            ready = next(e for e in events if e["type"] == "JOB_READY" and e["jobId"] == 0)
            events.remove(ready)
            ready["simulationTime"] = 0.5
            index = next(i for i, e in enumerate(events) if e["simulationTime"] > 0.5)
            events.insert(index, ready)
            return events
        audit = self.rewrite_compute_events(
            "late-ready", late_ready,
            totalWaitingTimeObservationCount=0, trueSlowdownObservationCount=0,
            successOnlyWaitingObservationCount=0, readyToDecisionObservationCount=0,
            meanComputeTrueSlowdown=0.0, successOnlyMeanComputeTrueSlowdown=0.0,
            medianComputeTrueSlowdown=0.0, p95ComputeTrueSlowdown=0.0, maxComputeTrueSlowdown=0.0,
            boundedSlowdownFloorClampedObservationCount=0, boundedSlowdownFloorClampedFraction=0.0)
        self.assertEqual(0, audit.counts.get("metric-mismatch", 0), audit.report())
        self.assertEqual(1, audit.counts.get("ready-decision-start", 0), audit.report())

    def test_r6_bounded_slowdown_uses_the_declared_floor(self):
        # FGCS-R6 (2026-11-25): the fixture's compute Job has w = 0 and e = 1.0 s. Under tau = 10 the sample
        # is clamped (e < tau, count 1); under tau = 0.1 it is not (count 0). The slowdown is 1.0 either way;
        # declaring the other floor's clamp count is a metric mismatch.
        for name, tau, clamped in (("tau10", 10.0, 1), ("tau01", 0.1, 0)):
            manifest = make_bundle(self.root / name)
            doc = json.loads(manifest.read_text())
            doc["configuration"]["boundedSlowdownThresholdSeconds"] = tau
            doc["metrics"].update(boundedSlowdownThresholdSeconds=tau,
                                  boundedSlowdownFloorClampedObservationCount=clamped,
                                  boundedSlowdownFloorClampedFraction=float(clamped))
            bind_sidecars(manifest, doc)
            audit = Audit()
            evidence.inspect_manifest(manifest, audit)
            self.assertEqual(0, audit.issue_count, audit.report())
            doc["metrics"]["boundedSlowdownFloorClampedObservationCount"] = 1 - clamped
            bind_sidecars(manifest, doc)
            audit = Audit()
            evidence.inspect_manifest(manifest, audit)
            self.assertGreater(audit.counts.get("metric-mismatch", 0), 0, audit.report())

    def test_r6_short_floor_changes_the_slowdown_of_a_waiting_short_job(self):
        # FGCS-R6 (2026-11-25): hand derivation with a genuine wait. The stage-in finishes at 0.21; the compute
        # Job is ready at 0.21 but starts at 0.41 (w = 0.2) and runs 1.0 s to 1.41. tau = 10 gives
        # max(1.2 / 10, 1) = 1.0 (clamped); tau = 0.1 gives 1.2 / 1.0 = 1.2 (not clamped).
        for name, tau, expected, clamped in (("wait10", 10.0, 1.0, 1), ("wait01", 0.1, 1.2, 0)):
            manifest = make_bundle(self.root / name)
            doc = json.loads(manifest.read_text())
            shift = 0.2
            for job in doc["result"]["jobs"]:
                if job["classType"] == 2:
                    # Idle-only dispatch: the VM submission happens at the dispatch instant, so the
                    # submission time moves with the start and the VM-queue wait stays 0.
                    job["submissionTime"] += shift; job["startTime"] += shift; job["finishTime"] += shift
            task = doc["result"]["tasks"][0]
            task["startTime"] += shift; task["finishTime"] += shift
            end = 1.41
            doc["result"]["simulationEndSeconds"] = end
            events_path = manifest.parent / "run.events.jsonl"
            events = [json.loads(line) for line in events_path.read_text().splitlines() if line.strip()]
            for event in events:
                if event["jobId"] == 0 and event["type"] in ("DATA_STAGE_IN_MODELED", "TASK_EXECUTION_MODELED", "JOB_RETURNED"):
                    event["simulationTime"] += shift
                if event["type"] == "TASK_EXECUTION_MODELED":
                    event["attributes"]["taskStartTime"] += shift
                    event["attributes"]["taskFinishTime"] += shift
                if event["type"] == "SCHEDULING_DECISION" and event["jobId"] == 0:
                    event["simulationTime"] += shift
                if event["type"] == "SCHEDULING_CYCLE" and event["simulationTime"] == 0.21:
                    event["simulationTime"] += shift
            events.sort(key=lambda e: (e["simulationTime"], e["sequence"]))
            for index, event in enumerate(events):
                event["sequence"] = index
            events_path.write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
            busy = 0.11 + 1.0
            doc["configuration"]["boundedSlowdownThresholdSeconds"] = tau
            doc["metrics"].update(
                makespanSeconds=end, logicalTaskCompletionSeconds=end, computeJobOutcomeThroughputPerSecond=1.0 / end,
                meanJobVmQueueWaitingTimeSeconds=0.0, meanJobResponseTimeSeconds=1.0, meanJobVmLevelSlowdown=1.0,
                meanComputeTotalWaitingTimeSeconds=shift, medianComputeTotalWaitingTimeSeconds=shift,
                p95ComputeTotalWaitingTimeSeconds=shift, maxComputeTotalWaitingTimeSeconds=shift,
                successOnlyMeanComputeTotalWaitingTimeSeconds=shift,
                meanComputeReadyToDecisionDelaySeconds=shift,
                meanComputeTrueSlowdown=expected, medianComputeTrueSlowdown=expected, p95ComputeTrueSlowdown=expected,
                maxComputeTrueSlowdown=expected, successOnlyMeanComputeTrueSlowdown=expected,
                boundedSlowdownThresholdSeconds=tau, boundedSlowdownFloorClampedObservationCount=clamped,
                boundedSlowdownFloorClampedFraction=float(clamped),
                meanVmModeledIntervalUtilization=busy / end)
            doc["metrics"]["vmMetrics"]["0"].update(modeledIntervalUtilization=busy / end,
                                                     reportedCpuTimeOverMakespan=busy / end)
            bind_sidecars(manifest, doc)
            audit = Audit()
            evidence.inspect_manifest(manifest, audit)
            self.assertEqual(0, audit.counts.get("metric-mismatch", 0), audit.report())

    def test_r6_current_identity_requires_the_declared_floor(self):
        # FGCS-R6 (2026-11-25): current-identity evidence must declare the floor; a missing declaration aborts
        # instead of silently falling back to 10 s.
        manifest = make_bundle(self.root / "undeclared-floor")
        doc = json.loads(manifest.read_text())
        del doc["configuration"]["boundedSlowdownThresholdSeconds"]
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("invalid-or-unsupported-bundle", 0), 0, audit.report())

    def test_r6_envelope_read_decomposition_is_recomputed_from_start_events(self):
        # FGCS-R6 (2026-11-25): busy = 0.11 (stage-in) + 1.0 (compute) = 1.11 s. A JOB_EXECUTION_STARTED that
        # declares 0.4 s of effective staging for the compute Job gives read = 0.4, compute-only = 0.71 and an
        # I/O share of 0.4 / 1.11; a forged read total is a metric mismatch.
        manifest = inject_contract_events(make_bundle(self.root / "read-share"))
        doc = json.loads(manifest.read_text())
        events_path = manifest.parent / "run.events.jsonl"
        events = [json.loads(line) for line in events_path.read_text().splitlines() if line.strip()]
        for event in events:
            if event["type"] == "JOB_EXECUTION_STARTED":
                event["attributes"].update(effectiveDataStageSeconds=0.4, modeledJobStartSeconds=0.21 + 0.4)
        events_path.write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
        busy = 0.11 + 1.0
        doc["metrics"].update(totalInputReadSecondsInEnvelope=0.4, totalComputeOnlyBusySeconds=busy - 0.4,
                              inputReadShareOfBusyTime=0.4 / busy)
        doc["metrics"]["vmMetrics"]["0"].update(inputReadSecondsInEnvelope=0.4, computeOnlyBusySeconds=busy - 0.4)
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.counts.get("metric-mismatch", 0), audit.report())
        doc["metrics"]["totalInputReadSecondsInEnvelope"] = 0.0
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("metric-mismatch", 0), 0, audit.report())

    def test_r6_bandwidth_bills_real_inputs_once_per_transfer(self):
        # FGCS-R6 (2026-11-25): mirror Task.getModeledDeclaredFileBytes. The fixture Task reads the external x
        # (0.5 B); add an output o (0.25 B) and an in-place file u declared as both input and output (0.125 B).
        # Billed bytes = real inputs only = x = 0.5 (o is produced; u is not transferred in). The former
        # every-declared-file rule (0.5 + 0.25 + 0.125 + 0.125 = 1.0) is a raw-billable mismatch.
        for name, declared, ok in (("real-input", 0.5, True), ("all-files", 1.0, False)):
            manifest = make_bundle(self.root / name)
            doc = json.loads(manifest.read_text())
            source = Path(doc["inputs"][0]["path"])
            source.write_text('<adag version="2.1"><job id="root" name="root" runtime="1">'
                              '<uses file="x" link="input" size="0.5"/><uses file="o" link="output" size="0.25"/>'
                              '<uses file="u" link="input" size="0.125"/><uses file="u" link="output" size="0.125"/>'
                              '</job></adag>', encoding="utf-8")
            doc["inputs"][0]["sha256"], doc["inputs"][0]["sizeBytes"] = fingerprint(source)
            compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
            bw = 0.1 * declared / 1_000_000.0
            compute.update(modeledDeclaredFileBytes=declared, modeledDeclaredFileBandwidthCost=bw,
                           modeledProcessingCost=compute["modeledCpuEnvelopeCost"] + bw)
            stage = next(j for j in doc["result"]["jobs"] if j["classType"] == 1)
            doc["metrics"].update(
                totalModeledDeclaredFileBytes=0.5 + declared,
                totalModeledDeclaredFileBandwidthCost=stage["modeledDeclaredFileBandwidthCost"] + bw,
                totalModeledProcessingCost=stage["modeledProcessingCost"] + compute["modeledProcessingCost"])
            bind_sidecars(manifest, doc)
            audit = Audit()
            evidence.inspect_manifest(manifest, audit)
            self.assertEqual(0 if ok else 1, audit.counts.get("raw-billable-file-bytes", 0), audit.report())
            self.assertEqual(0, audit.counts.get("metric-mismatch", 0), audit.report())

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

    def test_statistics_bind_v2_era_protocol_only_in_historical_mode(self):
        # FGCS-R3 (2026-11-24): a retained V2-era study stays auditable read-only, but never as current evidence.
        source = make_statistics_study(self.root / "v2-era", "peft-comparison-r12-v2")
        audit = Audit()
        statistics.inspect_index(source, audit)
        self.assertGreater(audit.counts.get("historical-mode-required", 0), 0, audit.report())
        audit = Audit()
        report, _ = statistics.inspect_index(source, audit, historical=True)
        self.assertEqual(0, audit.issue_count, audit.report())
        self.assertEqual("WORK_CONSERVING_TASK_EXECUTION_V2", report["model"])
        index = json.loads(source.read_text())
        manifest = source.parent / index["runs"][0]["manifest"]
        doc = json.loads(manifest.read_text())
        doc["configuration"]["executionSemantics"] = EXECUTION_SEMANTICS
        write_json(manifest, doc)
        audit = Audit()
        statistics.inspect_index(source, audit, historical=True)
        self.assertGreater(audit.counts.get("fixed-condition", 0), 0, "V3 run cannot sit in a V2-era study")

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
    doc["metrics"]["inputReadInEnvelopeObservationCount"] = 1 + int(duplicate_start)
    bind_sidecars(manifest, doc)
    return manifest

class ContractEventAuditTests(unittest.TestCase):

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

    def test_v2_execution_identity_requires_historical_mode_and_unknown_identity_is_rejected(self):
        # FGCS-R3 (2026-11-24): V2 is historical read-only; an unregistered identity is never accepted.
        manifest = make_bundle(self.root / "v2-identity")
        doc = json.loads(manifest.read_text())
        doc["configuration"]["executionSemantics"] = "WORK_CONSERVING_TASK_EXECUTION_V2"
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("historical-mode-required", 0), 0, audit.report())
        audit = Audit()
        evidence.inspect_manifest(manifest, audit, historical=True)
        self.assertEqual(0, audit.counts.get("execution-model-identity", 0), audit.report())
        self.assertEqual(0, audit.counts.get("historical-mode-required", 0), audit.report())
        doc["configuration"]["executionSemantics"] = "WORK_CONSERVING_TASK_EXECUTION_V9"
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit, historical=True)
        self.assertGreater(audit.counts.get("execution-model-identity", 0), 0, audit.report())

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

        manifest = inject_contract_events(make_bundle(self.root / "declared-terminal"))
        rewrite_stream(manifest, {"SIMULATION_TERMINATED"}, declared="F-006/F-007")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("revision-contract-terminal", 0), 0, audit.report())

    def test_a_declared_contract_rejects_a_stream_with_every_marker_removed(self):

        manifest = inject_contract_events(make_bundle(self.root / "declared-markers"))
        rewrite_stream(manifest, {"SIMULATION_TERMINATED", "JOB_EXECUTION_STARTED"},
                       declared="F-006/F-007")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("revision-contract-terminal", 0), 0, audit.report())

    def test_an_undeclared_markerless_stream_keeps_the_legacy_boundary(self):

        manifest = make_bundle(self.root / "undeclared-legacy")
        rewrite_stream(manifest, {"SIMULATION_TERMINATED", "JOB_EXECUTION_STARTED"}, declared=None)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())

    def test_a_declared_contract_with_its_markers_intact_passes(self):

        manifest = inject_contract_events(make_bundle(self.root / "declared-healthy"))
        rewrite_stream(manifest, set(), declared="F-006/F-007")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())

    def test_an_unknown_declared_contract_is_rejected(self):

        manifest = inject_contract_events(make_bundle(self.root / "declared-unknown"))
        rewrite_stream(manifest, set(), declared="v0")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("revision-contract", 0), 0, audit.report())

def rewrite_stream(manifest, drop_types, declared="keep"):

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

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def cli(self, script, source, output):
        return subprocess.run([sys.executable, str(HERE / script), str(source), "--output", str(output)],
                              capture_output=True, text=True)

    def audit_status(self, manifest, report_name):

        report = Path(self.temp.name) / (report_name + ".json")
        evidence.main(["--output", str(report), str(manifest)])
        return json.loads(report.read_text(encoding="utf-8"))

    @staticmethod
    def elementary_energy(manifest):

        end = float(manifest["metrics"]["makespanSeconds"])
        busy = float(manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"])
        return 100.0 * end + 300.0 * busy, end, busy

    def test_carbon_intensity_recompute_and_corruption(self):

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "carbon-intensity"
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
            grams = energy / 3.6e6 * 380.0
            manifest["carbonMetrics"] = {
                "totalCarbonGrams": grams, "meanVmCarbonGrams": grams / 1,
                "carbonMakespanProductGramSeconds": grams * end, "perVmCarbonGrams": {"0": grams},
                "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
            }
            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            good = self.audit_status(bundle, "carbon-good")
            self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))

            manifest["carbonMetrics"]["totalCarbonGrams"] = grams + 5.0
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad = self.audit_status(bundle, "carbon-bad")
            self.assertEqual("FAIL", bad["status"])
            self.assertTrue(any("totalCarbonGrams" in str(i.get("location", "")) for i in bad["issues"]),
                            "corrupted total carbon must be caught: " + json.dumps(bad["issues"][:2]))

    def test_carbon_missing_when_intensities_declared(self):

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

            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            report = self.audit_status(bundle, "carbon-missing")
            self.assertEqual("FAIL", report["status"],
                             "intensity declarations without carbonMetrics must fail (BAT-C2 declared→required)")
            self.assertTrue(any("carbonMetrics" in str(i) for i in report["issues"]),
                            "failure must cite the carbonMetrics section: " + json.dumps(report["issues"][:3]))

    def test_carbon_budget_window_recompute_and_corruption(self):

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

            manifest["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 155.0
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad1 = self.audit_status(bundle, "budget-bad-total")
            self.assertEqual("FAIL", bad1["status"])
            self.assertTrue(any("totalCarbonExcessWattSeconds" in str(i.get("location", "")) for i in bad1["issues"]),
                            "corrupted total excess must be caught: " + json.dumps(bad1["issues"][:2]))

            manifest["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 150.0
            manifest["carbonMetrics"]["windowExcessWattSeconds"] = {"0": 100.0}
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad2 = self.audit_status(bundle, "budget-bad-window")
            self.assertEqual("FAIL", bad2["status"])
            self.assertTrue(any("windowExcess" in str(i) for i in bad2["issues"]),
                            "corrupted window sub-map must be caught: " + json.dumps(bad2["issues"][:2]))

            manifest["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
            manifest["carbonMetrics"]["windowExcessWattSeconds"] = {"0": 150.0}
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            both = self.audit_status(bundle, "budget-bad-both")
            self.assertEqual("FAIL", both["status"])
            self.assertTrue(any("mutually exclusive" in str(i) or "intensity" in str(i) for i in both["issues"]),
                            "dual declaration must be rejected citing the exclusion clause: "
                            + json.dumps(both["issues"][:2]))

    def test_carbon_windows_must_be_sorted_non_overlapping(self):

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
            # FGCS-R6 (2026-11-25): both compute Jobs (1 s, 2 s) are shorter than the 10 s floor.
            "boundedSlowdownFloorClampedObservationCount": 2,
            "totalComputeOnlyBusySeconds": 3.11,
        })
        doc["metrics"]["vmMetrics"]["0"].update({
            "computeOnlyBusySeconds": 1.11,
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
            "inputReadSecondsInEnvelope": 0.0,
            "computeOnlyBusySeconds": 2.0,
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

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def audit_status(self, manifest_path, report_name):

        report = Path(self.temp.name) / (report_name + ".json")
        evidence.main(["--output", str(report), str(manifest_path)])
        return json.loads(report.read_text(encoding="utf-8"))

    TWO_TIERS = [{"mips": 2000.0, "idlePowerWatts": 100.0, "maxPowerWatts": 400.0},
                 {"mips": 1000.0, "idlePowerWatts": 90.0, "maxPowerWatts": 200.0}]

    def make_tier_manifest(self, name="dvfs"):

        root = self.root / name
        make_bundle(root, 1.0, 1000, False)
        doc = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in self.TWO_TIERS]
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 1000.0
        return root / "run.manifest.json", doc

    def store_energy(self, doc, total):

        end = float(doc["metrics"]["makespanSeconds"])
        doc["energyMetrics"] = {
            "totalEnergyJoules": total,
            "meanVmEnergyJoules": total,
            "peakPowerWatts": 400.0,
            "energyMakespanProductJouleSeconds": total * end,
            "vmEnergyJoules": {"0": total},
        }

    def test_nonmonotonic_tier_peak_uses_max_declared_tier(self):

        bundle, doc = self.make_tier_manifest("dvfs-nonmonotonic-peak")
        doc["platform"]["vms"][0]["frequencyTiers"][1]["maxPowerWatts"] = 900.0
        self.store_energy(doc, 954.0)
        doc["energyMetrics"]["peakPowerWatts"] = 900.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-nonmonotonic-peak")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))

    def test_tiered_energy_recompute_matches_hand_law(self):

        bundle, doc = self.make_tier_manifest("dvfs-good")
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-good")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))
        note = next((n for n in report["notes"] if n["code"] == "dvfs-tier-accounting-observation"), None)
        self.assertIsNotNone(note, "the DVFS observation note must exist")

        self.assertAlmostEqual(254.0, note["perTierTotalEnergyJoules"], delta=1e-9)
        self.assertAlmostEqual(454.0, note["allAtDefaultEnergyJoules"], delta=1e-9)
        self.assertTrue(note["nonDefaultTierBusyObserved"])

    def test_tiered_energy_discriminates_all_at_default_forgery(self):

        bundle, doc = self.make_tier_manifest("dvfs-454")
        self.store_energy(doc, 454.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-454")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in report["issues"]),
                        "forged all-at-default energy must be caught: " + json.dumps(report["issues"][:2]))

    def test_tier_attribution_corruption_is_killed(self):

        bundle, doc = self.make_tier_manifest("dvfs-retier")
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 2000.0
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-retier")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in report["issues"]),
                        "re-tiered attribution must break the conserved energy: "
                        + json.dumps(report["issues"][:2]))

    def test_undeclared_tier_mips_is_chain_broken(self):

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

        bundle, doc = self.make_tier_manifest("dvfs-unsorted")
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in reversed(self.TWO_TIERS)]
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-unsorted")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("descending" in str(i) for i in report["issues"]),
                        "non-descending tier table must be rejected: " + json.dumps(report["issues"][:2]))

    THREE_TIERS = [
        {"mips": 2000.0, "idlePowerWatts": 100.0, "maxPowerWatts": 400.0},
        {"mips": 1000.0, "idlePowerWatts": 90.0, "maxPowerWatts": 200.0},
        {"mips": 500.0, "idlePowerWatts": 85.0, "maxPowerWatts": 150.0},
    ]

    def test_tier_speed_drives_task_window_recompute(self):

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

        doc["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 51.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        bad = self.audit_status(bundle, "tier-budget-bad")
        self.assertEqual("FAIL", bad["status"])
        self.assertTrue(any("totalCarbonExcessWattSeconds" in str(i.get("location", "")) for i in bad["issues"]),
                        "corrupted per-tier fleet excess must be caught: " + json.dumps(bad["issues"][:2]))

    def test_tiered_carbon_intensity_rides_per_tier_energy(self):

        bundle, doc = self.make_tier_manifest("dvfs-intensity")
        doc["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
        self.store_energy(doc, 254.0)
        grams = 254.0 / 3.6e6 * 380.0
        doc["carbonMetrics"] = {
            "totalCarbonGrams": grams, "meanVmCarbonGrams": grams,
            "carbonMakespanProductGramSeconds": grams * float(doc["metrics"]["makespanSeconds"]),
            "perVmCarbonGrams": {"0": grams},
            "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        good = self.audit_status(bundle, "tier-intensity-good")
        self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))

        doc["carbonMetrics"]["totalCarbonGrams"] = grams + 5.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        bad = self.audit_status(bundle, "tier-intensity-bad")
        self.assertEqual("FAIL", bad["status"])
        self.assertTrue(any("totalCarbonGrams" in str(i.get("location", "")) for i in bad["issues"]),
                        "corrupted per-tier ridden grams must be caught: " + json.dumps(bad["issues"][:2]))

    def test_plain_v1_fixture_stays_on_the_v1_law(self):

        root = self.root / "v1-plain"
        make_bundle(root)
        manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
        manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
        end = manifest["metrics"]["makespanSeconds"]
        busy = manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"]
        expected = 100.0 * end + 300.0 * busy
        manifest["energyMetrics"] = {
            "totalEnergyJoules": expected, "meanVmEnergyJoules": expected,
            "peakPowerWatts": 400.0, "energyMakespanProductJouleSeconds": expected * end,
            "vmEnergyJoules": {"0": expected},
        }
        (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        report = self.audit_status(root / "run.manifest.json", "v1-plain-good")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))

        self.assertFalse([n for n in report["notes"] if n["code"] == "dvfs-tier-accounting-observation"],
                         str(report["notes"]))

    def test_declared_but_empty_tier_list_is_killed(self):

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

        bundle, doc = self.make_tier_manifest("dvfs-inert-corrupt")
        self.store_energy(doc, 254.0)

        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in self.THREE_TIERS]
        doc["platform"]["vms"][0]["frequencyTiers"][2]["idlePowerWatts"] = 85.5
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-inert-corrupt")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))

if __name__ == "__main__":
    unittest.main(verbosity=2)
