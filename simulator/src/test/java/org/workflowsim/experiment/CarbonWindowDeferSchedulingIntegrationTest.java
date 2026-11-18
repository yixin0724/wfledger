package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class CarbonWindowDeferSchedulingIntegrationTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void deferralMovesEveryJobStartIntoTheCleanerWindow() throws Exception {
        SimulationReport deferred = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);

        assertEquals(
                2,
                deferred.getMetrics().getSuccessfulComputeJobOutcomeCount(),
                "both tasks must still complete (deferral must not starve the workflow)");
        for (SimulationReport.JobOutcome job : deferred.getJobs()) {
            if (job.getClassType() != 2) {
                continue;
            }
            assertTrue(
                    job.getStartTime() >= 100.0,
                    "the dirty window [0,100) must be skipped: job " + job.getJobId() + " started at "
                            + job.getStartTime());
        }
        assertTrue(deferred.getMakespan() >= 100.0, "the makespan must span the skipped dirty window");
    }

    @Test
    void deferralLowersCarbonExcessAgainstTheCarbonAgnosticBaseline() throws Exception {
        SimulationReport baseline = run(SchedulingAlgorithm.FCFS, true);
        SimulationReport deferred = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);

        double baselineExcess = baseline.getCarbonAccounting().getTotalCarbonExcessWattSeconds();
        double deferredExcess = deferred.getCarbonAccounting().getTotalCarbonExcessWattSeconds();
        assertTrue(baselineExcess > 0.0, "the carbon-agnostic run must incur excess in the dirty window");
        assertEquals(
                0.0,
                deferredExcess,
                "running inside the 400 W green window at 300 W active power must leave zero excess" + " (baseline was "
                        + baselineExcess + ")");
    }

    @Test
    void windowlessPlatformsAreRejectedInsteadOfSilentlyDegrading() throws Exception {
        org.workflowsim.exception.SimulationConfigurationException rejected =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.workflowsim.exception.SimulationConfigurationException.class,
                        () -> run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, false));
        assertTrue(
                rejected.getMessage().contains("carbonWindows"),
                "the refusal must name the missing declaration: " + rejected.getMessage());

        SimulationReport plain = run(SchedulingAlgorithm.FCFS, false);
        assertNull(plain.getCarbonAccounting(), "plain FCFS without windows stays legal and accounting-free");
    }

    @Test
    void eachJobIsDeferredAtMostOnceSoTheWorkflowCannotStarve() throws Exception {
        SimulationReport deferred = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);
        double upperBound = 100.0 + 60.0;
        assertTrue(
                deferred.getMakespan() <= upperBound,
                "one deferral per Job bounds the makespan near the clean-window start + work: "
                        + deferred.getMakespan());
    }

    @Test
    void alreadyCleanWindowsAreNotDeferred() throws Exception {
        SimulationReport baseline = run(SchedulingAlgorithm.FCFS, true, 400.0);
        SimulationReport engine = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true, 400.0);
        assertEquals(
                0,
                Double.compare(baseline.getMakespan(), engine.getMakespan()),
                "the first window is already the cleanest: nothing to gain, so no deferral");
    }

    @Test
    void carbonWindowsDoNotLeakIntoTheNextRun() throws Exception {
        SimulationReport withWindows = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);
        assertTrue(withWindows.getMakespan() >= 100.0, "the first run must defer (sanity)");

        SimulationReport afterLeak = run(SchedulingAlgorithm.FCFS, false);
        double firstStart = afterLeak.getJobs().stream()
                .filter(job -> job.getClassType() == 2)
                .mapToDouble(SimulationReport.JobOutcome::getStartTime)
                .min()
                .orElse(-1.0);
        assertTrue(
                firstStart >= 0.0 && firstStart < 1.0,
                "the second run must start immediately: a leaked window table would push it to 100,"
                        + " observed first start " + firstStart);
    }

    @Test
    void deferralCannotGoBelowTheIdleFloorOfADirtyWindow() throws Exception {
        SimulationReport deferred = runTwoVms(SchedulingAlgorithm.CARBON_WINDOW_DEFER);
        SimulationReport baseline = runTwoVms(SchedulingAlgorithm.FCFS);

        double floor = (2 * 100.0 - 120.0) * 100.0;
        assertTrue(
                deferred.getCarbonAccounting().getTotalCarbonExcessWattSeconds() >= floor - 1e-9,
                "the idle floor of the dirty window is unavoidable: observed "
                        + deferred.getCarbonAccounting().getTotalCarbonExcessWattSeconds());
        assertTrue(
                deferred.getCarbonAccounting().getTotalCarbonExcessWattSeconds()
                        < baseline.getCarbonAccounting().getTotalCarbonExcessWattSeconds(),
                "deferral must still remove the dynamic share that FCFS burns in the dirty window");
    }

    private SimulationReport runTwoVms(SchedulingAlgorithm algorithm) throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "floor-" + algorithm + ".dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"20\"/>"
                                + "<child ref=\"b\"><parent ref=\"a\"/></child></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(algorithm)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261104L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-floor-test");
        for (int id = 0; id < 2; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000, 2048, 10000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id,
                    1000,
                    1,
                    512,
                    1000L,
                    10000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                    null,
                    100.0,
                    300.0,
                    null));
            builder.pinVmToHost(id, id);
        }
        builder.carbonWindow(0.0, 100.0, 120.0);
        builder.carbonWindow(100.0, 400.0, 400.0);
        return new SimulationRunner().run(config, builder.build());
    }

    @Test
    void repeatedRunsProduceIdenticalDeferralDecisions() throws Exception {
        SimulationReport first = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);
        SimulationReport second = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);

        assertEquals(
                0,
                Double.compare(first.getMakespan(), second.getMakespan()),
                "makespan must be reproducible: " + first.getMakespan() + " vs " + second.getMakespan());
        assertEquals(
                0,
                Double.compare(
                        first.getCarbonAccounting().getTotalCarbonExcessWattSeconds(),
                        second.getCarbonAccounting().getTotalCarbonExcessWattSeconds()),
                "carbon excess must be reproducible");
        assertEquals(first.getJobs().size(), second.getJobs().size(), "same job count");
        for (int index = 0; index < first.getJobs().size(); index++) {
            assertEquals(
                    0,
                    Double.compare(
                            first.getJobs().get(index).getStartTime(),
                            second.getJobs().get(index).getStartTime()),
                    "job " + index + " start must be identical across runs");
        }
    }

    @Test
    void theOneDeferralBoundAppliesPerJobNotPerWorkflow() throws Exception {
        SimulationReport report = runLadder();
        double earliestStart = report.getJobs().stream()
                .mapToDouble(SimulationReport.JobOutcome::getStartTime)
                .min()
                .orElse(-1.0);
        assertEquals(
                0,
                Double.compare(100.0, earliestStart),
                "the earliest Job (the stage-in envelope) hops exactly once, into the 100 s window");
        assertTrue(
                report.getMakespan() < 300.0,
                "without the per-Job bound every already-deferred Job would hop again at 100 s and the"
                        + " chain would land past 300 s; observed " + report.getMakespan());
    }

    private SimulationReport runLadder() throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "ladder.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"20\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.CARBON_WINDOW_DEFER)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261106L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-ladder-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        1000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        100.0,
                        300.0,
                        null))
                .pinVmToHost(0, 0);
        builder.carbonWindow(0.0, 100.0, 100.0);
        builder.carbonWindow(100.0, 200.0, 300.0);
        builder.carbonWindow(200.0, 400.0, 500.0);
        return new SimulationRunner().run(config, builder.build());
    }

    private SimulationReport run(SchedulingAlgorithm algorithm, boolean declareWindows) throws Exception {
        return run(algorithm, declareWindows, 120.0);
    }

    private SimulationReport run(SchedulingAlgorithm algorithm, boolean declareWindows, double firstGreen)
            throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "defer-" + algorithm + ".dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"20\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(algorithm)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261102L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-defer-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        1000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        100.0,
                        300.0,
                        null))
                .pinVmToHost(0, 0);
        if (declareWindows) {
            builder.carbonWindow(0.0, 100.0, firstGreen);
            builder.carbonWindow(100.0, 400.0, 400.0);
        }
        return new SimulationRunner().run(config, builder.build());
    }
}
