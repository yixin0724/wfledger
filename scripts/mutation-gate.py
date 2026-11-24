#!/usr/bin/env python3

import argparse
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent

TEST_STATS = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)")

REGISTRY = [
    dict(
        id="M1",
        name="HEFT upward rank takes first child instead of maximum child",
        file="simulator/src/main/java/org/workflowsim/planning/SharedStorageDagPlanner.java",
        old="            childMaximum = Math.max(childMaximum, upwardRanks.get(child).doubleValue());",
        new="            childMaximum = upwardRanks.get(child).doubleValue();",
        killers="SharedStorageHeftPlanningAlgorithmTest,SharedStorageDlsPlanningAlgorithmTest,"
                "SharedStorageEtfPlanningAlgorithmTest,SharedStorageCpopPlanningAlgorithmTest"),
    dict(
        id="M2",
        name="MCT drops the availability term",
        file="simulator/src/main/java/org/workflowsim/planning/StaticIndependentPlanningSupport.java",
        old="            double finish = Double.isInfinite(duration) ? Double.POSITIVE_INFINITY : availability[index] + duration;",
        new="            double finish = Double.isInfinite(duration) ? Double.POSITIVE_INFINITY : duration;",
        killers="StaticIndependentPlanningAlgorithmsTest"),
    dict(
        id="M3",
        name="dependency gate inverted (only unsatisfied jobs pass)",
        file="simulator/src/main/java/org/workflowsim/WorkflowEngine.java",
        old="            if (!receivedSuccessfulJobIds.contains(job.getCloudletId())) {",
        new="            if (receivedSuccessfulJobIds.contains(job.getCloudletId())) {",
        killers="MetamorphicGapIntegrationTest,ReproducibilityTest"),
    dict(
        id="M4",
        name="fat-tree aggregation picks halfK instead of availableAggregateCount",
        file="simulator/src/main/java/org/workflowsim/network/FatTreeTopology.java",
        old="            int agg = srcEdge % availableAggregates;",
        new="            int agg = srcEdge % halfK;",
        killers="FatTreeTopologyTest"),
    dict(
        id="M5",
        name="fluid completion tolerance magnified x1000",
        file="simulator/src/main/java/org/workflowsim/data/TransferContentionEngine.java",
        old="            double tolerance = Math.min(bytes * 0.5, Math.max(1.0e-9 * bytes, 4.0 * Math.ulp(bytes)));",
        new="            double tolerance = Math.min(bytes * 0.5, Math.max(1.0e-6 * bytes, 4.0 * Math.ulp(bytes)));",
        killers="TransferContentionEngineTest,FractionalTransferContentionEngineTest,"
                "MaxMinTransferTest,TransferCompletionToleranceBoundaryTest"),
    dict(
        id="M6",
        name="MI conversion floor -> round at the parser boundary",
        file="simulator/src/main/java/org/workflowsim/WorkflowParser.java",
        old="""        if (convertedLength < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId,
                    convertedLength,
                    MIN_CLOUDLET_LENGTH);
            return MIN_CLOUDLET_LENGTH;
        }
        return (long) convertedLength;""",
        new="""        if (convertedLength < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId,
                    convertedLength,
                    MIN_CLOUDLET_LENGTH);
            return MIN_CLOUDLET_LENGTH;
        }
        return Math.round(convertedLength);""",
        killers="WorkflowInputValidationTest,WorkflowParserStrictValidationTest,"
                "WfCommonsJsonParserValidationTest,WorkflowDependencyParsingScalabilityTest"),
    dict(
        id="M8",
        name="online assignment halves the compute estimate of the nominal score",
        file="simulator/src/main/java/org/workflowsim/data/v2/DataflowVmAssigner.java",
        old="""            double compute = TaskExecutionModel.executionSeconds(task, vm.id, vm.mips);
            InputEstimate inputs = estimateInputs(observation, demands, DataLocation.vm(vm.id));
            double cpu = cpuAvailable(observation, vm.id, now);
            double finish = addTime(Math.max(addTime(now, inputs.seconds), cpu), compute);""",
        new="""            double compute = TaskExecutionModel.executionSeconds(task, vm.id, vm.mips);
            InputEstimate inputs = estimateInputs(observation, demands, DataLocation.vm(vm.id));
            double cpu = cpuAvailable(observation, vm.id, now);
            double finish = addTime(Math.max(addTime(now, inputs.seconds), cpu), compute / 2.0);""",
        killers="DataflowVmAssignerTest,DataflowAssignmentValidatorTest"),
    dict(
        id="M9",
        name="task execution window globally offset by 1e-6 seconds",
        file="simulator/src/main/java/org/workflowsim/WorkflowDatacenter.java",
        old="        double startTime = job.getExecStartTime() + effectiveStageInSeconds;",
        new="        double startTime = job.getExecStartTime() + effectiveStageInSeconds + 1.0e-6;",
        killers="NetworkEvidenceDatacenterTest,SimulationRunnerFailureCoverageIntegrationTest,"
                "SimulationRunnerAlgorithmContractTest,EventTerminalAndJobStartContractTest"),
    dict(
        id="M10",
        name="SHARED DLS dynamic level flips the earliest-start subtraction into an addition",
        file="simulator/src/main/java/org/workflowsim/planning/SharedStorageDagPlanner.java",
        old="                        new DlsCandidate(task, vm, upwardRanks.get(task).doubleValue() - earliestStart);",
        new="                        new DlsCandidate(task, vm, upwardRanks.get(task).doubleValue() + earliestStart);",
        killers="SharedStoragePlannerRuntimeMirrorIntegrationTest,SharedStorageDlsPlanningAlgorithmTest,"
                "SharedStorageDlsEtfSlotSemanticsAuditTest,SharedStorageDlsEtfSlotVisitAuditTest"),
    dict(
        id="M11",
        name="registered declaration ANY-scope no longer covers any file system",
        file="simulator/src/main/java/org/workflowsim/experiment/registry/AlgorithmDeclaration.java",
        old="            case ANY:\n                return true;",
        new="            case ANY:\n                return false;",
        killers="AlgorithmDeclarationTest,GradStudentSchedulerExerciseIntegrationTest"),
    dict(
        id="M12",
        name="engine fork drops the shared retained-trace prefix",
        file="simulator/src/main/java/org/workflowsim/data/TransferContentionEngine.java",
        old="            copy.traceState.head = traceState.head;",
        new="            copy.traceState.head = null;",
        killers="TransferContentionEngineForkTraceTest"),
    dict(
        id="M13",
        name="metric recompute mismatch check ignores the declared metrics value",
        file="simulator/src/main/java/org/workflowsim/experiment/ExperimentArtifactValidator.java",
        old="        if (Math.abs(declared - expected) > tolerance) {",
        new="        if (false) {",
        killers="ExperimentArtifactValidatorTest"),
    dict(
        id="M14",
        name="Holm step-down drops the (familySize - rank) multiplier",
        file="simulator/src/main/java/org/workflowsim/experiment/HolmStepDownCorrection.java",
        old="            double adjusted = Math.max(previous, Math.min(1.0, (familySize - rank) * raw));",
        new="            double adjusted = Math.max(previous, Math.min(1.0, raw));",
        killers="HolmStepDownCorrectionTest,CampaignHolmFamilyIntegrationTest"),
    dict(
        id="M15",
        name="event recorder cap admits one event beyond the budget",
        file="simulator/src/main/java/org/workflowsim/experiment/SimulationEventRecorder.java",
        old="        if (events.size() >= maxEvents) {",
        new="        if (events.size() > maxEvents) {",
        killers="SimulationEventRecorderTest"),
    dict(
        id="M16",
        name="cost-caliber guard rejects homogeneous groups instead of mixed ones",
        file="simulator/src/main/java/org/workflowsim/experiment/ExperimentPlan.java",
        old="                    if (existingCaliber != null && existingCaliber != caliber) {",
        new="                    if (existingCaliber != null && existingCaliber == caliber) {",
        killers="ExperimentPlanCaliberGuardTest"),
    dict(
        id="M18",
        name="finished lifecycle validator still accepts event feeding",
        file="simulator/src/main/java/org/workflowsim/experiment/FileLifecycleContextValidator.java",
        old="""        if (lifecycleFinished) {
            throw new IllegalStateException(
                    "Event feeding is rejected: this lifecycle validator already finished (single-use instance)");
        }""",
        new="""        if (false) {
            throw new IllegalStateException(
                    "Event feeding is rejected: this lifecycle validator already finished (single-use instance)");
        }""",
        killers="FileLifecycleArtifactIntegrationTest"),
    dict(
        id="M19",
        name="INVALID label binds the FCFS policy instead of the static no-decision shell",
        file="simulator/src/main/java/org/workflowsim/WorkflowScheduler.java",
        old="""            case INVALID:
                algorithm = new StaticSchedulingAlgorithm();
                break;""",
        new="""            case INVALID:
                algorithm = new FCFSSchedulingAlgorithm();
                break;""",
        killers="WorkflowSchedulerExplicitBindingTest"),
    dict(
        id="M20",
        name="Runner ignores the configured event-recorder cap and constructs the default recorder",
        file="simulator/src/main/java/org/workflowsim/experiment/SimulationRunner.java",
        old="""        SimulationEventRecorder events = config.isEventRecordingEnabled()
                ? new SimulationEventRecorder(config.getEventRecorderCap())
                : SimulationEventRecorder.disabled();""",
        new="""        SimulationEventRecorder events =
                config.isEventRecordingEnabled() ? new SimulationEventRecorder() : SimulationEventRecorder.disabled();""",
        killers="SimulationRunnerEventRecorderCapIntegrationTest"),
    dict(
        id="M21",
        name="Declared revision contract is ignored, so a marker-less stream falls back to the legacy boundary",
        file="simulator/src/main/java/org/workflowsim/experiment/ExperimentArtifactValidator.java",
        old="        boolean contractDeclared = declaredContract != null;",
        new="        boolean contractDeclared = false;",
        killers="ExperimentArtifactValidatorTest"),
    dict(
        id="M22",
        name="Campaign validator skips the independent statistical-summary recompute",
        file="simulator/src/main/java/org/workflowsim/experiment/ExperimentCampaignValidator.java",
        old="        ExperimentCampaignSummaryVerifier.verify(index, document);",
        new="        // M22: summary verification deliberately bypassed",
        killers="ExperimentCampaignExecutorTest"),
    dict(
        id="M23",
        name="WfCommons parser skips schema-version and unknown-field validation",
        file="simulator/src/main/java/org/workflowsim/WfCommonsJsonParser.java",
        old="            WfCommonsSchemaValidator.validate(document);",
        new="            // M23: schema validation deliberately bypassed",
        killers="WfCommonsJsonParserValidationTest"),
    dict(
        id="M24",
        name="Space-Shared drops the file-transfer MI added to Cloudlet length",
        file="simulator/src/main/java/org/cloudbus/cloudsim/CloudletSchedulerSpaceShared.java",
        old="        cloudlet.setCloudletLength(length);",
        new="        cloudlet.setCloudletLength(cloudlet.getCloudletLength());",
        killers="CloudletSchedulerReferenceTest"),
    dict(
        id="M25",
        name="FutureQueue assigns serial zero to every event",
        file="simulator/src/main/java/org/cloudbus/cloudsim/core/FutureQueue.java",
        old="        newEvent.setSerial(serial++);",
        new="        newEvent.setSerial(0L);",
        killers="CloudSimKernelReferenceTest"),
    dict(
        id="M26",
        name="WorkflowDatacenter drops the SHARED input-transfer seconds",
        file="simulator/src/main/java/org/workflowsim/WorkflowDatacenter.java",
        old="        updateTaskExecTime(job, vm, effectiveStageInSeconds, fileTransferTime);",
        new="        updateTaskExecTime(job, vm, effectiveStageInSeconds, 0.0);",
        killers="ControlledSharedStorageIndependentReferenceTest"),
    dict(
        id="M27",
        name="WfCommons runtime conversion collapses every task to the minimum length",
        file="simulator/src/main/java/org/workflowsim/WfCommonsJsonParser.java",
        old="        return Math.max(MIN_CLOUDLET_LENGTH, (long) length);",
        new="        return MIN_CLOUDLET_LENGTH;",
        killers="WfCommonsRuntimeReferenceTest"),
    dict(
        id="M28",
        name="Exact Wilcoxon reverts to the Commons Math 3.2 rank-sum path that discards ties",
        file="simulator/src/main/java/org/workflowsim/experiment/PairedWilcoxonSignificance.java",
        old="            pValue = exactTwoSidedPValue(pairedBaseline, pairedCandidate);",
        new="            pValue = new WilcoxonSignedRankTest().wilcoxonSignedRankTest(pairedBaseline, pairedCandidate, true);",
        killers="PairedWilcoxonExternalReferenceTest"),
    dict(
        id="M29",
        name="Completion guard ignores the configured minimum event interval",
        file="simulator/src/main/java/org/workflowsim/utils/SimulationTiming.java",
        old="                currentTime + minimumEventIntervalSeconds + CLOUDLET_COMPLETION_SAFETY_SECONDS);",
        new="                currentTime + 0.0 * minimumEventIntervalSeconds + CLOUDLET_COMPLETION_SAFETY_SECONDS);",
        killers="SimulationTickSensitivityTest"),
    dict(
        id="M31",
        name="Coherent dataflow runtime reports zero admitted copies",
        file="simulator/src/main/java/org/workflowsim/data/v2/CoherentDataflowRuntime.java",
        old="        return current == null ? 0 : current.replicas.getAdmittedCopyCount();",
        new="        return 0L;",
        killers="DataflowStateProfileSmokeTest"),
    dict(
        id="M32",
        name="CaWoSched green-window selection degrades to ASAP",
        file="simulator/src/main/java/org/workflowsim/planning/cawosched/CaWoSchedPlanner.java",
        old="    private double selectStart(int taskId, double earliest, double latest) {\n        double selected = earliest;\n        double bestGreen = Double.NEGATIVE_INFINITY;",
        new="    private double selectStart(int taskId, double earliest, double latest) {\n        double selected = earliest;\n        double bestGreen = Double.POSITIVE_INFINITY;",
        killers="CaWoSchedPlannerTest"),
    dict(
        id="M33",
        name="CaWoSched power weighting is removed from slack scores",
        file="simulator/src/main/java/org/workflowsim/planning/cawosched/CaWoSchedPlanner.java",
        old="                value = weighted && maxActive > 0.0 ? slack * maxActive / task.getActiveWatts() : slack;",
        new="                value = slack;",
        killers="TimedPlanFrameworkTest"),
    dict(
        id="M34",
        name="Communication-enhanced DAG silently drops a missing link",
        file="simulator/src/main/java/org/workflowsim/planning/timed/CommunicationEnhancedPlan.java",
        old="                if (link == null) {\n                    throw new IllegalArgumentException(\n                            \"Missing communication link for \" + parentId + \"->\" + activity.getActivityId());\n                }",
        new="                if (link == null) {\n                    continue;\n                }",
        killers="CommunicationEnhancedPlanTest"),
    dict(
        id="M35",
        name="Exact single-processor DP drops all non-zero candidate starts",
        file="simulator/src/main/java/org/workflowsim/planning/cawosched/CaWoSchedPlanner.java",
        old="            for (int start = 0; start <= (int) Math.floor(deadlineSeconds); start++) {",
        new="            for (int start = 0; start < 1; start++) {",
        killers="CaWoSchedExactOptimizerTest"),
    dict(
        id="M36",
        name="CWS RankMin comparator ignores DAG rank",
        file="simulator/src/main/java/org/workflowsim/scheduling/CwsRankMinRoundRobinSchedulingAlgorithm.java",
        old="                int byRank = Integer.compare(secondJob.getCwsRank(), firstJob.getCwsRank());",
        new="                int byRank = 0;",
        killers="CwsRankMinRoundRobinSchedulingAlgorithmTest"),
    dict(
        id="M37",
        name="NACO DNC removes the cross-host communication term",
        file="simulator/src/main/java/org/workflowsim/planning/naco/NacoDncEvaluator.java",
        old="                    communication += edgeBits / slowest;",
        new="                    communication += 0.0;",
        killers="NacoDncEvaluatorTest"),
    dict(
        id="M38",
        name="NACO GPX discards the first-parent prefix",
        file="simulator/src/main/java/org/workflowsim/planning/naco/NacoGaOperators.java",
        old="            if (index < cut) {",
        new="            if (false) {",
        killers="NacoGaHeftOptimizerTest"),
    dict(
        id="M39",
        name="Reshi prediction error drops the non-negative runtime clamp",
        file="simulator/src/main/java/org/workflowsim/utils/ReshiPredictionErrorModel.java",
        old="        return Math.max(1.0 - sample * errorFraction, 0.0);",
        new="        return 1.0 - sample * errorFraction;",
        killers="ReshiPredictionErrorModelTest"),
    dict(
        id="M40",
        name="GreenHEFT selects earliest finish instead of minimum energy",
        file="simulator/src/main/java/org/workflowsim/planning/greenheft/GreenHeftPlanner.java",
        old="                if (taskEnergy < bestEnergy - 1.0e-9",
        new="                if (finish < bestFinish - 1.0e-9",
        killers="GreenHeftPlannerTest"),
    dict(
        id="M41",
        name="MOHEFT dominance ignores energy",
        file="simulator/src/main/java/org/workflowsim/planning/moheft/MoHeftPlanner.java",
        old="        boolean noWorse = first.makespan <= second.makespan && first.energy <= second.energy;",
        new="        boolean noWorse = first.makespan <= second.makespan;",
        killers="MoHeftPlannerTest"),
    dict(
        id="M42",
        name="MOHEFT balanced choice ignores makespan tradeoff",
        file="simulator/src/main/java/org/workflowsim/planning/moheft/MoHeftPlanner.java",
        old="            double distance = Math.sqrt(makespanPart * makespanPart + energyPart * energyPart);",
        new="            double distance = candidate.energy;",
        killers="MoHeftPlannerTest"),
    dict(
        id="M43",
        name="IC-PCP ignores the virtual END deadline slack",
        file="simulator/src/main/java/org/workflowsim/planning/icpcp/IcpcpPlanner.java",
        old="        minimum = Math.min(minimum, latest.get(END_ID) - starts.get(END_ID));",
        new="        // END slack intentionally ignored by the mutant",
        killers="IcpcpPlannerTest"),
    dict(
        id="M44",
        name="IC-PCP path choice ignores incremental cost",
        file="simulator/src/main/java/org/workflowsim/planning/icpcp/IcpcpPlanner.java",
        old="            if (best == null\n                    || costDelta < best.costDelta - 1.0e-9",
        new="            if (best == null\n                    || slack > best.slack + 1.0e-9",
        killers="IcpcpPlannerTest"),
    dict(
        id="M45",
        name="Network-Aware HEFT gives every flow the full bottleneck",
        file="simulator/src/main/java/org/workflowsim/planning/networkheft/NetworkHeftFlowStretcher.java",
        old="                flow.remaining = Math.max(0.0, flow.remaining - capacityBytesPerSecond * (end - time) / active.size());",
        new="                flow.remaining = Math.max(0.0, flow.remaining - capacityBytesPerSecond * (end - time));",
        killers="NetworkHeftPlannerTest"),
    dict(
        id="M46",
        name="EBPSM ignores input-data cache preference",
        file="simulator/src/main/java/org/workflowsim/scheduling/EbpsmSchedulingAlgorithm.java",
        old="                if (hasRequiredInput(task, vm.getId())) {",
        new="                if (false) {",
        killers="EbpsmSchedulingAlgorithmTest"),
    dict(
        id="M47",
        name="EBPSM dispatches tasks above their budget",
        file="simulator/src/main/java/org/workflowsim/scheduling/EbpsmSchedulingAlgorithm.java",
        old="            if (cost <= budget + 1.0e-9",
        new="            if (true",
        killers="EbpsmSchedulingAlgorithmTest"),
    dict(
        id="M48",
        name="DVFS slack allocation accepts an energy-increasing downgrade",
        file="simulator/src/main/java/org/workflowsim/planning/dvfs/DvfsSlackOptimizer.java",
        old="                    if (candidate.getMakespanSeconds() <= deadlineSeconds + 1.0e-9\n                            && candidate.getTotalEnergyJoules() < bestEnergy - 1.0e-9) {",
        new="                    if (candidate.getMakespanSeconds() <= deadlineSeconds + 1.0e-9 && true) {",
        killers="DvfsSlackOptimizerTest"),
    dict(
        id="M49",
        name="CRCH recovery ignores completed checkpoint work",
        file="simulator/src/main/java/org/workflowsim/utils/CrchRegistry.java",
        old="        long remaining = Math.max(0L, workMi - savedMi);",
        new="        long remaining = workMi;",
        killers="CrchRegistryTest"),
    dict(
        id="M50",
        name="CRCH retry selects the failed VM again",
        file="simulator/src/main/java/org/workflowsim/scheduling/CrchSchedulingAlgorithm.java",
        old="                if (recovery != null && vm.getId() == recovery.getFailedVmId()) {",
        new="                if (false) {",
        killers="CrchSchedulingAlgorithmTest"),
    dict(
        id="M51",
        name="DHEFT uses maximum instead of minimum pairwise bandwidth",
        file="simulator/src/main/java/org/workflowsim/planning/dheft/DheftProblem.java",
        old="        return Math.min(bandwidthByVm.get(firstVmId), bandwidthByVm.get(secondVmId));",
        new="        return Math.max(bandwidthByVm.get(firstVmId), bandwidthByVm.get(secondVmId));",
        killers="DheftPlannerTest"),
    dict(
        id="M52",
        name="DHEFT ignores parent finish time",
        file="simulator/src/main/java/org/workflowsim/planning/dheft/DheftPlanner.java",
        old="                    start = Math.max(start, parent.getFinishSeconds() + communication);",
        new="                    start = available.getOrDefault(vmId, 0.0);",
        killers="DheftPlannerTest"),
    dict(
        id="M54",
        name="DVFS slack drops unused-VM and terminal idle energy",
        file="simulator/src/main/java/org/workflowsim/planning/dvfs/DvfsSlackOptimizer.java",
        old="            energy += defaultTier.getIdlePowerWatts() * makespan;",
        new="            energy += 0.0;",
        killers="DvfsSlackOptimizerTest"),
    dict(
        id="M55",
        name="DVFS slack drops SHARED stage-in and root release timing",
        file="simulator/src/main/java/org/workflowsim/planning/dvfs/DvfsSlackOptimizer.java",
        old="            double start = Math.max(\n                    task.getEarliestStartSeconds(), Math.max(available.getOrDefault(task.getVmId(), 0.0), ready));\n            double finish = start + task.getStageInSeconds() + task.getWorkMi() / tier.getMips();",
        new="            double start = Math.max(available.getOrDefault(task.getVmId(), 0.0), ready);\n            double finish = start + task.getWorkMi() / tier.getMips();",
        killers="DvfsSlackIntegrationTest"),
    dict(
        id="M56",
        name="GreenHEFT reverts to append-only placement",
        file="simulator/src/main/java/org/workflowsim/planning/greenheft/GreenHeftPlanner.java",
        old="            if (start + duration <= reservation.getStartSeconds() + 1.0e-9) {",
        new="            if (false) {",
        killers="GreenHeftPlannerTest"),
    dict(
        id="M57",
        name="GreenHEFT admits PE-incompatible VMs",
        file="simulator/src/main/java/org/workflowsim/planning/greenheft/GreenHeftAdapter.java",
        old="                if (vm.getNumberOfPes() >= task.getNumberOfPes()) {",
        new="                if (true) {",
        killers="GreenHeftAdapterTest"),
    dict(
        id="M58",
        name="DHEFT drops decimal-megabyte bandwidth conversion",
        file="simulator/src/main/java/org/workflowsim/planning/dheft/DheftAdapter.java",
        old="            bandwidth.put(vm.getId(), vm.getBw() * 1.0e6);",
        new="            bandwidth.put(vm.getId(), (double) vm.getBw());",
        killers="DheftAdapterTest"),
    dict(
        id="M59",
        name="Network-Aware HEFT disables reservation-gap insertion",
        file="simulator/src/main/java/org/workflowsim/planning/networkheft/NetworkHeftPlanner.java",
        old="            if (start + duration <= reservation.start) {",
        new="            if (false) {",
        killers="NetworkHeftPlannerTest"),
    dict(
        id="M60",
        name="Reshi-M reapplies prediction noise inside its comparator",
        file="simulator/src/main/java/org/workflowsim/scheduling/ReshiSchedulingAlgorithm.java",
        old="                .getTaskHistory()\n                .averageRuntimeFor(job.getTaskList().get(0).getType());",
        new="                        .getTaskHistory()\n                        .averageRuntimeFor(job.getTaskList().get(0).getType())\n                * configuration()\n                        .getPredictionErrorModel()\n                        .sampleFactor(\n                                configuration().getPredictionErrorFraction(),\n                                org.workflowsim.utils.SimulationRandom.newJavaRandom(\"scheduling.reshi\"));",
        killers="ReshiSchedulingAlgorithmTest"),
    dict(
        id="M61",
        name="CWS counts non-real input bytes in its tie-break",
        file="simulator/src/main/java/org/workflowsim/scheduling/CwsRankMinRoundRobinSchedulingAlgorithm.java",
        old="            if (file.getType() == FileType.INPUT && file.isRealInputFile(job.getFileList())) {",
        new="            if (file.getType() == FileType.INPUT) {",
        killers="CwsRankMinRoundRobinSchedulingAlgorithmTest"),
    dict(
        id="M62",
        name="NACO double-counts parent-produced input as external disk read",
        file="simulator/src/main/java/org/workflowsim/planning/naco/NacoAdapter.java",
        old="                if (file.getType() == FileType.INPUT && !parentProducedInputs.contains(file.getName())) {",
        new="                if (file.getType() == FileType.INPUT) {",
        killers="NacoAdapterTest"),
    dict(
        id="M63",
        name="EBPSM publishes unsuccessful output as readable cache",
        file="simulator/src/main/java/org/workflowsim/scheduling/EbpsmSchedulingAlgorithm.java",
        old="        if (task.getCloudletStatus() != org.cloudbus.cloudsim.Cloudlet.SUCCESS) {",
        new="        if (false) {",
        killers="EbpsmSchedulingAlgorithmTest"),
    dict(
        id="M64",
        name="CRCH ignores checkpoint overhead when committing progress",
        file="simulator/src/main/java/org/workflowsim/utils/CrchRegistry.java",
        old="            double candidate = checkpointedWorkSeconds\n                    + active.getCheckpointIntervalSeconds()\n                    + active.getCheckpointOverheadSeconds();",
        new="            double candidate = checkpointedWorkSeconds + active.getCheckpointIntervalSeconds() + 0.0;",
        killers="CrchRegistryTest"),
    dict(
        id="M65",
        name="EDP heuristic double-counts default idle during busy execution",
        file="simulator/src/main/java/org/workflowsim/WorkflowScheduler.java",
        old="            double energyEstimate = tier.getMaxPowerWatts() * estimatedSeconds\n                    + defaultTier.getIdlePowerWatts() * (makespanEstimate - estimatedSeconds);",
        new="            double energyEstimate =\n                    tier.getMaxPowerWatts() * estimatedSeconds + defaultTier.getIdlePowerWatts() * makespanEstimate;",
        killers="FrequencyTierAssignmentTest"),
    dict(
        id="M66",
        name="Failed parent releases its child before a successful retry",
        file="simulator/src/main/java/org/workflowsim/WorkflowEngine.java",
        old="        if (job.getCloudletStatus() == Cloudlet.SUCCESS) {\n            receivedSuccessfulJobIds.add(job.getCloudletId());\n        }",
        new="        receivedSuccessfulJobIds.add(job.getCloudletId());",
        killers="WorkflowEngineDependencyGateTest,CrchIntegrationTest"),
    dict(
        id="M67",
        name="CRCH retry overwrites remaining work with the original task length",
        file="simulator/src/main/java/org/workflowsim/WorkflowDatacenter.java",
        old="            if (org.workflowsim.utils.CrchRegistry.hasRecovery(job.getCloudletId())) {",
        new="            if (false) {",
        killers="CrchIntegrationTest"),
]

