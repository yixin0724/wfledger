package org.workflowsim.experiments.fgcs.microbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
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
 * Small, analytically solvable end-to-end model checks for the FGCS revision.
 *
 * <p>The expected values below are derived from the public dataflow and CRCH contracts. Absolute
 * event-observation times are deliberately not treated as analytic service times: network oracles
 * use the effective settlement time relative to admission, while lifecycle and recovery oracles
 * use event causality and model-derived Task windows. A changed mapping, missing failure, incomplete
 * capture, or numeric mismatch invalidates a fixture and aborts the suite rather than producing a
 * successful report with weakened semantics.
 */
public final class ModelMicrobenchmarks {

    private static final double EPSILON = 1.0e-9;
    private static final int EVIDENCE_BUDGET = 2000;
    private static final Gson JSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private ModelMicrobenchmarks() {}

    /** Runs all scenarios serially and writes results only after every oracle has passed. */
    public static Suite run(Path outputDirectory) throws Exception {
        if (outputDirectory == null) {
            throw new IllegalArgumentException("Microbenchmark output directory is required");
        }
        Files.createDirectories(outputDirectory);
        Path resultPath = outputDirectory.resolve("results.json");
        Files.deleteIfExists(resultPath);
        Path inputs = outputDirectory.resolve("inputs");
        Files.createDirectories(inputs);

        Map<String, SimulationReport> reports = new LinkedHashMap<String, SimulationReport>();
        List<EvidenceFile> evidenceFiles = new ArrayList<EvidenceFile>();
        JsonArray cases = new JsonArray();
        cases.add(bottleneckCase(inputs, reports, evidenceFiles));
        cases.add(replicaCase(inputs, reports, evidenceFiles));
        cases.add(writebackCase(inputs, reports, evidenceFiles));
        cases.add(recoveryCase(inputs, reports, evidenceFiles));

        JsonObject document = new JsonObject();
        document.addProperty("schema", "fgcs-model-microbench-v1");
        document.addProperty("execution", "ACTUAL_SIMULATION_SERIAL_SESSION");
        document.addProperty("timeUnit", "simulated seconds");
        document.addProperty("workUnit", "MI");
        document.addProperty("byteUnit", "decimal bytes; rates are bytes/second");
        JsonArray contracts = new JsonArray();
        contracts.add("docs/advanced/COHERENT_DATAFLOW_CONTRACT.md");
        contracts.add("docs/advanced/STORAGE_DATAFLOW_CONTRACT.md");
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
        Path output = args.length == 0 ? Paths.get("paper/tmp/fgcs-revision/microbench") : Paths.get(args[0]);
        Suite suite = run(output);
        System.out.println("FGCS model microbenchmarks passed="
                + suite.getDocument().getAsJsonArray("cases").size() + " output="
                + output.toAbsolutePath().normalize());
    }

