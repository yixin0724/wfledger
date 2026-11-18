package org.workflowsim.experiments.audit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

class AlgorithmExperimentAuditTest {

    private static final List<String> SHARED =
            Arrays.asList("SHARED_STORAGE_HEFT", "NETWORK_HEFT", "GREEN_HEFT", "MO_HEFT", "IC_PCP");
    private static final List<String> LOCAL = Arrays.asList("LOCAL_HEFT", "DHEFT", "NACO_GA_HEFT");
    private static final List<String> ONLINE = Arrays.asList("CWS_RANKMIN_RR", "RESHI_C", "RESHI_M");

    @TempDir
    Path directory;

    @Test
    void sharedAlgorithmsAcrossDagResourceAndSeedMatrix() throws Exception {
        int runs = 0;
        for (String scenario : Arrays.asList("reverseChain", "fanDuplicate", "independent", "largeFiles")) {
            for (String algorithm : SHARED) {
                for (long seed : Arrays.asList(7L, 11L)) {
                    assertClean(run(algorithm, scenario, seed));
                    runs++;
                }
            }
        }
        assertTrue(runs == 40);
        System.out.println("EXPERIMENT_SUMMARY sharedRuns=" + runs);
    }

    @Test
    void localAlgorithmsAcrossDagResourceAndSeedMatrix() throws Exception {
        int runs = 0;
        for (String scenario : Arrays.asList("reverseChain", "fanDuplicate", "independent", "largeFiles")) {
            for (String algorithm : LOCAL) {
                for (long seed : Arrays.asList(7L, 11L)) {
                    assertClean(run(algorithm, scenario, seed));
                    runs++;
                }
            }
        }
        assertTrue(runs == 24);
        System.out.println("EXPERIMENT_SUMMARY localRuns=" + runs);
    }

    @Test
    void onlineAlgorithmsAcrossDagResourceAndSeedMatrix() throws Exception {
        int runs = 0;
        for (String scenario : Arrays.asList("reverseChain", "fanDuplicate", "independent")) {
            for (String algorithm : ONLINE) {
                for (long seed : Arrays.asList(7L, 11L)) {
                    assertClean(run(algorithm, scenario, seed));
                    runs++;
                }
            }
        }
        assertTrue(runs == 18);
        System.out.println("EXPERIMENT_SUMMARY onlineRuns=" + runs);
    }

    @Test
    void carbonDvfsFailureAndBudgetExperiments() throws Exception {
        for (String algorithm : Arrays.asList("CAWOSCHED_SLACK", "CAWOSCHED_PRESSURE")) {
            assertClean(run(algorithm, "fanDuplicate", 7L));
        }
        assertClean(run("DVFS_SLACK", "reverseChain", 7L));
        assertClean(run("CRCH", "reverseChain", 7L));
        assertClean(runEbpsm("independent", 7L));
    }

    @Test
    void heftWorkScalingChangesMakespanAndEnergyInTheExpectedDirection() throws Exception {
        AlgorithmMetricAudit.AuditResult base = run("SHARED_STORAGE_HEFT", "reverseChain", 7L);
        AlgorithmMetricAudit.AuditResult doubled = run("SHARED_STORAGE_HEFT", "reverseChainDouble", 7L);
        assertTrue(doubled.getMakespanSeconds() > base.getMakespanSeconds() * 1.5);
        assertTrue(doubled.getEnergyJoules() > base.getEnergyJoules() * 1.5);
    }

    @Test
    void icpcpRejectsAnActuallyInfeasibleDeadline() {
        assertThrows(IllegalStateException.class, () -> run("IC_PCP", "infeasible", 7L));
    }