EXPERIMENTS_REGISTRY = [
    dict(
        id="M53",
        name="Cross-paper comparison drops direct IC-PCP planner cost",
        file="experiments/src/main/java/org/workflowsim/experiments/comparison/CrossPaperComparison.java",
        old="                    value,",
        new="                    null,",
        killers="CrossPaperComparisonTest",
        module="experiments"),
    dict(
        id="M30",
        name="Workbench drops the event-recorder cap from the simulation allowlist",
        file="experiments/src/main/java/org/workflowsim/experiments/workbench/WorkbenchConfig.java",
        old="                \"eventRecorderCap\",\n                \"eventRecordingEnabled\",\n                \"deadlineSeconds\",\n",
        new="                \"eventRecordingEnabled\",\n                \"deadlineSeconds\",\n",
        killers="WorkbenchConfigTest",
        module="experiments"),
]

class AnchorError(ValueError):
    pass

def parse_test_stats(output):

    match = TEST_STATS.search(output)
    if not match:
        return None
    runs = int(match.group(1))
    failures = int(match.group(2)) + int(match.group(3))
    return runs, failures

def classify_stage(exit_code, failures):
    if exit_code != 0 and failures:
        return "KILLED"
    if exit_code != 0:
        return "INFRA_FAILURE"
    return "NOT_KILLED_BY_STAGE"