    private static JsonObject bottleneckCase(
            Path inputs, Map<String, SimulationReport> reports, List<EvidenceFile> evidenceFiles) throws Exception {
        Path dax = writeDax(
                inputs.resolve("bottleneck.dax"),
                "<job id=\"small\" runtime=\"1\"><uses file=\"small\" link=\"input\""
                        + " size=\"2000000\"/></job><job id=\"large\" runtime=\"1\"><uses file=\"large\""
                        + " link=\"input\" size=\"6000000\"/></job>");
        PlatformProfile platform =
                platform("microbench-bottleneck", new int[] {0, 1}, 100L, DataflowStorageSpec.of(30, 1.0, 1.0, 100.0));
        SimulationReport shared = new SimulationRunner()
                .run(coherentConfig(dax, 2, DataMovementModel.coherentStorageDataflowV3()), platform);
        SimulationReport independent = new SimulationRunner()
                .run(coherentConfig(dax, 2, DataMovementModel.coherentStorageDataflowNoContentionV3()), platform);
        reports.put("bottleneck-shared", shared);
        reports.put("bottleneck-independent", independent);
        requireComplete(shared);
        requireComplete(independent);

        FileLifecycleEvent ss = admission(shared, "small", "INPUT");
        FileLifecycleEvent sl = admission(shared, "large", "INPUT");
        FileLifecycleEvent is = admission(independent, "small", "INPUT");
        FileLifecycleEvent il = admission(independent, "large", "INPUT");
        requireClose(ss.getObservedTime(), sl.getObservedTime(), "Shared flows must be concurrent");
        requireClose(is.getObservedTime(), il.getObservedTime(), "Independent flows must be concurrent");
        requireClose(1.0e6, payload(ss).get("standaloneRate").getAsDouble(), "Small-flow isolated rate");
        requireClose(1.0e6, payload(sl).get("standaloneRate").getAsDouble(), "Large-flow isolated rate");
        requireResource(ss, "STORE:source:READ");
        requireResource(sl, "STORE:source:READ");

        double sharedStart = ss.getObservedTime();
        double independentStart = is.getObservedTime();
        JsonObject expected = new JsonObject();
        expected.addProperty("sharedAdmissionDeltaSeconds", 0.0);
        expected.addProperty("sharedSmallServiceSeconds", 4.0);
        expected.addProperty("sharedLargeServiceSeconds", 8.0);
        expected.addProperty("independentAdmissionDeltaSeconds", 0.0);
        expected.addProperty("independentSmallServiceSeconds", 2.0);
        expected.addProperty("independentLargeServiceSeconds", 6.0);
        JsonObject observed = new JsonObject();
        observed.addProperty("sharedAdmissionDeltaSeconds", sl.getObservedTime() - sharedStart);
        observed.addProperty("sharedSmallServiceSeconds", effective(shared, ss) - sharedStart);
        observed.addProperty("sharedLargeServiceSeconds", effective(shared, sl) - sharedStart);
        observed.addProperty("independentAdmissionDeltaSeconds", il.getObservedTime() - independentStart);
        observed.addProperty("independentSmallServiceSeconds", effective(independent, is) - independentStart);
        observed.addProperty("independentLargeServiceSeconds", effective(independent, il) - independentStart);
        observed.addProperty("sharedMakespanSeconds", shared.getMakespan());
        observed.addProperty("independentMakespanSeconds", independent.getMakespan());
        requireExpectations(expected, observed);

        JsonObject sharedEvents = eventDocument("bottleneck-shared");
        putLifecycle(sharedEvents, "smallAdmitted", ss);
        putLifecycle(sharedEvents, "largeAdmitted", sl);
        putLifecycle(sharedEvents, "smallSettled", settlement(shared, ss));
        putLifecycle(sharedEvents, "largeSettled", settlement(shared, sl));
        JsonObject independentEvents = eventDocument("bottleneck-independent");
        putLifecycle(independentEvents, "smallAdmitted", is);
        putLifecycle(independentEvents, "largeAdmitted", il);
        putLifecycle(independentEvents, "smallSettled", settlement(independent, is));
        putLifecycle(independentEvents, "largeSettled", settlement(independent, il));
        evidenceFiles.add(new EvidenceFile("bottleneck-shared.events.json", sharedEvents));
        evidenceFiles.add(new EvidenceFile("bottleneck-independent.events.json", independentEvents));
        JsonObject evidence = new JsonObject();
        evidencePath(
                evidence, "sharedSmallAdmission", "bottleneck-shared.events.json", "smallAdmitted", "observedTime");
        evidencePath(
                evidence, "sharedLargeAdmission", "bottleneck-shared.events.json", "largeAdmitted", "observedTime");
        evidencePath(
                evidence,
                "independentSmallAdmission",
                "bottleneck-independent.events.json",
                "smallAdmitted",
                "observedTime");
        evidencePath(
                evidence,
                "independentLargeAdmission",
                "bottleneck-independent.events.json",
                "largeAdmitted",
                "observedTime");
        evidencePath(evidence, "sharedSmall", "bottleneck-shared.events.json", "smallSettled", "payload/effectiveTime");
        evidencePath(evidence, "sharedLarge", "bottleneck-shared.events.json", "largeSettled", "payload/effectiveTime");
        evidencePath(
                evidence,
                "independentSmall",
                "bottleneck-independent.events.json",
                "smallSettled",
                "payload/effectiveTime");
        evidencePath(
                evidence,
                "independentLarge",
                "bottleneck-independent.events.json",
                "largeSettled",
                "payload/effectiveTime");
        return caseJson(
                "two-flow-progressive-filling",
                "COHERENT_STORAGE_DATAFLOW_V3 versus NO_CONTENTION_V3",
                "C=1 MB/s, Bsmall=2 MB, Blarge=6 MB. Shared rates start at C/2; small ends at 2/(C/2)=4 s, "
                        + "when large has 4 MB left and ends at 4+4/C=8 s. Independent paths take 2 s and 6 s.",
                expected,
                observed,
                evidence);
    }

