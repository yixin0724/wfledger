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

/**
 * C1 (2026-11-02): behavior contract of the carbon-aware deferral engine.
 *
 * <p>The engine postpones a ready batch to the next window whose green power is strictly higher (at
 * most once per job). This test uses <b>hand-derived scenarios</b> to anchor three things: deferral
 * really happens (job start times land inside the clean window), the budget carbon excess really
 * drops (against the carbon-agnostic FCFS), and behavior does not drift when no window is
 * declared.</p>
 *
 * <p>Source of expected values: the budget model definition in
 * {@code CARBON_DIMENSION_DESIGN.md} Section 2.3 (excess = integral of max(0, P_fleet(t) -
 * green(t)) dt) plus the windows and power levels this test declares explicitly.</p>
 */
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

    /**
     * Review round (2026-11-02): the deferral engine on a windowless platform must <b>explicitly
     * reject</b> and must not silently degrade into ordinary online scheduling -- a carbon-aware label
     * with not a single job deferred is the same family of BAT-D2 "silently ineffective policy"
     * problems (believing the study is about time shifting while it actually runs FCFS).
     */
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

    /**
     * Adversarial side 1 (starvation prevention): after each of the two jobs is deferred <b>once</b>
     * they must both complete and must not be postponed a second time -- if the "at most once per job"
     * gate failed, the engine would keep looking for a "cleaner" window inside [100,400) and defer
     * repeatedly (or spin when no window is left), pushing completion past the 100 + work upper bound.
     */
    @Test
    void eachJobIsDeferredAtMostOnceSoTheWorkflowCannotStarve() throws Exception {
        SimulationReport deferred = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);
        double upperBound = 100.0 + 60.0; // deferred to 100; two jobs run 30+20 s serially; 10 s bookkeeping margin
        assertTrue(
                deferred.getMakespan() <= upperBound,
                "one deferral per Job bounds the makespan near the clean-window start + work: "
                        + deferred.getMakespan());
    }

    /**
     * Adversarial side 2 (do not move what should not move): when the current window already has the
     * highest green power, the engine must <b>not</b> defer -- otherwise it stretches an already clean
     * run, which is bad behavior with no benefit.
     */
    @Test
    void alreadyCleanWindowsAreNotDeferred() throws Exception {
        SimulationReport baseline = run(SchedulingAlgorithm.FCFS, true, 400.0);
        SimulationReport engine = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true, 400.0);
        assertEquals(
                0,
                Double.compare(baseline.getMakespan(), engine.getMakespan()),
                "the first window is already the cleanest: nothing to gain, so no deferral");
    }

    /**
     * Residual pollution (the H-1/H-2 class, a trap this repository calls out by name): in one JVM,
     * first run a deferral run <b>with windows</b>, then run one <b>without windows</b> -- the window
     * table must not survive across the session boundary. If it did, the second run would
     * inexplicably stall to 100 seconds (its platform declares no window at all).
     */
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

    /**
     * Honest boundary (C1): deferral only removes the <b>dynamic</b> share; it cannot remove the
     * <b>idle floor</b>. When two VMs idle at 200 W together, above the 120 W green power of the dirty
     * window, the dirty-window excess equals the idle excess 80 W x 100 s = 8000 W-s even if every job
     * is deferred -- the engine cannot make the declared static power of the platform disappear.
     */
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

    /**
     * Two-VM fixture: 200 W total idle power > 120 W green power of the first window (creates the idle
     * floor).
     */
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

    /**
     * Reproducibility (review round): running the same input twice must give bit-identical job start
     * times, finish times, and excess -- the deferral engine state (deferred set, wake-up instants)
     * must not survive across runs or depend on floating-point iteration order.
     */
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

    /**
     * Mutation hardening (review round): only on the three-window ladder does the "at most once per
     * job" gate become discriminating, and what it constrains is a <b>single job</b>, not the whole
     * workflow -- successor jobs are new jobs and may each legally hop again.
     *
     * <p>Observed fact (confirmed by instrumentation): the first job becomes ready at 0.1 s and is
     * deferred to 100 s; after it completes at 130 s, the two newly ready successors legally hop from
     * the second window (300 W) to the third (500 W, 200 s). This test therefore asserts that "the
     * first job hops exactly once" (start=100) and gives the whole chain a termination upper
     * bound.</p>
     */
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

    /** Three-window ladder fixture: 100 W (dirty) -> 300 W -> 500 W on a single VM. */
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

    /**
     * Run fixture: single VM (idle 100 / max 300), windows [0,100)=120W dirty and [100,400)=400W clean.
     */
    private SimulationReport run(SchedulingAlgorithm algorithm, boolean declareWindows) throws Exception {
        return run(algorithm, declareWindows, 120.0);
    }

    /**
     * Adjustable green power of the first window: at 400W the first window is already the cleanest (the
     * engine must not defer).
     */
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
