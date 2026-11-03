package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.rl.EarliestFinishGreedyPolicy;
import org.workflowsim.rl.RlEnvironment;
import org.workflowsim.rl.RlEpisodeResult;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Whole-platform health matrix (cross-acceptance of the core configurations after the R1-R5 merge).
 *
 * <p>Unlike a single-point reproduction test, this test passes every end-to-end result through a
 * unified report-invariant verifier (completion, internal metric consistency, task interval
 * partition, flow-time definition, VM aggregate closure), so that old and new features remain usable
 * under one and the same accounting. Crosses covered: every online scheduler, every maintained
 * planner (including PSO end-to-end), the three data movement models, multi-workflow staggered
 * arrival x online scheduling, and the RL track x staggered arrival.</p>
 */
class PlatformHealthMatrixIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void everySupportedOnlineSchedulerIsHealthyOnADependentDag() throws Exception {
        Log.disable();
        for (SchedulingAlgorithm algorithm : Arrays.asList(
                SchedulingAlgorithm.FCFS,
                SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                SchedulingAlgorithm.READY_BATCH_MCT,
                SchedulingAlgorithm.READY_BATCH_MINMIN,
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                SchedulingAlgorithm.DATA)) {
            SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 3)
                    .schedulingAlgorithm(algorithm)
                    .fileSystem(
                            algorithm == SchedulingAlgorithm.DATA
                                    ? ReplicaCatalog.FileSystem.LOCAL
                                    : ReplicaCatalog.FileSystem.SHARED)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner()
                    .run(config, PlatformProfiles.homogeneousLocal("health-online-" + algorithm, 3));
            assertReportHealthy(report, "online:" + algorithm);
        }
    }

    @Test
    void everySupportedIndependentPlannerIsHealthyIncludingPsoEndToEnd() throws Exception {
        Log.disable();
        for (PlanningAlgorithm planner : Arrays.asList(
                PlanningAlgorithm.RANDOM,
                PlanningAlgorithm.STATIC_OLB,
                PlanningAlgorithm.STATIC_MET,
                PlanningAlgorithm.STATIC_MCT,
                PlanningAlgorithm.STATIC_MINMIN,
                PlanningAlgorithm.STATIC_MAXMIN,
                PlanningAlgorithm.STATIC_SUFFERAGE,
                PlanningAlgorithm.STATIC_ROUND_ROBIN,
                PlanningAlgorithm.PSO)) {
            SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/independent-tasks.dax"), 2)
                    .planningAlgorithm(planner)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner()
                    .run(config, PlatformProfiles.homogeneousLocal("health-independent-" + planner, 2));
            assertReportHealthy(report, "independent:" + planner);
        }
    }

    @Test
    void everyMaintainedDagPlannerIsHealthyOnItsModel() throws Exception {
        Log.disable();
        // LOCAL family: LOCAL file system + pre-execution transfer delay model (planning AST and
        // run-time bits aligned).
        for (PlanningAlgorithm planner : Arrays.asList(PlanningAlgorithm.LOCAL_HEFT, PlanningAlgorithm.LOCAL_CPOP)) {
            SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                    .planningAlgorithm(planner)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "dag-local:" + planner);
        }
        // RANDOM: legacy data movement model (SHARED default).
        {
            SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                    .planningAlgorithm(PlanningAlgorithm.RANDOM)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "dag-legacy:RANDOM");
        }
        // SHARED_STORAGE family: the model premise requires the legacy data movement model
        // (planning and execution transfer estimates bit-aligned).
        for (PlanningAlgorithm planner : Arrays.asList(
                PlanningAlgorithm.SHARED_STORAGE_HEFT,
                PlanningAlgorithm.SHARED_STORAGE_CPOP,
                PlanningAlgorithm.SHARED_STORAGE_DLS,
                PlanningAlgorithm.SHARED_STORAGE_ETF,
                PlanningAlgorithm.SHARED_STORAGE_PEFT)) {
            SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/p7-shared-storage.dax"), 2)
                    .planningAlgorithm(planner)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, sharedStoragePlatform());
            assertReportHealthy(report, "dag-shared:" + planner);
        }
    }

    @Test
    void localHeftIsHealthyUnderBothExplicitTransferModels() throws Exception {
        Log.disable();
        for (DataMovementModel movement : Arrays.asList(
                DataMovementModel.preExecutionTransferDelayV1(),
                DataMovementModel.preExecutionTransferDelayWithContentionV1())) {
            SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                    .planningAlgorithm(PlanningAlgorithm.LOCAL_HEFT)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(movement)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "local-heft:" + movement.getKind());
        }
    }

    @Test
    void everyOnlineSchedulerIsHealthyUnderStaggeredArrival() throws Exception {
        Log.disable();
        for (SchedulingAlgorithm algorithm : Arrays.asList(
                SchedulingAlgorithm.FCFS,
                SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                SchedulingAlgorithm.READY_BATCH_MCT,
                SchedulingAlgorithm.READY_BATCH_MINMIN,
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                SchedulingAlgorithm.DATA)) {
            SimulationConfig config = SimulationConfig.builder(
                            Arrays.asList(
                                    resourcePath("/dax/heft-paper-example.dax"),
                                    resourcePath("/dax/heft-paper-example.dax")),
                            3)
                    .schedulingAlgorithm(algorithm)
                    .fileSystem(
                            algorithm == SchedulingAlgorithm.DATA
                                    ? ReplicaCatalog.FileSystem.LOCAL
                                    : ReplicaCatalog.FileSystem.SHARED)
                    .workflowArrivalSeconds(Arrays.asList(0.0, 2000.0))
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "arrival:" + algorithm);
            assertArrivalGated(report, 2000.0, "arrival:" + algorithm);
        }
    }

    @Test
    void rlPolicyEpisodeIsHealthyUnderStaggeredArrival() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(
                        Arrays.asList(
                                resourcePath("/dax/heft-paper-example.dax"),
                                resourcePath("/dax/heft-paper-example.dax")),
                        3)
                .schedulingAlgorithm(SchedulingAlgorithm.RL_POLICY)
                .workflowArrivalSeconds(Arrays.asList(0.0, 2000.0))
                .build();
        RlEpisodeResult episode =
                new RlEnvironment().runEpisode(config, paperPlatform(), new EarliestFinishGreedyPolicy());
        SimulationReport report = episode.getReport();
        assertReportHealthy(report, "arrival:RL_POLICY");
        assertArrivalGated(report, 2000.0, "arrival:RL_POLICY");
        assertEquals(
                report.getTotalJobs(), episode.getDecisions().size(), "the RL decision trace must cover every Job");
        assertEquals(-episode.getMakespan(), episode.getReward(), 0.0, "RL reward contract reward = -makespan");
    }

    /**
     * Unified report-invariant verifier: completion + internal metric consistency + task interval
     * partition + flow-time definition + VM aggregate closure. Any breach means the platform behaves
     * abnormally under the corresponding configuration combination.
     */
    private static void assertReportHealthy(SimulationReport report, String label) {
        assertTrue(
                report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()),
                label + ": makespan must be a positive finite value");
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs(), label + ": every Job must succeed");
        assertTrue(
                report.getMetrics().isAllLogicalTasksCompletedSuccessfully(),
                label + ": every logical task must complete successfully");
        assertEquals(
                "COMPLETED_SUCCESSFULLY",
                report.getMetrics().getLogicalTaskCompletionStatus(),
                label + ": logical completion status");
        assertTrue(
                report.getMetrics().getMeanComputeJobRunTimeSeconds() > 0.0,
                label + ": mean compute run time must be positive");
        assertTrue(
                report.getMetrics().getMeanJobVmQueueWaitingTimeSeconds() >= 0.0,
                label + ": mean queue waiting time must be non-negative");
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            assertTrue(
                    task.getStartTime() >= 0.0 && task.getFinishTime() >= task.getStartTime(),
                    label + ": task " + task.getTaskId() + " timing must be self-consistent");
        }
        int vmJobTotal = 0;
        for (SimulationReport.VmSummary summary : report.getVmSummaries().values()) {
            vmJobTotal += summary.getJobs();
        }
        assertEquals(
                report.getTotalJobs(),
                vmJobTotal,
                label + ": the VM aggregate Job count must close to the total Job count");
        int taskTotal = 0;
        for (SimulationReport.WorkflowOutcome outcome : report.getWorkflowOutcomes()) {
            assertTrue(outcome.getTaskCount() >= 1, label + ": every workflow has at least one task");
            assertTrue(outcome.getLastTaskId() >= outcome.getFirstTaskId(), label + ": task id interval must be valid");
            assertTrue(
                    outcome.getLastSuccessFinishSecond() >= outcome.getArrivalSecond(),
                    label + ": workflow " + outcome.getIndex() + " finish must not precede its submission");
            assertEquals(
                    outcome.getLastSuccessFinishSecond() - outcome.getArrivalSecond(),
                    outcome.getFlowTimeSeconds(),
                    0.0,
                    label + ": flow-time definition (finish - submission)");
            taskTotal += outcome.getTaskCount();
        }
        assertEquals(
                report.getMetrics().getLogicalTaskCount(),
                taskTotal,
                label + ": sum of workflow task intervals must equal the logical task count");
        assertTrue(
                report.getWorkflowOutcomes().size()
                        == report.getConfig().getWorkflowPaths().size(),
                label + ": one outcome per workflow input");
    }

    /** Staggered-arrival gate: tasks of a late workflow must not start before its submission time. */
    private static void assertArrivalGated(SimulationReport report, double arrivalSecond, String label) {
        for (SimulationReport.WorkflowOutcome workflow : report.getWorkflowOutcomes()) {
            if (workflow.getArrivalSecond() < arrivalSecond) {
                continue;
            }
            for (SimulationReport.TaskOutcome task : report.getTasks()) {
                if (task.getTaskId() >= workflow.getFirstTaskId() && task.getTaskId() <= workflow.getLastTaskId()) {
                    assertTrue(
                            task.getStartTime() >= arrivalSecond,
                            label + ": task " + task.getTaskId() + " started at " + task.getStartTime()
                                    + ", before the arrival time");
                }
            }
        }
    }

    /** Platform isomorphic to the HEFT paper reproduction: 3 VMs, mips=1.0. */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("health-paper-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    /** Shared-storage platform: used by the SHARED_STORAGE_* family. */
    private static PlatformProfile sharedStoragePlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("health-shared-storage-platform");
        for (int id = 0; id < 2; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 1000.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        builder.storage(new PlatformProfile.StorageSpec(1_000_000_000L, 10));
        return builder.build();
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = PlatformHealthMatrixIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
