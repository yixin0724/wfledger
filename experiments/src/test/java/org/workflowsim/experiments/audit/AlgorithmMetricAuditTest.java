package org.workflowsim.experiments.audit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.EbpsmConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.ReshiConfig;
import org.workflowsim.utils.ReshiRankTable;
import org.workflowsim.utils.ReshiTaskHistory;
import org.workflowsim.utils.SimulationConfig;

/** Cross-cutting runtime metric oracles; algorithm-specific semantics remain in their own tests. */
class AlgorithmMetricAuditTest {

    @TempDir
    Path directory;

    @Test
    void sharedHeftMetricsRecomputeIndependently() throws Exception {
        SimulationConfig config = base(
                Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT,
                ReplicaCatalog.FileSystem.SHARED,
                DataMovementModel.legacyWorkflowsimV1());
        assertClean(AlgorithmMetricAudit.run(config, platform(), "SHARED_STORAGE_HEFT"));
    }

    @Test
    void localHeftMetricsRecomputeIndependently() throws Exception {
        SimulationConfig config = base(
                Parameters.PlanningAlgorithm.LOCAL_HEFT,
                ReplicaCatalog.FileSystem.LOCAL,
                DataMovementModel.preExecutionTransferDelayV1());
        assertClean(AlgorithmMetricAudit.run(config, platform(), "LOCAL_HEFT"));
    }

    @Test
    void sharedObjectiveAlgorithmsRecomputeMetrics() throws Exception {
        for (Parameters.PlanningAlgorithm planner : Arrays.asList(
                Parameters.PlanningAlgorithm.GREEN_HEFT,
                Parameters.PlanningAlgorithm.MO_HEFT,
                Parameters.PlanningAlgorithm.NETWORK_HEFT,
                Parameters.PlanningAlgorithm.IC_PCP)) {
            assertClean(AlgorithmMetricAudit.run(
                    base(planner, ReplicaCatalog.FileSystem.SHARED, DataMovementModel.legacyWorkflowsimV1()),
                    platform(),
                    planner.name()));
        }
    }

    @Test
    void localAlgorithmsRecomputeMetrics() throws Exception {
        for (Parameters.PlanningAlgorithm planner :
                Arrays.asList(Parameters.PlanningAlgorithm.DHEFT, Parameters.PlanningAlgorithm.NACO_GA_HEFT)) {
            assertClean(AlgorithmMetricAudit.run(
                    base(planner, ReplicaCatalog.FileSystem.LOCAL, DataMovementModel.preExecutionTransferDelayV1()),
                    platform(),
                    planner.name()));
        }
    }

