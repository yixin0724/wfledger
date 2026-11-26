package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.EbpsmConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class EbpsmIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void successfulOutputIsPublishedOnCompletionAndSteersTheChild() throws Exception {
        Path chain = PlanningRegressionFixtures.workflow(
                directory,
                "ebpsm-chain.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1000\">"
                        + "<uses file=\"y\" link=\"input\" size=\"1000000\"/>"
                        + "<uses file=\"x\" link=\"output\" size=\"1000000\"/></job>"
                        + "<job id=\"b\" name=\"b\" runtime=\"1000\">"
                        + "<uses file=\"x\" link=\"input\" size=\"1000000\"/></job>"
                        + "<child ref=\"b\"><parent ref=\"a\"/></child>");
        java.util.List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        for (int vm : PlanningRegressionFixtures.VM_IDS) {
            inputs.put(vm, Collections.<String>emptySet());
            costs.put(vm, 1.0);
        }
        containers.put(3, Collections.singleton("ib"));
        containers.put(8, Collections.<String>emptySet());
        inputs.put(8, Collections.singleton("y"));
        Map<Integer, String> images = new HashMap<Integer, String>();
        images.put(1, "ia");
        images.put(2, "ib");
        EbpsmConfig ebpsm = EbpsmConfig.forWorkflowBudgets(
                Collections.singletonMap(0, 10.0), inputs, containers, images, costs, 1.0e6, 1.0e6, 0.0);
        SimulationConfig config = SimulationConfig.builder(chain.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.EBPSM)
                .ebpsmConfig(ebpsm)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);

        SimulationReport.JobOutcome parent = PlanningRegressionFixtures.computeJob(report, 1);
        SimulationReport.JobOutcome child = PlanningRegressionFixtures.computeJob(report, 2);
        assertEquals(8, parent.getVmId());
        assertEquals(8, child.getVmId());
    }

    @Test
    void spareBudgetFromTheParentUpgradesTheChildToTheFasterVm() throws Exception {
        Path chain = PlanningRegressionFixtures.workflow(
                directory,
                "ebpsm-upgrade.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1000\">"
                        + "<uses file=\"x\" link=\"output\" size=\"1000000\"/></job>"
                        + "<job id=\"b\" name=\"b\" runtime=\"2000\"/>"
                        + "<child ref=\"b\"><parent ref=\"a\"/></child>");
        java.util.List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 2000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        for (int vm : PlanningRegressionFixtures.VM_IDS) {
            inputs.put(vm, Collections.<String>emptySet());
            containers.put(vm, Collections.singleton("default"));
        }
        costs.put(3, 1.0);
        costs.put(8, 3.0);
        EbpsmConfig ebpsm = EbpsmConfig.forWorkflowBudgets(
                Collections.singletonMap(0, 5.5),
                inputs,
                containers,
                Collections.<Integer, String>emptyMap(),
                costs,
                1.0e6,
                1.0e6,
                0.0);
        SimulationConfig config = SimulationConfig.builder(chain.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.EBPSM)
                .ebpsmConfig(ebpsm)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(3, PlanningRegressionFixtures.computeJob(report, 1).getVmId());
        assertEquals(8, PlanningRegressionFixtures.computeJob(report, 2).getVmId());
    }

    @Test
    void ebPSMRunsTwoStaggeredWorkflows() throws Exception {
        Path first = PlanningRegressionFixtures.workflow(
                directory, "ebpsm-a.dax", "<job id=\"a\" name=\"a\" runtime=\"1000\"/>");
        Path second = PlanningRegressionFixtures.workflow(
                directory, "ebpsm-b.dax", "<job id=\"b\" name=\"b\" runtime=\"1000\"/>");
        java.util.List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        Map<Integer, Double> budgets = new HashMap<Integer, Double>();
        budgets.put(0, 10.0);
        budgets.put(1, 10.0);
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        inputs.put(3, Collections.<String>emptySet());
        inputs.put(8, Collections.<String>emptySet());
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        containers.put(3, Collections.singleton("default"));
        containers.put(8, Collections.singleton("default"));
        Map<Integer, String> images = new HashMap<Integer, String>();
        images.put(0, "default");
        images.put(1, "default");
        images.put(2, "default");
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        costs.put(3, 1.0);
        costs.put(8, 1.0);
        EbpsmConfig ebpsm =
                EbpsmConfig.forWorkflowBudgets(budgets, inputs, containers, images, costs, 1000.0, 1000.0, 0.0);
        SimulationConfig config = SimulationConfig.builder(Arrays.asList(first.toString(), second.toString()), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.EBPSM)
                .workflowArrivalSeconds(Arrays.asList(0.0, 5.0))
                .ebpsmConfig(ebpsm)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);

        assertTrue(report.getJobs().size() >= 2);
        assertTrue(report.getMakespan() > 5.0);
    }
}
