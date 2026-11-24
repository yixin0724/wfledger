package org.workflowsim.experiments.fgcs.contracts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.DataflowAssignmentConfig;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.FileLifecycleEvent;
import org.workflowsim.experiment.SimulationEvent;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Hand-derived semantic oracles for FGCS round-two coverage gaps.
 *
 * <p>Every case executes the ordinary serial {@link SimulationRunner}. Absolute times originate in
 * the declared 100-second workflow arrival, not in a previous simulator observation. Expected
 * candidate scores, joules, checkpoint work, and retry work are derived from the public contracts
 * before execution. A fixture that changes mapping, loses an injection, truncates evidence, or
 * misses an absolute expectation aborts without a passing result.
 */
public final class SemanticContractBenchmarks {

    private static final double EPSILON = 1.0e-9;
    private static final double ARRIVAL_SECONDS = 100.0;
    private static final int EVIDENCE_BUDGET = 4000;
    private static final Gson JSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private SemanticContractBenchmarks() {}

    /** Runs all semantic contracts serially and writes results only after every oracle passes. */
    public static Suite run(Path outputDirectory) throws Exception {
        if (outputDirectory == null) {
            throw new IllegalArgumentException("Contract output directory is required");
        }
        Files.createDirectories(outputDirectory);
        Path resultPath = outputDirectory.resolve("results.json");
        Files.deleteIfExists(resultPath);
        Path inputs = outputDirectory.resolve("inputs");
        Files.createDirectories(inputs);

        Map<String, SimulationReport> reports = new LinkedHashMap<String, SimulationReport>();
        List<EvidenceFile> evidenceFiles = new ArrayList<EvidenceFile>();
        JsonArray cases = new JsonArray();
        cases.add(controlReadyCase(inputs, reports, evidenceFiles));
        cases.add(tieredEnergyCase(inputs, reports, evidenceFiles));
        cases.add(repeatedCheckpointCase(inputs, reports, evidenceFiles));

        JsonObject document = new JsonObject();
        document.addProperty("schema", "fgcs-round2-semantic-contracts-v1");
        document.addProperty("execution", "ACTUAL_SIMULATION_SERIAL_SESSION");
        document.addProperty("arrivalSeconds", ARRIVAL_SECONDS);
        document.addProperty("timeUnit", "simulated seconds");
        document.addProperty("workUnit", "MI");
        document.addProperty("energyUnit", "joules");
        JsonArray contracts = new JsonArray();
        contracts.add("docs/advanced/ONLINE_DATAFLOW_ASSIGNMENT_CONTRACT.md");
        contracts.add("docs/advanced/COHERENT_DATAFLOW_CONTRACT.md");
        contracts.add("docs/advanced/STORAGE_DATAFLOW_CONTRACT.md");
        contracts.add("docs/advanced/ENERGY_DIMENSION_DESIGN.md");
        contracts.add("docs/algorithms/CONTRACTS.md#crch-synchronized-checkpoint-recovery-r-paper-11-2026-11-08");
        document.add("contracts", contracts);
        document.add("cases", cases);

        for (EvidenceFile evidence : evidenceFiles) {
            writeJson(outputDirectory.resolve(evidence.name), evidence.document);
        }
        writeJson(resultPath, document);
        return new Suite(reports, document);
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("paper/tmp/fgcs-round2/contracts") : Paths.get(args[0]);
        Suite suite = run(output);
        System.out.println("FGCS round-two semantic contracts passed="
                + suite.getDocument().getAsJsonArray("cases").size() + " output="
                + output.toAbsolutePath().normalize());
    }

    private static JsonObject controlReadyCase(
            Path inputs, Map<String, SimulationReport> reports, List<EvidenceFile> evidenceFiles) throws Exception {
        Path dax = writeDax(
                inputs.resolve("control-ready.dax"),
                "<job id=\"parent\" runtime=\"10\"><uses file=\"x\" link=\"output\""
                        + " size=\"1000000\"/></job><job id=\"filler\" runtime=\"20\"/>"
                        + "<job id=\"child\" runtime=\"1\"><uses file=\"x\" link=\"input\""
                        + " size=\"1000000\"/></job><child ref=\"child\"><parent ref=\"parent\"/></child>");
        SimulationConfig config = SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.coherentFileDataflowNoContentionV2())
                .dataflowAssignment(DataflowAssignmentConfig.onlineNominal())
                .networkEvidence(NetworkEvidenceConfig.fileLifecycleV2(EVIDENCE_BUDGET))
                .workflowArrivalSeconds(Collections.singletonList(ARRIVAL_SECONDS))
                .runtimeReferenceMips(1000.0)
                .randomSeed(1L)
                .cloudSimMinEventIntervalSeconds(0.1)
                .build();
        SimulationReport report = new SimulationRunner().run(config, assignmentPlatform());
        reports.put("control-ready-assignment", report);
        requireComplete(report);

        SimulationEvent parent = assignment(report, 1);
        SimulationEvent filler = assignment(report, 2);
        SimulationEvent child = assignment(report, 3);
        require(parent.getSequence() < filler.getSequence(), "Root fixture order changed before reservations");
        require(filler.getSequence() < child.getSequence(), "Child was assigned before its control release");
        for (SimulationEvent action : new SimulationEvent[] {parent, filler, child}) {
            JsonArray candidates = attributes(action).getAsJsonArray("candidates");
            require(candidates.size() == 2, "Every initial action must score both actual VMs");
            require(candidates.get(0).getAsJsonObject().get("vmId").getAsInt() == 3, "First candidate is not VM 3");
            require(candidates.get(1).getAsJsonObject().get("vmId").getAsInt() == 9, "Second candidate is not VM 9");
            require("INITIAL".equals(attributes(action).get("binding").getAsString()), "Unexpected initial binding");
        }