    private static JsonObject replicaCase(
            Path inputs, Map<String, SimulationReport> reports, List<EvidenceFile> evidenceFiles) throws Exception {
        Path dax = writeDax(
                inputs.resolve("replica-visibility.dax"),
                "<job id=\"parent\" runtime=\"1\"><uses file=\"fast\" link=\"output\""
                        + " size=\"1000000\"/><uses file=\"slow\" link=\"output\" size=\"4000000\"/></job><job"
                        + " id=\"child\" runtime=\"1\"><uses file=\"fast\" link=\"input\""
                        + " size=\"1000000\"/><uses file=\"slow\" link=\"input\""
                        + " size=\"4000000\"/></job><child ref=\"child\"><parent ref=\"parent\"/></child>");
        PlatformProfile platform = platform("microbench-replicas", new int[] {7, 42}, 1L, null);
        SimulationReport report = new SimulationRunner()
                .run(coherentConfig(dax, 2, DataMovementModel.coherentFileDataflowNoContentionV2()), platform);
        reports.put("replica-visibility", report);
        requireComplete(report);
        FileLifecycleEvent fast = admission(report, "fast", null);
        FileLifecycleEvent slow = admission(report, "slow", null);
        requireClose(fast.getObservedTime(), slow.getObservedTime(), "Sibling inputs must start together");
        FileLifecycleEvent fastSettled = settlement(report, fast);
        FileLifecycleEvent slowSettled = settlement(report, slow);
        SimulationReport.JobOutcome parent = successfulJob(report, 1);
        SimulationReport.JobOutcome child = successfulJob(report, 2);
        require(parent.getVmId() != child.getVmId(), "Replica fixture requires a cross-VM child mapping");
        FileLifecycleEvent ready = oneLifecycle(report, FileLifecycleEvent.Type.JOB_DATA_READY, child.getJobId());
        FileLifecycleEvent cpu = oneLifecycle(report, FileLifecycleEvent.Type.JOB_CPU_STARTED, child.getJobId());
        FileLifecycleEvent parentFinished =
                oneLifecycle(report, FileLifecycleEvent.Type.TASK_FINISHED, parent.getJobId());
        require(fastSettled.getObservedTime() < slowSettled.getObservedTime(), "Visibility must be per file");
        requireClose(ready.getObservedTime(), slowSettled.getObservedTime(), "Readiness must await the last input");
        require(cpu.getObservedTime() >= slowSettled.getObservedTime(), "CPU preceded a required visible replica");
        require(ready.getSequence() > fastSettled.getSequence(), "Fast input incorrectly released the child");
        for (FileLifecycleEvent copy : new FileLifecycleEvent[] {fast, slow}) {
            JsonObject source = payload(copy).getAsJsonObject("sourceReplica").getAsJsonObject("location");
            require(source.get("vmId").getAsInt() == parent.getVmId(), "Copy did not use the visible parent replica");
            require(payload(copy).get("destinationVmId").getAsInt() == child.getVmId(), "Wrong replica destination");
        }

        double start = fast.getObservedTime();
        JsonObject expected = new JsonObject();
        expected.addProperty("remoteVisibleReplicaCopies", 2.0);
        expected.addProperty("fastServiceSeconds", 1.0);
        expected.addProperty("slowServiceSeconds", 4.0);
        expected.addProperty("effectiveVisibilityLeadSeconds", 3.0);
        expected.addProperty("dataReadyAtLastSettlement", 1.0);
        expected.addProperty("cpuNotBeforeLastSettlement", 1.0);
        JsonObject observed = new JsonObject();
        observed.addProperty(
                "remoteVisibleReplicaCopies",
                lifecycle(report, FileLifecycleEvent.Type.COPY_ADMITTED).size());
        observed.addProperty("fastServiceSeconds", effective(report, fast) - start);
        observed.addProperty("slowServiceSeconds", effective(report, slow) - start);
        observed.addProperty("effectiveVisibilityLeadSeconds", effective(report, slow) - effective(report, fast));
        observed.addProperty(
                "dataReadyAtLastSettlement", ready.getObservedTime() == slowSettled.getObservedTime() ? 1.0 : 0.0);
        observed.addProperty(
                "cpuNotBeforeLastSettlement", cpu.getObservedTime() >= slowSettled.getObservedTime() ? 1.0 : 0.0);
        observed.addProperty("fastObservedAtSeconds", fastSettled.getObservedTime());
        observed.addProperty("slowObservedAtSeconds", slowSettled.getObservedTime());
        observed.addProperty("cpuStartedAtSeconds", cpu.getObservedTime());
        requireExpectations(expected, observed);

        JsonObject events = eventDocument("replica-visibility");
        putLifecycle(events, "parentFinished", parentFinished);
        putLifecycle(events, "fastAdmitted", fast);
        putLifecycle(events, "slowAdmitted", slow);
        putLifecycle(events, "fastSettled", fastSettled);
        putLifecycle(events, "slowSettled", slowSettled);
        putLifecycle(events, "childDataReady", ready);
        putLifecycle(events, "childCpuStarted", cpu);
        evidenceFiles.add(new EvidenceFile("replica-visibility.events.json", events));
        JsonObject evidence = new JsonObject();
        evidencePath(evidence, "fastVisible", "replica-visibility.events.json", "fastSettled", "observedTime");
        evidencePath(evidence, "slowVisible", "replica-visibility.events.json", "slowSettled", "observedTime");
        evidencePath(evidence, "dataReady", "replica-visibility.events.json", "childDataReady", "observedTime");
        evidencePath(evidence, "cpuStarted", "replica-visibility.events.json", "childCpuStarted", "observedTime");
        evidencePath(
                evidence, "replicaSource", "replica-visibility.events.json", "slowAdmitted", "payload/sourceReplica");
        return caseJson(
                "replica-visibility-and-cpu-barrier",
                "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2",
                "Two remote inputs of 1 MB and 4 MB each have an isolated 1 MB/s path. Their effective"
                        + " services are 1 s and 4 s; the earlier replica is independently visible but cannot"
                        + " release the CPU barrier.",
                expected,
                observed,
                evidence);
    }

