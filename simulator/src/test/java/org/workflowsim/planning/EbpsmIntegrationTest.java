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

    /**
     * FGCS-R3 (2026-11-24, A1), re-derived for FGCS-R5 per-workflow budgets: end-to-end proof that WorkflowScheduler
     * forwards job completions to EBPSM. Chain a -> b on two 1000-MIPS VMs (bw 1 MB/s, price 1, one VM type); a
     * reads y (1e6 B, initially cached only on VM 8) and writes x (1e6 B), b reads x. a uses image ia, b uses ib
     * (deployed only on VM 3). Eq. 5 costs on the uncached type: a 2 s read + 1 s compute + 2 s write = 5; b 2 s
     * read + 1 s compute = 3; budget 10 covers both. The input tier sends a to VM 8. If a's output is published on
     * completion, b's input tier selects VM 8; without publication no VM holds x and b's container tier selects
     * VM 3.
     */
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

    /**
     * FGCS-R5 (2026-11-24), test (b) end to end. VM 3: 1000 MIPS, price 1; VM 8: 2000 MIPS, price 3; bw 1 MB/s;
     * GS rates 1e6 B/s; {@code prov_c = 0}; every image deployed. a: 1000 MI writing x (1e6 B); b: 2000 MI, no files.
     * Eq. 5 (Eq. 4 includes the Eq. 2 write of 1 + 1 = 2 s): a slow 1 + 2 = 3 s, cost 3; a fast 0.5 + 2 = 2.5 s,
     * cost 3 * 3 = 9; b slow 2 s, cost 2; b fast 1 s, cost 3. Budget 5.5: cheapest 3 + 2, residual 0.5; neither
     * upgrade (+6, +1) fits, so b's static sub-budget 2 admits only VM 3. a runs on VM 3 (VM 8 costs 9 > 3). The
     * SHARED runtime does not charge the output write inside the envelope (contract: CONTRACTS.md "The Compute
     * Envelope Model of SHARED"), so a's actual envelope is 1 s, cost 1. Algorithm 3: pool 3 + 0.5 covers 1, spare
     * 2.5, {@code beta_u = 2 + 2.5 = 4.5}; Algorithm 1 over {b}: 2, residual 2.5, the +1 upgrade fits, so b's
     * sub-budget is 3 and b runs on the faster VM 8.
     */
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