def apply_mutation(text, old, new):
    if text.count(old) != 1:
        raise AnchorError("anchor occurs %d times (expected 1)" % text.count(old))
    return text.replace(old, new, 1)

def collect_anchor_states(registry=None):

    states = {}
    for mutant in (registry or REGISTRY):
        path = ROOT / mutant["file"]
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            states[mutant["id"]] = "ANCHOR_STALE"
            continue
        states[mutant["id"]] = "LIVE" if text.count(mutant["old"]) == 1 else "ANCHOR_STALE"
    return states

def summarize(results):
    killed = [r for r in results if r["status"] == "KILLED"]
    survivors = [r for r in results if r["status"] == "SURVIVED"]
    return {
        "total": len(results),
        "killed": len(killed),
        "survived": len(survivors),
        "survivorIds": [r["id"] for r in survivors],
        "staleIds": [r["id"] for r in results if r["status"] == "ANCHOR_STALE"],
        "infraFailureIds": [r["id"] for r in results if r["status"] == "INFRA_FAILURE"],
    }

def report_exit_code(results):
    if any(r["status"] == "ANCHOR_STALE" for r in results):
        return 2
    if any(r["status"] in ("SURVIVED", "INFRA_FAILURE") for r in results):
        return 1
    return 0

def write_report(path, data):
    path = pathlib.Path(path)
    if path.exists():
        raise OSError("gate refused: output must be a new file: %s" % path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

def run_maven(arguments):
    started = time.time()
    proc = subprocess.run(
        ["mvn"] + arguments, cwd=str(ROOT), capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr, time.time() - started

def git_revision():
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=str(ROOT), capture_output=True, text=True)
        return proc.stdout.strip() if proc.returncode == 0 else None
    except OSError:
        return None


def git_tree_dirty():
    try:
        proc = subprocess.run(
            ["git", "status", "--porcelain"], cwd=str(ROOT),
            capture_output=True, text=True)
        if proc.returncode != 0:
            return None
        return bool(proc.stdout.strip())
    except OSError:
        return None

def module_test_arguments(mutant, killers=None):

    if mutant.get("module", "simulator") == "experiments":
        arguments = ["-pl", ":workflowsim-workbench", "-am", "test"]
    else:
        arguments = ["-pl", ":workflowsim", "test"]
    if killers:
        arguments.extend(["-Dtest=%s" % killers, "-Dsurefire.failIfNoSpecifiedTests=false"])
    return arguments

def execute_mutant(mutant):

    path = ROOT / mutant["file"]
    original = path.read_text(encoding="utf-8")
    if original.count(mutant["old"]) != 1:
        return {"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                "status": "ANCHOR_STALE", "stage": "preflight"}
    elapsed = 0.0
    stage = "killers"
    try:
        path.write_text(
            original.replace(mutant["old"], mutant["new"]), encoding="utf-8")
        code, out, secs = run_maven(module_test_arguments(mutant, mutant["killers"]))
        elapsed += secs
        stats = parse_test_stats(out)
        failures = stats[1] if stats else 0
        if code != 0 and failures:
            return {"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                    "status": "KILLED", "stage": "killers", "testStats": stats,
                    "seconds": round(elapsed, 1)}
        code, out, secs = run_maven(module_test_arguments(mutant))
        elapsed += secs
        stats = parse_test_stats(out)
        failures = stats[1] if stats else 0
        if code != 0 and failures:
            status = "KILLED"
        elif code != 0:
            status = "INFRA_FAILURE"
        else:
            status = "SURVIVED"
        return {"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                "status": status, "stage": "full-module", "testStats": stats,
                "seconds": round(elapsed, 1)}
    finally:
        path.write_text(original, encoding="utf-8")
        restored = path.read_text(encoding="utf-8")
        if restored != original:
            raise RuntimeError("restore verification failed for %s" % mutant["file"])

def main(argv=None):
    parser = argparse.ArgumentParser(
        description='Registered-mutant gate: verifies the maintained test battery still kills\nevery registered semantic mutation (mutation-survival = 0 gate).\n\nStatuses\n    KILLED        the registered killer tests (or the full module battery)\n                  fail while this mutant is applied\n    SURVIVED      mutant applied and the full simulator battery stayed green\n    ANCHOR_STALE  the registered anchor no longer occurs exactly once in the\n                  current tree (source drift; the ledger must be updated)\n    INFRA_FAILURE build/test infrastructure failed without test failures\n\nExit codes\n    0  all registered mutants KILLED\n    1  at least one SURVIVED or INFRA_FAILURE\n    2  at least one ANCHOR_STALE (takes precedence: ledger maintenance first)\n\nUsage\n    python3 -B scripts/mutation-gate.py --output <new-report.json>\n    python3 -B scripts/mutation-gate.py --output <new-report.json> --dry-run\n    python3 -B scripts/mutation-gate.py --output <new-report.json> --ids M5,M9\n    python3 -B scripts/mutation-gate.py --module experiments --output <new-report.json>\n    python3 -B scripts/mutation-gate.py --all-modules --output <new-report.json>\n\nSafety: mutation is applied in place to the working tree; every file is\nrestored from an in-memory backup in a `finally` block and re-verified after\nrestore. Full mode refuses to start on a dirty Git tree. Run it exclusively\n(no concurrent Maven builds in the same checkout).\n', formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", required=True,
                        help="path for the new JSON report (must not exist)")
    parser.add_argument("--dry-run", action="store_true",
                        help="anchor-liveness and ledger check only; no mutation, no Maven")
    parser.add_argument("--ids", default=None,
                        help="comma-separated mutant ids to execute (default: all)")
    parser.add_argument("--module", choices=("simulator", "experiments"), default="simulator",
                        help="registered mutant family to execute (default: simulator)")
    parser.add_argument("--all-modules", action="store_true",
                        help="execute simulator and experiments registries sequentially")
    args = parser.parse_args(argv)

    all_mutants = list(REGISTRY) + list(EXPERIMENTS_REGISTRY)
    if args.all_modules:
        registry = all_mutants
    else:
        registry = list(REGISTRY if args.module == "simulator" else EXPERIMENTS_REGISTRY)
    if args.ids:
        wanted = {token.strip() for token in args.ids.split(",") if token.strip()}
        known = {mutant["id"] for mutant in all_mutants}
        unknown = wanted - known
        if unknown:
            parser.error("unknown mutant ids: %s" % ", ".join(sorted(unknown)))
        registry = [mutant for mutant in registry if mutant["id"] in wanted]

    started = time.time()
    dirty = git_tree_dirty()
    if args.dry_run:
        states = collect_anchor_states(registry)
        results = [{"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                    "status": states[mutant["id"]], "stage": "dry-run"}
                   for mutant in registry]
    else:
        if dirty:
            print("mutation-gate: refusing to run on a dirty Git tree; "
                  "commit or stash first", file=sys.stderr)
            return 2
        if dirty is None:
            print("mutation-gate: git unavailable; proceeding with in-place "
                  "backup/restore only", file=sys.stderr)
        results = [execute_mutant(mutant) for mutant in registry]

    report = {
        "schema": "workflowsim-mutation-gate-v1",
        "scope": "REGISTERED_MUTANT_SURVIVAL_GATE_NOT_A_TEST substitute_entry",
        "mode": "dry-run" if args.dry_run else "full",
        "sourceCommit": git_revision(),
        "sourceTreeDirty": dirty,
        "command": ["python3", "-B", "scripts/mutation-gate.py"] + list(argv if argv is not None else sys.argv[1:]),
        "modules": sorted({mutant.get("module", "simulator") for mutant in registry}),
        "summary": summarize(results),
        "results": results,
        "wallClockSeconds": round(time.time() - started, 1),
    }
    write_report(args.output, report)
    summary = report["summary"]
    print("mutation-gate: %d/%d KILLED; survivors=%s stale=%s infra=%s" % (
        summary["killed"], summary["total"],
        summary["survivorIds"] or "-", summary["staleIds"] or "-",
        summary["infraFailureIds"] or "-"))
    for result in results:
        print("  [%s] %s @ %s (%ss)" % (
            result["id"], result["status"], result["stage"], result.get("seconds", 0)))
    print("report: %s" % args.output)
    return report_exit_code(results)

if __name__ == "__main__":
    sys.exit(main())