    private static JsonObject writebackCase(
            Path inputs, Map<String, SimulationReport> reports, List<EvidenceFile> evidenceFiles) throws Exception {
        Path dax = writeDax(
                inputs.resolve("writeback.dax"),
                "<job id=\"sink\" runtime=\"1\"><uses file=\"sink\" link=\"output\"" + " size=\"1000000\"/></job>");
        SimulationReport writeback = new SimulationRunner()
                .run(
                        coherentConfig(dax, 1, DataMovementModel.coherentStorageDataflowV3()),
                        platform(
                                "microbench-writeback",
                                new int[] {7},
                                100L,
                                DataflowStorageSpec.of(30, 1.0, 0.5, 100.0)));
        SimulationReport control = new SimulationRunner()
                .run(
                        coherentConfig(dax, 1, DataMovementModel.coherentFileDataflowNoContentionV2()),
                        platform("microbench-writeback-control", new int[] {7}, 100L, null));
        reports.put("writeback-drain", writeback);
        reports.put("writeback-control", control);
        requireComplete(writeback);
        requireComplete(control);
        FileLifecycleEvent output = admission(writeback, "sink", "OUTPUT");
        FileLifecycleEvent settled = settlement(writeback, output);
        FileLifecycleEvent finished = oneLifecycle(writeback, FileLifecycleEvent.Type.TASK_FINISHED, null);
        requireClose(finished.getObservedTime(), output.getObservedTime(), "Write-back must start at CPU completion");
        require(settled.getObservedTime() > finished.getObservedTime(), "Positive output had no terminal service");
        require(writeback.getMakespan() >= settled.getObservedTime(), "Simulation ended before output commit");
        require(successfulJob(writeback, 1).getFinishTime() <= finished.getObservedTime(), "CPU return waited for I/O");
        require(
                writeback.getTerminalLifecycleTailSeconds() != null
                        && writeback.getTerminalLifecycleTailSeconds() > 0.0,
                "Required output write-back must create a nonzero terminal drain");
        require(lifecycle(control, FileLifecycleEvent.Type.COPY_ADMITTED).isEmpty(), "V2 control wrote back its sink");
        require(control.getTerminalLifecycleTailSeconds() != null, "V2 control has no logical completion boundary");
        requireClose(0.0, control.getTerminalLifecycleTailSeconds(), "V2 control must not invent a write-back drain");

        JsonObject expected = new JsonObject();
        expected.addProperty("outputWritebackCopies", 1.0);
        expected.addProperty("effectiveWritebackSeconds", 2.0);
        expected.addProperty("terminalDrainIsPositive", 1.0);
        expected.addProperty("controlOutputCopies", 0.0);
        expected.addProperty("controlTerminalDrainSeconds", 0.0);
        JsonObject observed = new JsonObject();
        observed.addProperty("outputWritebackCopies", countPurpose(writeback, "OUTPUT"));
        observed.addProperty("effectiveWritebackSeconds", effective(writeback, output) - output.getObservedTime());
        observed.addProperty("terminalDrainIsPositive", writeback.getTerminalLifecycleTailSeconds() > 0.0 ? 1.0 : 0.0);
        observed.addProperty(
                "controlOutputCopies",
                lifecycle(control, FileLifecycleEvent.Type.COPY_ADMITTED).size());
        observed.addProperty("controlTerminalDrainSeconds", control.getTerminalLifecycleTailSeconds());
        observed.addProperty("writebackTerminalDrainSeconds", writeback.getTerminalLifecycleTailSeconds());
        observed.addProperty("logicalTaskCompletionSeconds", writeback.getLogicalTaskCompletionSeconds());
        observed.addProperty("simulationEndSeconds", writeback.getSimulationEndSeconds());
        observed.addProperty("sourceCommitObservedAtSeconds", settled.getObservedTime());
        requireExpectations(expected, observed);

        JsonObject events = eventDocument("writeback-drain");
        putLifecycle(events, "taskFinished", finished);
        putLifecycle(events, "outputAdmitted", output);
        putLifecycle(events, "outputSettled", settled);
        putMain(
                events,
                "jobReturned",
                oneMain(
                        writeback,
                        SimulationEventType.JOB_RETURNED,
                        successfulJob(writeback, 1).getJobId(),
                        null));
        putMain(events, "terminated", oneMain(writeback, SimulationEventType.SIMULATION_TERMINATED, null, null));
        JsonObject controlEvents = eventDocument("writeback-control");
        putLifecycle(controlEvents, "taskFinished", oneLifecycle(control, FileLifecycleEvent.Type.TASK_FINISHED, null));
        putMain(controlEvents, "terminated", oneMain(control, SimulationEventType.SIMULATION_TERMINATED, null, null));
        evidenceFiles.add(new EvidenceFile("writeback-drain.events.json", events));
        evidenceFiles.add(new EvidenceFile("writeback-control.events.json", controlEvents));
        JsonObject evidence = new JsonObject();
        evidencePath(evidence, "cpuFinished", "writeback-drain.events.json", "taskFinished", "observedTime");
        evidencePath(
                evidence, "outputAdmitted", "writeback-drain.events.json", "outputAdmitted", "payload/isolatedSeconds");
        evidencePath(
                evidence, "outputCommitted", "writeback-drain.events.json", "outputSettled", "payload/effectiveTime");
        evidencePath(evidence, "simulationEnd", "writeback-drain.events.json", "terminated", "simulationTime");
        evidencePath(evidence, "controlEnd", "writeback-control.events.json", "terminated", "simulationTime");
        return caseJson(
                "required-output-terminal-drain",
                "COHERENT_STORAGE_DATAFLOW_V3 with coherent-file V2 control",
                "An unused successful 1 MB output must traverse a 0.5 MB/s write bottleneck: effective"
                        + " service is 2 s. CPU return is unchanged, simulation termination waits for the"
                        + " SOURCE commit, and V2 has no such write-back obligation or terminal drain.",
                expected,
                observed,
                evidence);
    }