        JsonObject expected = new JsonObject();
        expected.addProperty("parentAssignmentSeconds", 100.0);
        expected.addProperty("fillerAssignmentSeconds", 100.0);
        expected.addProperty("childAssignmentSeconds", 110.0);
        expected.addProperty("parentSelectedVm", 9);
        expected.addProperty("fillerSelectedVm", 9);
        expected.addProperty("childSelectedVm", 3);
        candidateExpected(expected, "parentSlow", 0.0, 100.0, 20.0, 120.0);
        candidateExpected(expected, "parentFast", 0.0, 100.0, 10.0, 110.0);
        candidateExpected(expected, "fillerSlow", 0.0, 100.0, 40.0, 140.0);
        candidateExpected(expected, "fillerFast", 0.0, 110.0, 20.0, 130.0);
        candidateExpected(expected, "childSlow", 1.0, 110.0, 2.0, 113.0);
        candidateExpected(expected, "childFast", 0.0, 130.0, 1.0, 131.0);
        expected.addProperty("remoteInputCopies", 1);
        expected.addProperty("childInputAdmissionSeconds", 110.0);
        expected.addProperty("childInputVisibleSeconds", 111.0);
        expected.addProperty("childDataReadySeconds", 111.0);
        expected.addProperty("childCpuStartSeconds", 111.0);
        expected.addProperty("childCpuFinishSeconds", 113.0);
        expected.addProperty("simulationEndSeconds", 130.0);

        JsonObject observed = new JsonObject();
        observed.addProperty("parentAssignmentSeconds", parent.getSimulationTime());
        observed.addProperty("fillerAssignmentSeconds", filler.getSimulationTime());
        observed.addProperty("childAssignmentSeconds", child.getSimulationTime());
        observed.addProperty("parentSelectedVm", selectedVm(parent));
        observed.addProperty("fillerSelectedVm", selectedVm(filler));
        observed.addProperty("childSelectedVm", selectedVm(child));
        candidateObserved(observed, "parentSlow", parent, 3);
        candidateObserved(observed, "parentFast", parent, 9);
        candidateObserved(observed, "fillerSlow", filler, 3);
        candidateObserved(observed, "fillerFast", filler, 9);
        candidateObserved(observed, "childSlow", child, 3);
        candidateObserved(observed, "childFast", child, 9);

        require(
                successfulJob(report, 1).getVmId() == 9
                        && successfulTask(report, 1).getVmId() == 9,
                "Parent Task/Job binding does not match the selected fast VM");
        require(
                successfulJob(report, 2).getVmId() == 9
                        && successfulTask(report, 2).getVmId() == 9,
                "Filler Task/Job binding does not match the selected fast VM");
        require(
                successfulJob(report, 3).getVmId() == 3
                        && successfulTask(report, 3).getVmId() == 3,
                "Child Task/Job binding does not match the selected slow VM");
        FileLifecycleEvent admitted = admission(report, "x", null);
        FileLifecycleEvent settled = settlement(report, admitted);
        int childJobId = successfulJob(report, 3).getJobId();
        FileLifecycleEvent ready = oneLifecycle(report, FileLifecycleEvent.Type.JOB_DATA_READY, childJobId);
        FileLifecycleEvent cpu = oneLifecycle(report, FileLifecycleEvent.Type.JOB_CPU_STARTED, childJobId);
        SimulationReport.TaskOutcome childTask = successfulTask(report, 3);
        require(
                payload(admitted)
                                .getAsJsonObject("sourceReplica")
                                .getAsJsonObject("location")
                                .get("vmId")
                                .getAsInt()
                        == 9,
                "Child input did not use the parent's visible replica on VM 9");
        require(
                payload(admitted).get("destinationVmId").getAsInt() == 3,
                "Child input destination differs from its committed VM");
        require(
                child.getSimulationTime() <= admitted.getObservedTime(),
                "Input request preceded the control-ready binding");
        require(
                ready.getObservedTime() == settled.getObservedTime(),
                "Data readiness did not wait for the required remote replica");
        require(
                cpu.getObservedTime() >= settled.getObservedTime(),
                "CPU started before the required remote replica became visible");

        observed.addProperty(
                "remoteInputCopies",
                lifecycle(report, FileLifecycleEvent.Type.COPY_ADMITTED).size());
        observed.addProperty("childInputAdmissionSeconds", admitted.getObservedTime());
        observed.addProperty("childInputVisibleSeconds", effective(report, admitted));
        observed.addProperty("childDataReadySeconds", ready.getObservedTime());
        observed.addProperty("childCpuStartSeconds", childTask.getStartTime());
        observed.addProperty("childCpuFinishSeconds", childTask.getFinishTime());
        observed.addProperty("simulationEndSeconds", report.getMakespan());
        requireExpectations(expected, observed);