    @Test
    void carbonAndCwsAlgorithmsRecomputeMetrics() throws Exception {
        for (Parameters.PlanningAlgorithm planner : Arrays.asList(
                Parameters.PlanningAlgorithm.CAWOSCHED_SLACK, Parameters.PlanningAlgorithm.CAWOSCHED_PRESSURE)) {
            assertClean(AlgorithmMetricAudit.run(
                    configBuilder(planner, ReplicaCatalog.FileSystem.SHARED, DataMovementModel.legacyWorkflowsimV1())
                            .deadline(20L)
                            .build(),
                    platform(),
                    planner.name()));
        }
        SimulationConfig cws = configBuilder(
                        Parameters.PlanningAlgorithm.INVALID,
                        ReplicaCatalog.FileSystem.SHARED,
                        DataMovementModel.legacyWorkflowsimV1())
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CWS_RANKMIN_RR)
                .build();
        assertClean(AlgorithmMetricAudit.run(cws, platform(), "CWS_RANKMIN_RR"));
    }

    @Test
    void reshiAlgorithmsRecomputeMetrics() throws Exception {
        for (Parameters.SchedulingAlgorithm algorithm :
                Arrays.asList(Parameters.SchedulingAlgorithm.RESHI_C, Parameters.SchedulingAlgorithm.RESHI_M)) {
            SimulationConfig config = configBuilder(
                            Parameters.PlanningAlgorithm.INVALID,
                            ReplicaCatalog.FileSystem.SHARED,
                            DataMovementModel.legacyWorkflowsimV1())
                    .schedulingAlgorithm(algorithm)
                    .reshiConfig(reshiConfig())
                    .build();
            assertClean(AlgorithmMetricAudit.run(config, platform(), algorithm.name()));
        }
    }

    @Test
    void ebPSMRecomputesMetricsAcrossWorkflowArrivals() throws Exception {
        Path first = singleTaskWorkflow("ebpsm-a.dax", "a");
        Path second = singleTaskWorkflow("ebpsm-b.dax", "b");
        Map<Integer, Double> budgets = new HashMap<Integer, Double>();
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, String> images = new HashMap<Integer, String>();
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        for (int id = 0; id <= 2; id++) {
            budgets.put(id, 10.0);
            images.put(id, "default");
        }
        for (int vm : Arrays.asList(3, 8)) {
            inputs.put(vm, Collections.<String>emptySet());
            containers.put(vm, Collections.singleton("default"));
            costs.put(vm, 1.0);
        }
        SimulationConfig config = SimulationConfig.builder(Arrays.asList(first.toString(), second.toString()), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.EBPSM)
                .workflowArrivalSeconds(Arrays.asList(0.0, 5.0))
                .ebpsmConfig(new EbpsmConfig(budgets, inputs, containers, images, costs, 1000.0, 1000.0, 0.0))
                .costModel(Parameters.CostModel.VM)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();
        assertClean(AlgorithmMetricAudit.run(config, platform(), "EBPSM"));
    }

    @Test
    void crchRetryRecomputesMetrics() throws Exception {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 0.001, 1.0)
        });
        specs.put(8, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1000000000.0, 1.0)
        });
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(5)
                .build();
        SimulationConfig config = configBuilder(
                        Parameters.PlanningAlgorithm.INVALID,
                        ReplicaCatalog.FileSystem.SHARED,
                        DataMovementModel.legacyWorkflowsimV1())
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                .failureModel(failure)
                .crchConfig(new CrchConfig(1.0, 0.1))
                .build();
        assertClean(AlgorithmMetricAudit.run(config, platform(), "CRCH"));
    }

    @Test
    void dvfsTierEnergyRecomputesMetrics() throws Exception {
        SimulationConfig config = configBuilder(
                        Parameters.PlanningAlgorithm.DVFS_SLACK,
                        ReplicaCatalog.FileSystem.SHARED,
                        DataMovementModel.legacyWorkflowsimV1())
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                .deadline(4L)
                .build();
        assertClean(AlgorithmMetricAudit.run(config, tieredPlatform(), "DVFS_SLACK"));
    }

    private SimulationConfig base(
            Parameters.PlanningAlgorithm planner, ReplicaCatalog.FileSystem fileSystem, DataMovementModel movement)
            throws Exception {
        return configBuilder(planner, fileSystem, movement).build();
    }

    private SimulationConfig.Builder configBuilder(
            Parameters.PlanningAlgorithm planner, ReplicaCatalog.FileSystem fileSystem, DataMovementModel movement)
            throws Exception {
        Path workflow = directory.resolve(planner.name() + ".dax");
        Files.write(
                workflow,
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">\n"
                                + "  <job id=\"a\" name=\"a\" runtime=\"1000\">\n"
                                + "    <uses file=\"x\" link=\"output\" size=\"100\"/>\n"
                                + "  </job>\n"
                                + "  <job id=\"b\" name=\"b\" runtime=\"1000\">\n"
                                + "    <uses file=\"x\" link=\"input\" size=\"100\"/>\n"
                                + "  </job>\n"
                                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                                + "</adag>\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(planner)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .costModel(Parameters.CostModel.VM)
                .fileSystem(fileSystem)
                .dataMovementModel(movement)
                .deadline(10L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L);
    }

    private Path singleTaskWorkflow(String fileName, String taskName) throws Exception {
        Path workflow = directory.resolve(fileName);
        Files.write(
                workflow,
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">\n"
                                + "  <job id=\"" + taskName + "\" name=\"" + taskName + "\" runtime=\"1000\"/>\n"
                                + "</adag>\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return workflow;
    }

    private static ReshiConfig reshiConfig() {
        ReshiRankTable table = new ReshiRankTable(Arrays.asList(
                new ReshiRankTable.Entry("a", "vm3", 1.0, 3),
                new ReshiRankTable.Entry("a", "vm8", 2.0, 8),
                new ReshiRankTable.Entry("b", "vm3", 1.0, 3),
                new ReshiRankTable.Entry("b", "vm8", 2.0, 8)));
        Map<String, Double> history = new LinkedHashMap<String, Double>();
        history.put("a", 1.0);
        history.put("b", 1.0);
        return new ReshiConfig(table, new ReshiTaskHistory(history));
    }

    private static PlatformProfile tieredPlatform() {
        return PlatformProfile.builder("metric-audit-dvfs")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        3,
                        2000.0,
                        1,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(0.0, 0.0, 0.0, 0.0),
                        0.0,
                        0.0,
                        null,
                        Arrays.asList(
                                new PlatformProfile.FrequencyTier(2000.0, 10.0, 100.0),
                                new PlatformProfile.FrequencyTier(500.0, 2.0, 20.0))))
                .addHost(new PlatformProfile.HostSpec(1, 1, 2000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        8,
                        2000.0,
                        1,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(0.0, 0.0, 0.0, 0.0),
                        0.0,
                        0.0,
                        null,
                        Arrays.asList(
                                new PlatformProfile.FrequencyTier(2000.0, 10.0, 100.0),
                                new PlatformProfile.FrequencyTier(500.0, 2.0, 20.0))))
                .build();
    }

    private static void assertClean(AlgorithmMetricAudit.AuditResult result) {
        assertTrue(result.isCompleted(), result.getLabel());
        assertTrue(result.getEnergyResidualJoules() < 1.0e-6, result.getLabel());
        assertTrue(result.getCostResidual() < 1.0e-6, result.getLabel() + " costResidual=" + result.getCostResidual());
        assertTrue(result.getMakespanResidualSeconds() < 1.0e-6, result.getLabel());
        assertTrue(result.getTailResidualSeconds() < 1.0e-6, result.getLabel());
    }

    private static PlatformProfile platform() {
        return PlatformProfile.builder("metric-audit")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        3,
                        1000.0,
                        1,
                        4096,
                        100L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(10.0, 0.001, 0.0, 0.0),
                        10.0,
                        200.0,
                        null,
                        Collections.emptyList()))
                .addVm(new PlatformProfile.VmSpec(
                        8,
                        500.0,
                        1,
                        4096,
                        50L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(1.0, 0.001, 0.0, 0.0),
                        5.0,
                        50.0,
                        null,
                        Collections.emptyList()))
                .carbonWindow(0.0, 5.0, 0.0)
                .carbonWindow(5.0, 10.0, 100.0)
                .carbonWindow(10.0, 20.0, 0.0)
                .build();
    }
}