    private static JsonObject recoveryCase(
            Path inputs, Map<String, SimulationReport> reports, List<EvidenceFile> evidenceFiles) throws Exception {
        Path dax = writeDax(
                inputs.resolve("crch-retry.dax"),
                "<job id=\"child\" name=\"child\" runtime=\"1\"/>"
                        + "<job id=\"parent\" name=\"parent\" runtime=\"2\"/>"
                        + "<child ref=\"child\"><parent ref=\"parent\"/></child>");
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {weibull(1.0e9), weibull(0.001), weibull(1.0e9)});
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
                .runtimeReferenceMips(1000.0)
                .randomSeed(7L)
                .cloudSimMinEventIntervalSeconds(0.1)
                .build();
        SimulationReport report =
                new SimulationRunner().run(config, platform("microbench-crch", new int[] {3, 8}, 1L, null));
        reports.put("crch-retry", report);
        require(report.isWorkflowCompletedSuccessfully(), "CRCH workflow did not complete successfully");
        require(
                report.getFailedJobs() == 1 && report.getMetrics().getRetryJobCreatedCount() == 1,
                "The fixed-seed CRCH fixture must fail the parent exactly once");

        SimulationEvent retryCreated = oneMain(report, SimulationEventType.RETRY_JOB_CREATED, null, null);
        int failedJobId = ((Number) retryCreated.getAttributes().get("failedJobId")).intValue();
        int retryJobId = retryCreated.getJobId();
        SimulationReport.TaskOutcome failedParent = task(report, 2, failedJobId);
        SimulationReport.TaskOutcome retryParent = task(report, 2, retryJobId);
        require(failedParent.getTaskStatus() == Cloudlet.FAILED, "Wrong failed parent attempt");
        require(retryParent.getTaskStatus() == Cloudlet.SUCCESS, "Retry did not succeed");
        require(failedParent.getVmId() != retryParent.getVmId(), "CRCH retry reused the failed VM");
        double elapsed = failedParent.getFinishTime() - failedParent.getStartTime();
        require(
                elapsed + EPSILON >= 1.1 && elapsed + EPSILON < 2.2,
                "CRCH fixture no longer has exactly one committed checkpoint: elapsed=" + elapsed);
        long expectedSavedMi = 1000L;
        long expectedRemainingMi = 2000L - expectedSavedMi;
        long expectedRetryMi = expectedRemainingMi + 100L;
        require(failedParent.getEffectiveExecutionLengthMi() == 2000L, "Initial parent work changed");
        require(retryParent.getEffectiveExecutionLengthMi() == expectedRetryMi, "CRCH remaining-MI contract failed");