    private AlgorithmMetricAudit.AuditResult run(String algorithm, String scenario, long seed) throws Exception {
        String xml = workflow(scenario);
        System.out.println("RUN " + algorithm + "@" + scenario + "#" + seed);
        Path workflow = directory.resolve(algorithm + "-" + scenario + "-" + seed + ".dax");
        Files.write(workflow, xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SimulationConfig.Builder builder = SimulationConfig.builder(workflow.toString(), 2)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .costModel(Parameters.CostModel.VM)
                .deadline(1000L)
                .runtimeReferenceMips(1.0)
                .randomSeed(seed);
        PlatformProfile platform = platform();
        if ("DVFS_SLACK".equals(algorithm)) {
            platform = tieredPlatform();
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.DVFS_SLACK)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                    .deadline(1000L);
        } else if ("CRCH".equals(algorithm)) {
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.INVALID)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                    .failureModel(failureModel())
                    .crchConfig(new CrchConfig(1.0, 0.25));
        } else if (ONLINE.contains(algorithm)) {
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.INVALID)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.valueOf(algorithm))
                    .reshiConfig(reshiConfig());
        } else if (LOCAL.contains(algorithm)) {
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.valueOf(algorithm))
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1());
        } else {
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.valueOf(algorithm))
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC);
        }
        if (algorithm.startsWith("CAWOSCHED")) {
            builder.deadline(20L);
        }
        if ("infeasible".equals(scenario)) {
            builder.deadline(1L);
        }
        AlgorithmMetricAudit.AuditResult result =
                AlgorithmMetricAudit.run(builder.build(), platform, algorithm + "@" + scenario + "#" + seed);
        System.out.println("EXPERIMENT " + result.getLabel() + " makespan=" + result.getMakespanSeconds()
                + " energy=" + result.getEnergyJoules() + " cost=" + result.getModeledProcessingCost()
                + " mapping=" + result.getMapping());
        return result;
    }

    private AlgorithmMetricAudit.AuditResult runEbpsm(String scenario, long seed) throws Exception {
        Path first = directory.resolve("ebpsm-a.dax");
        Path second = directory.resolve("ebpsm-b.dax");
        Files.write(first, workflow(scenario).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(second, workflow(scenario).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<Integer, Double> budgets = new HashMap<Integer, Double>();
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, String> images = new HashMap<Integer, String>();
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        for (int id = 0; id <= 10; id++) {
            budgets.put(id, 100.0);
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
                .randomSeed(seed)
                .build();
        return AlgorithmMetricAudit.run(config, platform(), "EBPSM@" + scenario + "#" + seed);
    }

    private static void assertClean(AlgorithmMetricAudit.AuditResult result) {
        assertTrue(result.isCompleted(), result.getLabel());
        assertTrue(result.getMakespanSeconds() > 0.0, result.getLabel());
        assertTrue(result.getEnergyResidualJoules() < 1.0e-6, result.getLabel());
        assertTrue(
                result.getPeakResidualWatts() < 1.0e-6,
                result.getLabel() + " peakResidual=" + result.getPeakResidualWatts());
        assertTrue(result.getCostResidual() < 1.0e-6, result.getLabel());
        assertTrue(result.getMakespanResidualSeconds() < 1.0e-6, result.getLabel());
        assertTrue(result.getTailResidualSeconds() < 1.0e-6, result.getLabel());
        assertTrue(result.getDependencyResidualSeconds() < 1.0e-6, result.getLabel());
    }

    private static String workflow(String scenario) {
        if ("reverseChainDouble".equals(scenario)) {
            return "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">"
                    + "<job id=\"a\" name=\"a\" runtime=\"2000\"/><job id=\"b\" name=\"b\" runtime=\"4000\"/>"
                    + "<child ref=\"a\"><parent ref=\"b\"/></child></adag>";
        }
        if ("infeasible".equals(scenario) || "reverseChain".equals(scenario)) {
            return "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">"
                    + "<job id=\"a\" name=\"a\" runtime=\"1000\"/><job id=\"b\" name=\"b\" runtime=\"2000\"/>"
                    + "<child ref=\"a\"><parent ref=\"b\"/></child></adag>";
        }
        if ("fanDuplicate".equals(scenario)) {
            return "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">"
                    + "<job id=\"a\" name=\"a\" runtime=\"1000\"><uses file=\"x\" link=\"output\" size=\"1000000\"/>"
                    + "<uses file=\"x\" link=\"output\" size=\"1000000\"/></job>"
                    + "<job id=\"b\" name=\"b\" runtime=\"2000\">"
                    + "<uses file=\"x\" link=\"input\" size=\"1000000\"/></job>"
                    + "<job id=\"c\" name=\"c\" runtime=\"3000\">"
                    + "<uses file=\"x\" link=\"input\" size=\"1000000\"/></job>"
                    + "<job id=\"d\" name=\"d\" runtime=\"1000\">"
                    + "<uses file=\"x\" link=\"input\" size=\"1000000\"/></job>"
                    + "<child ref=\"b\"><parent ref=\"a\"/></child><child ref=\"c\"><parent ref=\"a\"/></child>"
                    + "<child ref=\"d\"><parent ref=\"b\"/><parent ref=\"c\"/></child></adag>";
        }
        if ("largeFiles".equals(scenario)) {
            return "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">"
                    + "<job id=\"a\" name=\"a\" runtime=\"1000\">"
                    + "<uses file=\"x\" link=\"output\" size=\"5000000\"/></job>"
                    + "<job id=\"b\" name=\"b\" runtime=\"2000\">"
                    + "<uses file=\"x\" link=\"input\" size=\"5000000\"/></job>"
                    + "<job id=\"c\" name=\"c\" runtime=\"3000\">"
                    + "<uses file=\"x\" link=\"input\" size=\"5000000\"/></job>"
                    + "<child ref=\"b\"><parent ref=\"a\"/></child><child ref=\"c\"><parent ref=\"a\"/></child></adag>";
        }
        return "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">"
                + "<job id=\"a\" name=\"a\" runtime=\"1000\"/><job id=\"b\" name=\"b\" runtime=\"2000\"/>"
                + "<job id=\"c\" name=\"c\" runtime=\"4000\"/><job id=\"d\" name=\"d\" runtime=\"8000\"/></adag>";
    }

    private static PlatformProfile platform() {
        return PlatformProfile.builder("experiment-matrix")
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

    private static PlatformProfile tieredPlatform() {
        return PlatformProfile.builder("experiment-dvfs")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 2000.0, 4096, 10000L, 100000L))
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
                                new PlatformProfile.FrequencyTier(500.0, 2.0, 200.0))))
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
                                new PlatformProfile.FrequencyTier(500.0, 2.0, 200.0))))
                .build();
    }

    private static ReshiConfig reshiConfig() {
        List<ReshiRankTable.Entry> entries = new ArrayList<ReshiRankTable.Entry>();
        Map<String, Double> history = new LinkedHashMap<String, Double>();
        for (String name : Arrays.asList("a", "b", "c", "d")) {
            entries.add(new ReshiRankTable.Entry(name, "vm3", 1.0, 3));
            entries.add(new ReshiRankTable.Entry(name, "vm8", 2.0, 8));
            history.put(name, 1.0);
        }
        return new ReshiConfig(new ReshiRankTable(entries), new ReshiTaskHistory(history));
    }

    private static FailureModelConfig failureModel() {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 0.001, 1.0)
        });
        specs.put(8, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1000000000.0, 1.0)
        });
        return FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(20)
                .build();
    }
}