        JsonObject events = eventDocument("control-ready-assignment");
        putMain(events, "parentAssignment", parent);
        putMain(events, "fillerAssignment", filler);
        putMain(events, "childAssignment", child);
        putLifecycle(events, "childInputAdmitted", admitted);
        putLifecycle(events, "childInputSettled", settled);
        putLifecycle(events, "childDataReady", ready);
        putLifecycle(events, "childCpuStarted", cpu);
        putMain(events, "childTaskWindow", oneMain(report, SimulationEventType.TASK_EXECUTION_MODELED, childJobId, 3));
        putMain(events, "terminated", oneMain(report, SimulationEventType.SIMULATION_TERMINATED, null, null));
        evidenceFiles.add(new EvidenceFile("control-ready.events.json", events));
        JsonObject evidence = new JsonObject();
        evidencePath(
                evidence, "parentCandidates", "control-ready.events.json", "parentAssignment", "attributes/candidates");
        evidencePath(
                evidence,
                "fillerFastCpuAvailable",
                "control-ready.events.json",
                "fillerAssignment",
                "attributes/candidates/1/cpuAvailableAt");
        evidencePath(
                evidence,
                "childSlowScore",
                "control-ready.events.json",
                "childAssignment",
                "attributes/candidates/0/scoreFinishSeconds");
        evidencePath(
                evidence,
                "childFastNegativeScore",
                "control-ready.events.json",
                "childAssignment",
                "attributes/candidates/1/scoreFinishSeconds");
        evidencePath(evidence, "childSelectedBinding", "control-ready.events.json", "childAssignment", "vmId");
        evidencePath(
                evidence,
                "childInputVisible",
                "control-ready.events.json",
                "childInputSettled",
                "payload/effectiveTime");
        evidencePath(evidence, "childDataReady", "control-ready.events.json", "childDataReady", "observedTime");
        evidencePath(evidence, "childCpuStarted", "control-ready.events.json", "childCpuStarted", "observedTime");
        return caseJson(
                "control-ready-target-score-binding-and-data-gate",
                "CONTROL_READY_ONLINE_ASSIGNMENT_V1 over COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2",
                "At t=100 the 10 s parent chooses 1000 MI/s VM 9 over 500 MI/s VM 3. The 20 s filler"
                        + " sees the parent's reservation: fast finish=max(100,110)+20=130 versus slow 140."
                        + " At t=110 the child sees the started filler reservation through t=130: fast"
                        + " score=max(110,130)+1=131; slow score=max(110+1,110)+2=113. It therefore binds"
                        + " VM 3, transfers the 1 MB input at 1 MB/s from 110 to 111, and only then starts"
                        + " its 2 s CPU interval. VM 9's worse child score 131 is a negative candidate"
                        + " expectation, not a preferred-ranking tuning target.",
                expected,
                observed,
                evidence);
    }

    private static JsonObject tieredEnergyCase(
            Path inputs, Map<String, SimulationReport> reports, List<EvidenceFile> evidenceFiles) throws Exception {
        Path dax = writeDax(
                inputs.resolve("tiered-energy.dax"),
                "<job id=\"sink\" runtime=\"2\"><uses file=\"unused\" link=\"output\"" + " size=\"1000000\"/></job>");
        SimulationConfig config = SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.coherentStorageDataflowV3())
                .dataflowAssignment(DataflowAssignmentConfig.onlineNominal())
                .networkEvidence(NetworkEvidenceConfig.storageLifecycleV3(EVIDENCE_BUDGET))
                .workflowArrivalSeconds(Collections.singletonList(ARRIVAL_SECONDS))
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER)
                .runtimeReferenceMips(1000.0)
                .randomSeed(1L)
                .cloudSimMinEventIntervalSeconds(0.1)
                .build();
        SimulationReport report = new SimulationRunner().run(config, tieredEnergyPlatform());
        reports.put("tiered-energy-drain", report);
        requireComplete(report);

        SimulationReport.JobOutcome job = successfulJob(report, 1);
        SimulationReport.TaskOutcome task = successfulTask(report, 1);
        require(
                job.getTierMips() != null && job.getTierMips().doubleValue() == 500.0,
                "MIN_POWER did not select the declared 500 MI/s tier");
        int usedVm = job.getVmId();
        int unusedVm = usedVm == 4 ? 9 : 4;
        require(usedVm == 4 || usedVm == 9, "Energy fixture used an undeclared VM");
        FileLifecycleEvent output = admission(report, "unused", "OUTPUT");
        FileLifecycleEvent settled = settlement(report, output);
        double usedEnergy =
                report.getEnergyAccounting().getVmEnergyJoules().get(usedVm).doubleValue();
        double unusedEnergy =
                report.getEnergyAccounting().getVmEnergyJoules().get(unusedVm).doubleValue();
        Map<Double, Double> usedTiers =
                report.getMetrics().getPerTierBusySecondsByVm().get(usedVm);
        require(
                usedTiers != null
                        && usedTiers.size() == 2
                        && usedTiers.containsKey(500.0)
                        && usedTiers.containsKey(1000.0),
                "Compute and 110 MI stage-in must occupy their distinct declared tiers: " + usedTiers);
        require(
                !report.getMetrics().getPerTierBusySecondsByVm().containsKey(unusedVm),
                "Unused declared VM acquired a tier-busy interval");

        JsonObject expected = new JsonObject();
        expected.addProperty("taskStartSeconds", 100.0);
        expected.addProperty("taskFinishSeconds", 104.0);
        expected.addProperty("selectedTierMips", 500.0);
        expected.addProperty("selectedTierBusySeconds", 4.0);
        expected.addProperty("defaultTierStageInSeconds", 0.11);
        expected.addProperty("unusedVmBusySeconds", 0.0);
        expected.addProperty("terminalDrainSeconds", 2.0);
        expected.addProperty("simulationEndSeconds", 106.0);
        expected.addProperty("usedVmEnergyJoules", 1149.9);
        expected.addProperty("unusedVmEnergyJoules", 1060.0);
        expected.addProperty("totalEnergyJoules", 2209.9);
        expected.addProperty("meanVmEnergyJoules", 1104.95);
        expected.addProperty("peakPowerWatts", 200.0);
        expected.addProperty("energyMakespanProductJouleSeconds", 234249.4);

        JsonObject observed = new JsonObject();
        observed.addProperty("taskStartSeconds", task.getStartTime());
        observed.addProperty("taskFinishSeconds", task.getFinishTime());
        observed.addProperty("selectedTierMips", job.getTierMips());
        observed.addProperty("selectedTierBusySeconds", usedTiers.get(500.0));
        observed.addProperty("defaultTierStageInSeconds", usedTiers.get(1000.0));
        observed.addProperty(
                "unusedVmBusySeconds",
                report.getMetrics().getVmMetrics().get(unusedVm).getModeledBusyIntervalSeconds());
        observed.addProperty("terminalDrainSeconds", report.getTerminalLifecycleTailSeconds());
        observed.addProperty("simulationEndSeconds", report.getMakespan());
        observed.addProperty("usedVmEnergyJoules", usedEnergy);
        observed.addProperty("unusedVmEnergyJoules", unusedEnergy);
        observed.addProperty("totalEnergyJoules", report.getEnergyAccounting().getTotalEnergyJoules());
        observed.addProperty("meanVmEnergyJoules", report.getEnergyAccounting().getMeanVmEnergyJoules());
        observed.addProperty("peakPowerWatts", report.getEnergyAccounting().getPeakPowerWatts());
        observed.addProperty(
                "energyMakespanProductJouleSeconds",
                report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds());
        require(
                output.getObservedTime() == 104.0 && effective(report, output) == 106.0,
                "Declared terminal output interval [104,106] changed");
        requireExpectations(expected, observed);

        JsonObject events = eventDocument("tiered-energy-drain");
        putMain(events, "taskWindow", oneMain(report, SimulationEventType.TASK_EXECUTION_MODELED, job.getJobId(), 1));
        putLifecycle(events, "outputAdmitted", output);
        putLifecycle(events, "outputSettled", settled);
        putMain(events, "terminated", oneMain(report, SimulationEventType.SIMULATION_TERMINATED, null, null));
        JsonObject accounting = new JsonObject();
        accounting.addProperty("usedVmId", usedVm);
        accounting.addProperty("unusedVmId", unusedVm);
        accounting.addProperty("usedVmEnergyJoules", usedEnergy);
        accounting.addProperty("unusedVmEnergyJoules", unusedEnergy);
        accounting.addProperty("totalEnergyJoules", report.getEnergyAccounting().getTotalEnergyJoules());
        accounting.addProperty(
                "meanVmEnergyJoules", report.getEnergyAccounting().getMeanVmEnergyJoules());
        accounting.addProperty("peakPowerWatts", report.getEnergyAccounting().getPeakPowerWatts());
        accounting.addProperty(
                "energyMakespanProductJouleSeconds",
                report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds());
        accounting.add("perTierBusySeconds", JSON.toJsonTree(usedTiers).getAsJsonObject());
        events.add("accounting", accounting);
        JsonObject intervals = new JsonObject();
        intervals.addProperty("defaultIdle", "[0,106] on each declared VM at 10 W");
        intervals.addProperty("usedLowTier", "[100,104] at 500 MI/s and 30 W maximum");
        intervals.addProperty("usedDefaultTierStageIn", "110 MI / 1000 MI/s = 0.11 s at 100 W maximum");
        intervals.addProperty("unusedVm", "no compute or stage-in interval; default-tier idle only");
        intervals.addProperty("terminalOutput", "[104,106] at 0.5 MB/s for 1000000 bytes");
        events.add("declaredIntervals", intervals);
        evidenceFiles.add(new EvidenceFile("tiered-energy.events.json", events));
        JsonObject evidence = new JsonObject();
        evidencePath(evidence, "taskStart", "tiered-energy.events.json", "taskWindow", "attributes/taskStartTime");
        evidencePath(evidence, "taskFinish", "tiered-energy.events.json", "taskWindow", "attributes/taskFinishTime");
        evidencePath(
                evidence, "terminalDrainEnd", "tiered-energy.events.json", "outputSettled", "payload/effectiveTime");
        evidencePath(evidence, "simulationEnd", "tiered-energy.events.json", "terminated", "simulationTime");
        evidence.addProperty("usedEnergy", "tiered-energy.events.json#/accounting/usedVmEnergyJoules");
        evidence.addProperty("unusedEnergy", "tiered-energy.events.json#/accounting/unusedVmEnergyJoules");
        evidence.addProperty("totalEnergy", "tiered-energy.events.json#/accounting/totalEnergyJoules");
        evidence.addProperty("lowTierBusy", "tiered-energy.events.json#/accounting/perTierBusySeconds/500.0");
        evidence.addProperty(
                "defaultTierStageInBusy", "tiered-energy.events.json#/accounting/perTierBusySeconds/1000.0");
        return caseJson(
                "two-tier-default-idle-unused-vm-and-terminal-drain",
                "MIN_POWER on two identical two-tier VMs with required V3 output write-back",
                "Arrival t=100 makes the intervals absolute. The 2000 MI task occupies the selected"
                        + " 500 MI/s tier for 4 s, [100,104]. Its unused 1 MB output takes 2 s at"
                        + " 0.5 MB/s, so simulation end is 106 and terminal drain is 2 s. Default-tier"
                        + " idle is 10 W over all 106 s on BOTH declared VMs. The used VM adds low-tier"
                        + " (30-10)*4=80 J and the contract's 110 MI / 1000 MI/s = 0.11 s stage-in at"
                        + " default-tier dynamic (100-10)*0.11=9.9 J. E_used=10*106+80+9.9=1149.9 J,"
                        + " E_unused=10*106=1060 J, E_total=2209.9 J, mean=1104.95 J,"
                        + " peak=2*100=200 W, EDP=2209.9*106=234249.4 J*s. Both tiers are exercised."
                        + " The low tier's distinct 1 W idle declaration must not replace default idle.",
                expected,
                observed,
                evidence);
    }

    private static JsonObject repeatedCheckpointCase(
            Path inputs, Map<String, SimulationReport> reports, List<EvidenceFile> evidenceFiles) throws Exception {
        Path dax = writeDax(
                inputs.resolve("crch-two-checkpoints.dax"),
                "<job id=\"parent\" name=\"parent\" runtime=\"3.0\"/>"
                        + "<job id=\"child\" name=\"child\" runtime=\"1\"/>"
                        + "<child ref=\"child\"><parent ref=\"parent\"/></child>");
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        // FGCS-R5 (2026-11-24): a failure now ends the attempt at the arrival, so the root stream is
        // deterministic (Weibull shape 1e300 samples exactly the scale): VM 3 root-depth arrivals at
        // 102.5, 205, ... s, the first inside the root's compute window [100, 103.2).
        specs.put(3, new DistributionSpec[] {weibull(1.0e9), weibull(102.5), weibull(1.0e9)});
        specs.put(8, new DistributionSpec[] {weibull(1.0e9), weibull(1.0e9), weibull(1.0e9)});
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM_JOB)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(1)
                .build();
        SimulationConfig config = SimulationConfig.builder(dax.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                .failureModel(failure)
                .crchConfig(new CrchConfig(1.0, 0.1))
                .workflowArrivalSeconds(Collections.singletonList(ARRIVAL_SECONDS))
                .runtimeReferenceMips(1000.0)
                .randomSeed(7L)
                .cloudSimMinEventIntervalSeconds(0.1)
                .build();
        SimulationReport report = new SimulationRunner().run(config, recoveryPlatform());
        reports.put("crch-two-checkpoints", report);
        require(report.isWorkflowCompletedSuccessfully(), "Two-checkpoint workflow did not complete");
        require(
                report.getFailedJobs() == 1 && report.getMetrics().getRetryJobCreatedCount() == 1,
                "Deterministic injection must fail and retry the parent exactly once: failed="
                        + report.getFailedJobs() + " retries="
                        + report.getMetrics().getRetryJobCreatedCount()
                        + " tasks=" + describeTasks(report));

        SimulationEvent retryCreated = oneMain(report, SimulationEventType.RETRY_JOB_CREATED, null, null);
        int failedJobId = ((Number) retryCreated.getAttributes().get("failedJobId")).intValue();
        int retryJobId = retryCreated.getJobId();
        SimulationReport.TaskOutcome failedParent = task(report, 1, failedJobId);
        SimulationReport.TaskOutcome retryParent = task(report, 1, retryJobId);
        require(failedParent.getTaskStatus() == Cloudlet.FAILED, "Wrong failed parent attempt");
        require(retryParent.getTaskStatus() == Cloudlet.SUCCESS, "Parent retry did not succeed");
        require(
                failedParent.getVmId() == 3 && retryParent.getVmId() == 3,
                "CRCH did not resume the retry on checkpointing VM 3");
        double elapsed = failedParent.getFinishTime() - failedParent.getStartTime();
        require(
                elapsed + EPSILON >= 2.2 && elapsed + EPSILON < 3.2,
                "Parent compute no longer ends between the second checkpoint (2.2 s) and completion (3.2 s): "
                        + elapsed);
        require(failedParent.getEffectiveExecutionLengthMi() == 3200L, "Initial parent attempt MI changed");
        require(
                retryParent.getEffectiveExecutionLengthMi() == 1000L,
                "Two-checkpoint remaining MI is wrong: failedJob=" + failedJobId + " retryJob=" + retryJobId
                        + " failedStart=" + failedParent.getStartTime() + " failedFinish="
                        + failedParent.getFinishTime() + " failedWork=" + failedParent.getEffectiveExecutionLengthMi()
                        + " retryWork=" + retryParent.getEffectiveExecutionLengthMi());
        // Independent of the production model: a 3 s task takes a checkpoint after 1 s and 2 s of
        // progress (none at completion); checkpoint k completes k * (1.0 + 0.1) s into compute.
        long plannedCheckpoints = 2L;
        long observedCheckpoints = Math.min(plannedCheckpoints, (long) Math.floor(elapsed / 1.1 + EPSILON));
        long observedSavedMi = observedCheckpoints * 1000L;
        SimulationEvent failed = oneMain(report, SimulationEventType.JOB_FAILED, failedJobId, null);
        SimulationEvent returned = oneMain(report, SimulationEventType.JOB_RETURNED, retryJobId, null);
        SimulationEvent childReady = oneMain(report, SimulationEventType.JOB_READY, null, 2);
        SimulationReport.TaskOutcome child = successfulTask(report, 2);
        require(failed.getSequence() < retryCreated.getSequence(), "Retry preceded failure evidence");
        require(retryCreated.getSequence() < returned.getSequence(), "Retry returned before creation");
        require(returned.getSequence() < childReady.getSequence(), "Failed parent released its child");
        require(
                child.getStartTime() + EPSILON >= retryParent.getFinishTime(),
                "Child compute preceded the successful retry");

        JsonObject expected = new JsonObject();
        expected.addProperty("failedParentAttempts", 1);
        expected.addProperty("retryJobsCreated", 1);
        expected.addProperty("failedParentStartSeconds", 100.0);
        expected.addProperty("failedParentAttemptMi", 3200);
        expected.addProperty("failedParentFinishSeconds", 102.5);
        expected.addProperty("committedCheckpoints", 2);
        expected.addProperty("thirdCheckpointPlanned", 0);
        expected.addProperty("savedWorkMi", 2000);
        expected.addProperty("remainingWorkMi", 1000);
        expected.addProperty("retryEffectiveWorkMi", 1000);
        expected.addProperty("retryExecutionSeconds", 1.0);
        expected.addProperty("retryFinishSeconds", 103.5);
        expected.addProperty("retryResumesOnCheckpointingVm", 1);
        expected.addProperty("childReleaseAfterSuccessfulRetry", 1);
        expected.addProperty("childReadySeconds", 103.5);

        JsonObject observed = new JsonObject();
        observed.addProperty("failedParentAttempts", report.getFailedJobs());
        observed.addProperty("retryJobsCreated", report.getMetrics().getRetryJobCreatedCount());
        observed.addProperty("failedParentStartSeconds", failedParent.getStartTime());
        observed.addProperty("failedParentAttemptMi", failedParent.getEffectiveExecutionLengthMi());
        observed.addProperty("failedParentFinishSeconds", failedParent.getFinishTime());
        observed.addProperty("committedCheckpoints", observedCheckpoints);
        observed.addProperty(
                "thirdCheckpointPlanned", (failedParent.getEffectiveExecutionLengthMi() - 3000L) / 100L >= 3L ? 1 : 0);
        observed.addProperty("savedWorkMi", observedSavedMi);
        observed.addProperty("remainingWorkMi", 3000L - observedSavedMi);
        observed.addProperty("retryEffectiveWorkMi", retryParent.getEffectiveExecutionLengthMi());
        observed.addProperty("retryExecutionSeconds", retryParent.getFinishTime() - retryParent.getStartTime());
        observed.addProperty("retryFinishSeconds", retryParent.getFinishTime());
        observed.addProperty("retryResumesOnCheckpointingVm", failedParent.getVmId() == retryParent.getVmId() ? 1 : 0);
        observed.addProperty(
                "childReleaseAfterSuccessfulRetry", returned.getSequence() < childReady.getSequence() ? 1 : 0);
        observed.addProperty("childReadySeconds", childReady.getSimulationTime());
        requireExpectations(expected, observed);

        JsonObject events = eventDocument("crch-two-checkpoints");
        putMain(
                events,
                "failedTaskWindow",
                oneMain(report, SimulationEventType.TASK_EXECUTION_MODELED, failedJobId, 1));
        putMain(events, "parentFailed", failed);
        putMain(events, "retryCreated", retryCreated);
        putMain(events, "retryTaskWindow", oneMain(report, SimulationEventType.TASK_EXECUTION_MODELED, retryJobId, 1));
        putMain(events, "successfulParentReturned", returned);
        putMain(events, "childReady", childReady);
        evidenceFiles.add(new EvidenceFile("crch-two-checkpoints.events.json", events));
        JsonObject evidence = new JsonObject();
        evidencePath(
                evidence,
                "failedWork",
                "crch-two-checkpoints.events.json",
                "failedTaskWindow",
                "attributes/effectiveExecutionLengthMi");
        evidencePath(
                evidence,
                "failedStart",
                "crch-two-checkpoints.events.json",
                "failedTaskWindow",
                "attributes/taskStartTime");
        evidencePath(
                evidence,
                "failedFinish",
                "crch-two-checkpoints.events.json",
                "failedTaskWindow",
                "attributes/taskFinishTime");
        evidencePath(
                evidence,
                "retryWork",
                "crch-two-checkpoints.events.json",
                "retryTaskWindow",
                "attributes/effectiveExecutionLengthMi");
        evidencePath(
                evidence,
                "retryFinish",
                "crch-two-checkpoints.events.json",
                "retryTaskWindow",
                "attributes/taskFinishTime");
        evidencePath(evidence, "childRelease", "crch-two-checkpoints.events.json", "childReady", "sequence");
        evidencePath(evidence, "parentFailure", "crch-two-checkpoints.events.json", "parentFailed", "sequence");
        evidencePath(
                evidence,
                "successfulParentReturn",
                "crch-two-checkpoints.events.json",
                "successfulParentReturned",
                "sequence");
        return caseJson(
                "crch-two-checkpoints-and-third-checkpoint-boundary",
                "CRCH with fixed seed 7 and VM/depth-keyed deterministic injection",
                "The root is 3000 MI at 1000 MI/s and starts at the declared t=100 arrival. With"
                        + " lambda=1 s and gamma=0.1 s it takes checkpoints after 1 s and 2 s of progress (none at"
                        + " completion), so the attempt executes 3000+2*100=3200 MI over [100,103.2). Checkpoint k"
                        + " completes k*1.1 s into compute. The VM 3 arrival at 102.5 ends the attempt after 2.5 s:"
                        + " two checkpoints (1.1, 2.2) save 2000 MI on VM 3, leaving 1000 MI. At 102.5 resuming on"
                        + " VM 3 costs 1000 MI with no checkpoint (1.0 s) while VM 8 would re-execute 3200 MI"
                        + " (3.2 s), so the retry stays on VM 3 over [102.5,103.5]. No restore term is charged."
                        + " The child cannot be released by the failed attempt and becomes ready at 103.5.",
                expected,
                observed,
                evidence);
    }

    private static PlatformProfile assignmentPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("round2-control-ready");
        int[] ids = {3, 9};
        double[] mips = {500.0, 1000.0};
        for (int index = 0; index < ids.length; index++) {
            int hostId = 13 + index * 6;
            builder.addHost(new PlatformProfile.HostSpec(hostId, 2, 2000.0, 4096, 2L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    ids[index],
                    mips[index],
                    1,
                    512,
                    1L,
                    10000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(ids[index], hostId);
        }
        return builder.build();
    }

    private static PlatformProfile tieredEnergyPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("round2-tiered-energy");
        List<PlatformProfile.FrequencyTier> tiers = Arrays.asList(
                new PlatformProfile.FrequencyTier(1000.0, 10.0, 100.0),
                new PlatformProfile.FrequencyTier(500.0, 1.0, 30.0));
        for (int vmId : new int[] {4, 9}) {
            int hostId = 40 + vmId;
            builder.addHost(new PlatformProfile.HostSpec(hostId, 2, 2000.0, 4096, 2L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    vmId,
                    1000.0,
                    1,
                    512,
                    1L,
                    10000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                    null,
                    0.0,
                    0.0,
                    null,
                    tiers));
            builder.pinVmToHost(vmId, hostId);
        }
        builder.addHost(new PlatformProfile.HostSpec(30, 2, 2000.0, 4096, 2L, 1000000L));
        builder.sourceStorage(DataflowStorageSpec.of(30, 1.0, 0.5, 100.0));
        return builder.build();
    }

    private static PlatformProfile recoveryPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("round2-crch-two-checkpoints");
        for (int vmId : new int[] {3, 8}) {
            int hostId = 30 + vmId;
            builder.addHost(new PlatformProfile.HostSpec(hostId, 2, 2000.0, 4096, 10000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    vmId, 1000.0, 1, 512, 100L, 10000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(vmId, hostId);
        }
        return builder.build();
    }

    private static DistributionSpec weibull(double scale) {
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, scale, 1.0e300);
    }

    private static Path writeDax(Path path, String body) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\" version=\"3.3\">"
                + body + "</adag>\n";
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void requireComplete(SimulationReport report) {
        require(report.isWorkflowCompletedSuccessfully(), "Contract workflow did not complete successfully");
        if (report.getStorageLifecycleEvidence() != null) {
            require(
                    report.getStorageLifecycleEvidence().getCapture().getDroppedRecords() == 0L,
                    "Truncated storage lifecycle");
        } else {
            require(report.getFileLifecycleEvidence() != null, "Missing coherent lifecycle evidence");
            require(report.getFileLifecycleEvidence().getDroppedRecords() == 0L, "Truncated file lifecycle");
        }
    }

    private static SimulationEvent assignment(SimulationReport report, int taskId) {
        return oneMain(report, SimulationEventType.DATAFLOW_VM_ASSIGNED, null, taskId);
    }

    private static int selectedVm(SimulationEvent action) {
        return attributes(action).get("selectedVmId").getAsInt();
    }

    private static JsonObject attributes(SimulationEvent action) {
        return JSON.toJsonTree(action.getAttributes()).getAsJsonObject();
    }

    private static JsonObject candidate(SimulationEvent action, int vmId) {
        return candidate(attributes(action).getAsJsonArray("candidates"), vmId);
    }

    private static JsonObject candidate(JsonArray candidates, int vmId) {
        for (JsonElement element : candidates) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get("vmId").getAsInt() == vmId) {
                return candidate;
            }
        }
        return null;
    }

    private static void candidateExpected(
            JsonObject expected, String prefix, double input, double cpu, double compute, double finish) {
        expected.addProperty(prefix + "InputSeconds", input);
        expected.addProperty(prefix + "CpuAvailableSeconds", cpu);
        expected.addProperty(prefix + "ComputeSeconds", compute);
        expected.addProperty(prefix + "ScoreFinishSeconds", finish);
    }

    private static void candidateObserved(JsonObject observed, String prefix, SimulationEvent action, int vmId) {
        JsonObject candidate = candidate(action, vmId);
        require(candidate != null, "Missing candidate VM " + vmId);
        observed.add(prefix + "InputSeconds", candidate.get("inputSeconds"));
        observed.add(prefix + "CpuAvailableSeconds", candidate.get("cpuAvailableAt"));
        observed.add(prefix + "ComputeSeconds", candidate.get("computeSeconds"));
        observed.add(prefix + "ScoreFinishSeconds", candidate.get("scoreFinishSeconds"));
    }

    private static FileLifecycleEvent admission(SimulationReport report, String file, String purpose) {
        FileLifecycleEvent match = null;
        for (FileLifecycleEvent event : lifecycle(report, FileLifecycleEvent.Type.COPY_ADMITTED)) {
            JsonObject payload = payload(event);
            if (!file.equals(payload.getAsJsonObject("fileId").get("name").getAsString())
                    || (purpose != null
                            && !purpose.equals(payload.get("purpose").getAsString()))) {
                continue;
            }
            require(match == null, "Multiple copies make the " + file + " fixture ambiguous");
            match = event;
        }
        require(match != null, "Missing required copy for " + file);
        return match;
    }

    private static FileLifecycleEvent settlement(SimulationReport report, FileLifecycleEvent admitted) {
        long ordinal = payload(admitted).get("copyOrdinal").getAsLong();
        for (FileLifecycleEvent event : lifecycle(report, FileLifecycleEvent.Type.COPY_SETTLED)) {
            if (payload(event).get("copyOrdinal").getAsLong() == ordinal) {
                return event;
            }
        }
        throw new IllegalStateException("Missing settlement for copy " + ordinal);
    }

    private static double effective(SimulationReport report, FileLifecycleEvent admitted) {
        return payload(settlement(report, admitted)).get("effectiveTime").getAsDouble();
    }

    private static FileLifecycleEvent oneLifecycle(
            SimulationReport report, FileLifecycleEvent.Type type, Integer jobId) {
        FileLifecycleEvent match = null;
        for (FileLifecycleEvent event : lifecycle(report, type)) {
            if (jobId != null && payload(event).get("jobId").getAsInt() != jobId.intValue()) {
                continue;
            }
            require(match == null, "Duplicate lifecycle event " + type);
            match = event;
        }
        require(match != null, "Missing lifecycle event " + type);
        return match;
    }

    private static List<FileLifecycleEvent> lifecycle(SimulationReport report, FileLifecycleEvent.Type type) {
        List<FileLifecycleEvent> source = report.getStorageLifecycleEvidence() == null
                ? report.getFileLifecycleEvidence().getEvents()
                : report.getStorageLifecycleEvidence().getCapture().getEvents();
        List<FileLifecycleEvent> result = new ArrayList<FileLifecycleEvent>();
        for (FileLifecycleEvent event : source) {
            if (event.getType() == type) {
                result.add(event);
            }
        }
        return result;
    }

    private static SimulationEvent oneMain(
            SimulationReport report, SimulationEventType type, Integer jobId, Integer taskId) {
        SimulationEvent match = null;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() != type
                    || (jobId != null && !jobId.equals(event.getJobId()))
                    || (taskId != null && !event.getTaskIds().contains(taskId))) {
                continue;
            }
            require(match == null, "Duplicate main event " + type + " for task " + taskId);
            match = event;
        }
        require(match != null, "Missing main event " + type + " for task " + taskId);
        return match;
    }

    private static SimulationReport.JobOutcome successfulJob(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getTaskIds().contains(taskId) && job.getStatus() == Cloudlet.SUCCESS) {
                return job;
            }
        }
        throw new IllegalStateException("Missing successful compute job for Task " + taskId);
    }

    private static SimulationReport.TaskOutcome successfulTask(SimulationReport report, int taskId) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId && task.getTaskStatus() == Cloudlet.SUCCESS) {
                return task;
            }
        }
        throw new IllegalStateException("Missing successful Task " + taskId);
    }

    private static SimulationReport.TaskOutcome task(SimulationReport report, int taskId, int jobId) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId && task.getJobId() == jobId) {
                return task;
            }
        }
        throw new IllegalStateException("Missing Task " + taskId + " for Job " + jobId);
    }

    private static JsonObject payload(FileLifecycleEvent event) {
        return event.getPayload();
    }

    private static JsonObject eventDocument(String runId) {
        JsonObject document = new JsonObject();
        document.addProperty("schema", "fgcs-round2-contract-events-v1");
        document.addProperty("runId", runId);
        document.add("events", new JsonObject());
        return document;
    }

    private static void putLifecycle(JsonObject document, String name, FileLifecycleEvent event) {
        JsonObject value = new JsonObject();
        value.addProperty("sequence", event.getSequence());
        value.addProperty("observedTime", event.getObservedTime());
        value.addProperty("type", event.getType().name());
        value.add("payload", payload(event));
        document.getAsJsonObject("events").add(name, value);
    }

    private static void putMain(JsonObject document, String name, SimulationEvent event) {
        JsonObject value = new JsonObject();
        value.addProperty("sequence", event.getSequence());
        value.addProperty("simulationTime", event.getSimulationTime());
        value.addProperty("type", event.getType().name());
        value.addProperty("jobId", event.getJobId());
        value.addProperty("vmId", event.getVmId());
        value.add("taskIds", JSON.toJsonTree(event.getTaskIds()));
        value.add("attributes", JSON.toJsonTree(event.getAttributes()));
        document.getAsJsonObject("events").add(name, value);
    }

    private static void evidencePath(
            JsonObject evidence, String name, String file, String eventName, String fieldPath) {
        evidence.addProperty(name, file + "#/events/" + eventName + "/" + fieldPath);
    }

    private static JsonObject caseJson(
            String id, String model, String derivation, JsonObject expected, JsonObject observed, JsonObject evidence) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("model", model);
        value.addProperty("derivation", derivation);
        value.add("expected", expected);
        value.add("observed", observed);
        value.add("evidence", evidence);
        value.addProperty("passed", true);
        return value;
    }

    private static String describeTasks(SimulationReport report) {
        StringBuilder text = new StringBuilder();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            text.append('[')
                    .append(task.getTaskId())
                    .append(" job=")
                    .append(task.getJobId())
                    .append(" vm=")
                    .append(task.getVmId())
                    .append(" status=")
                    .append(task.getTaskStatus())
                    .append(' ')
                    .append(task.getStartTime())
                    .append("..")
                    .append(task.getFinishTime())
                    .append(" mi=")
                    .append(task.getEffectiveExecutionLengthMi())
                    .append(']');
        }
        return text.toString();
    }

    private static void requireExpectations(JsonObject expected, JsonObject observed) {
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            JsonElement actual = observed.get(entry.getKey());
            require(actual != null, "Missing observed metric " + entry.getKey());
            if (entry.getValue().getAsJsonPrimitive().isNumber()) {
                require(actual.getAsJsonPrimitive().isNumber(), "Non-numeric observed metric " + entry.getKey());
                requireClose(entry.getValue().getAsDouble(), actual.getAsDouble(), "Contract metric " + entry.getKey());
            } else {
                require(entry.getValue().equals(actual), "Contract metric " + entry.getKey() + " differs");
            }
        }
    }

    private static void requireClose(double expected, double actual, String message) {
        double tolerance = EPSILON * Math.max(1.0, Math.abs(expected));
        require(
                Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
                message + ": expected=" + expected + " observed=" + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Invalid FGCS semantic contract: " + message);
        }
    }

    private static void writeJson(Path path, JsonObject value) throws IOException {
        Files.write(path, (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final class EvidenceFile {
        private final String name;
        private final JsonObject document;

        private EvidenceFile(String name, JsonObject document) {
            this.name = name;
            this.document = document;
        }
    }

    /** Completed reports and the exact compact document retained on disk. */
    public static final class Suite {
        private final Map<String, SimulationReport> reports;
        private final JsonObject document;

        private Suite(Map<String, SimulationReport> reports, JsonObject document) {
            this.reports = Collections.unmodifiableMap(new LinkedHashMap<String, SimulationReport>(reports));
            this.document = document.deepCopy();
        }

        public SimulationReport getReport(String id) {
            SimulationReport report = reports.get(id);
            if (report == null) {
                throw new IllegalArgumentException("Unknown semantic-contract report " + id);
            }
            return report;
        }

        public JsonObject getDocument() {
            return document.deepCopy();
        }
    }
}