        SimulationEvent failed = oneMain(report, SimulationEventType.JOB_FAILED, failedJobId, null);
        SimulationEvent returned = oneMain(report, SimulationEventType.JOB_RETURNED, retryJobId, null);
        require(
                ((Number) returned.getAttributes().get("jobStatus")).intValue() == Cloudlet.SUCCESS,
                "Successful parent return identifies the wrong attempt");
        SimulationEvent childReady = oneMain(report, SimulationEventType.JOB_READY, null, 1);
        require(failed.getSequence() < retryCreated.getSequence(), "Retry preceded parent failure evidence");
        require(retryCreated.getSequence() < returned.getSequence(), "Retry returned before its creation");
        require(returned.getSequence() < childReady.getSequence(), "Failed parent released the child");
        SimulationReport.TaskOutcome child = successfulTask(report, 1);
        require(child.getStartTime() + EPSILON >= retryParent.getFinishTime(), "Child compute preceded parent success");

        int observedCheckpoints = 0;
        double observedCheckpointedSeconds = 0.0;
        while (observedCheckpointedSeconds + 1.0 + 0.1 <= elapsed + EPSILON) {
            observedCheckpointedSeconds += 1.0;
            observedCheckpoints++;
        }
        long observedSavedMi = Math.min(2000L, (long) Math.floor(observedCheckpointedSeconds * 1000.0 + EPSILON));
        long observedOverheadMi = (long) Math.ceil(0.1 * 1000.0);

        JsonObject expected = new JsonObject();
        expected.addProperty("failedParentAttempts", 1.0);
        expected.addProperty("committedCheckpoints", 1.0);
        expected.addProperty("savedWorkMi", expectedSavedMi);
        expected.addProperty("remainingWorkMi", expectedRemainingMi);
        expected.addProperty("recoveryOverheadMi", 100.0);
        expected.addProperty("retryEffectiveWorkMi", expectedRetryMi);
        expected.addProperty("retryExecutionSeconds", 1.1);
        expected.addProperty("retryUsesAlternativeVm", 1.0);
        expected.addProperty("childReleaseAfterSuccessfulRetry", 1.0);
        JsonObject observed = new JsonObject();
        observed.addProperty("failedParentAttempts", report.getFailedJobs());
        observed.addProperty("committedCheckpoints", observedCheckpoints);
        observed.addProperty("savedWorkMi", observedSavedMi);
        observed.addProperty("remainingWorkMi", retryParent.getEffectiveExecutionLengthMi() - observedOverheadMi);
        observed.addProperty("recoveryOverheadMi", observedOverheadMi);
        observed.addProperty("retryEffectiveWorkMi", retryParent.getEffectiveExecutionLengthMi());
        observed.addProperty("retryExecutionSeconds", retryParent.getFinishTime() - retryParent.getStartTime());
        observed.addProperty("retryUsesAlternativeVm", failedParent.getVmId() != retryParent.getVmId() ? 1.0 : 0.0);
        observed.addProperty(
                "childReleaseAfterSuccessfulRetry", returned.getSequence() < childReady.getSequence() ? 1.0 : 0.0);
        observed.addProperty("failedAttemptElapsedSeconds", elapsed);
        observed.addProperty("failedJobId", failedJobId);
        observed.addProperty("retryJobId", retryJobId);
        observed.addProperty("failedVmId", failedParent.getVmId());
        observed.addProperty("retryVmId", retryParent.getVmId());
        observed.addProperty("failedSequence", failed.getSequence());
        observed.addProperty("retryCreatedSequence", retryCreated.getSequence());
        observed.addProperty("successfulParentReturnSequence", returned.getSequence());
        observed.addProperty("childReadySequence", childReady.getSequence());
        requireExpectations(expected, observed);

        JsonObject events = eventDocument("crch-retry");
        putMain(
                events,
                "failedTaskWindow",
                oneMain(report, SimulationEventType.TASK_EXECUTION_MODELED, failedJobId, null));
        putMain(events, "parentFailed", failed);
        putMain(events, "retryCreated", retryCreated);
        putMain(
                events,
                "retryTaskWindow",
                oneMain(report, SimulationEventType.TASK_EXECUTION_MODELED, retryJobId, null));
        putMain(events, "successfulParentReturned", returned);
        putMain(events, "childReady", childReady);
        putMain(events, "childTaskWindow", oneMain(report, SimulationEventType.TASK_EXECUTION_MODELED, null, 1));
        evidenceFiles.add(new EvidenceFile("crch-retry.events.json", events));
        JsonObject evidence = new JsonObject();
        evidencePath(
                evidence,
                "failedWork",
                "crch-retry.events.json",
                "failedTaskWindow",
                "attributes/effectiveExecutionLengthMi");
        evidencePath(
                evidence,
                "retryWork",
                "crch-retry.events.json",
                "retryTaskWindow",
                "attributes/effectiveExecutionLengthMi");
        evidencePath(evidence, "retryStart", "crch-retry.events.json", "retryTaskWindow", "attributes/taskStartTime");
        evidencePath(evidence, "retryFinish", "crch-retry.events.json", "retryTaskWindow", "attributes/taskFinishTime");
        evidencePath(evidence, "parentFailure", "crch-retry.events.json", "parentFailed", "sequence");
        evidencePath(
                evidence, "successfulParentReturn", "crch-retry.events.json", "successfulParentReturned", "sequence");
        evidencePath(evidence, "childRelease", "crch-retry.events.json", "childReady", "sequence");
        return caseJson(
                "deterministic-crch-parent-retry",
                "CRCH, fixed seed 7, FAILURE_VM_JOB keyed to VMs 3/8 and parent/child depths",
                "The 2000 MI parent runs at 1000 MI/s. Its envelope permits one checkpoint:"
                        + " 1.0+0.1<=elapsed<2.2. Saved=1000 MI, remaining=1000 MI, recovery"
                        + " overhead=ceil(0.1*1000)=100 MI, so retry work is 1100 MI and takes 1.1 s. Only the"
                        + " successful retry may release the child.",
                expected,
                observed,
                evidence);
    }

    private static DistributionSpec weibull(double scale) {
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, scale, 1.0);
    }

    private static SimulationConfig coherentConfig(Path input, int vms, DataMovementModel model) {
        boolean storage = model.isCoherentStorageDataflowV3();
        return SimulationConfig.builder(input.toString(), vms)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .randomSeed(1L)
                .runtimeReferenceMips(1000.0)
                .cloudSimMinEventIntervalSeconds(0.1)
                .dataMovementModel(model)
                .networkEvidence(
                        storage
                                ? NetworkEvidenceConfig.storageLifecycleV3(EVIDENCE_BUDGET)
                                : NetworkEvidenceConfig.fileLifecycleV2(EVIDENCE_BUDGET))
                .build();
    }

    private static PlatformProfile platform(String name, int[] vmIds, long bandwidthMb, DataflowStorageSpec storage) {
        PlatformProfile.Builder builder = PlatformProfile.builder(name);
        for (int index = 0; index < vmIds.length; index++) {
            int vmId = vmIds[index];
            int hostId = 10 + index;
            builder.addHost(new PlatformProfile.HostSpec(hostId, 2, 2000.0, 4096, 10000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    vmId,
                    1000.0,
                    1,
                    512,
                    bandwidthMb,
                    10000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(vmId, hostId);
        }
        if (storage != null) {
            builder.addHost(new PlatformProfile.HostSpec(30, 2, 2000.0, 4096, 10000L, 1000000L));
            builder.sourceStorage(storage);
        }
        return builder.build();
    }

    private static Path writeDax(Path path, String body) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\" version=\"3.3\">"
                + body
                + "</adag>\n";
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void requireComplete(SimulationReport report) {
        require(report.isWorkflowCompletedSuccessfully(), "Microbenchmark workflow did not complete successfully");
        if (report.getStorageLifecycleEvidence() != null) {
            require(
                    report.getStorageLifecycleEvidence().getCapture().getDroppedRecords() == 0L,
                    "Truncated storage lifecycle");
        } else {
            require(report.getFileLifecycleEvidence() != null, "Missing coherent lifecycle evidence");
            require(report.getFileLifecycleEvidence().getDroppedRecords() == 0L, "Truncated file lifecycle");
        }
    }

    private static FileLifecycleEvent admission(SimulationReport report, String file, String purpose) {
        FileLifecycleEvent match = null;
        for (FileLifecycleEvent event : lifecycle(report, FileLifecycleEvent.Type.COPY_ADMITTED)) {
            JsonObject payload = payload(event);
            if (!file.equals(payload.getAsJsonObject("fileId").get("name").getAsString())) {
                continue;
            }
            if (purpose != null && !purpose.equals(payload.get("purpose").getAsString())) {
                continue;
            }
            require(match == null, "Multiple copies make the " + file + " fixture ambiguous");
            match = event;
        }
        require(match != null, "Missing required copy for " + file + "; scenario mapping/model is invalid");
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
            require(match == null, "Duplicate main event " + type);
            match = event;
        }
        require(match != null, "Missing main event " + type);
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

    private static int countPurpose(SimulationReport report, String purpose) {
        int count = 0;
        for (FileLifecycleEvent event : lifecycle(report, FileLifecycleEvent.Type.COPY_ADMITTED)) {
            if (purpose.equals(payload(event).get("purpose").getAsString())) {
                count++;
            }
        }
        return count;
    }

    private static JsonObject payload(FileLifecycleEvent event) {
        return event.getPayload();
    }

    private static void requireResource(FileLifecycleEvent event, String resource) {
        for (JsonElement entry : payload(event).getAsJsonArray("resources")) {
            if (resource.equals(entry.getAsString())) {
                return;
            }
        }
        throw new IllegalStateException("Copy does not use expected bottleneck " + resource);
    }

    private static JsonObject eventDocument(String runId) {
        JsonObject document = new JsonObject();
        document.addProperty("schema", "fgcs-model-microbench-events-v1");
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

    private static void requireExpectations(JsonObject expected, JsonObject observed) {
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            JsonElement actual = observed.get(entry.getKey());
            require(actual != null, "Missing observed metric " + entry.getKey());
            if (entry.getValue().getAsJsonPrimitive().isNumber()) {
                require(actual.getAsJsonPrimitive().isNumber(), "Non-numeric observed metric " + entry.getKey());
                requireClose(
                        entry.getValue().getAsDouble(),
                        actual.getAsDouble(),
                        "Microbenchmark metric " + entry.getKey());
            } else {
                require(entry.getValue().equals(actual), "Microbenchmark metric " + entry.getKey() + " differs");
            }
        }
    }

    private static void requireClose(double expected, double actual, String message) {
        require(
                Double.isFinite(actual) && Math.abs(expected - actual) <= EPSILON,
                message + ": expected=" + expected + " observed=" + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Invalid FGCS microbenchmark: " + message);
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
                throw new IllegalArgumentException("Unknown microbenchmark report " + id);
            }
            return report;
        }

        public JsonObject getDocument() {
            return document.deepCopy();
        }
    }
}
