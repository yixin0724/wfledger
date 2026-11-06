package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReshiConfig;
import org.workflowsim.utils.ReshiRankTable;
import org.workflowsim.utils.ReshiTaskHistory;
import org.workflowsim.utils.SimulationConfig;

/** Standard SimulationRunner integration with explicit Reshi inputs. */
class ReshiIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void reshiRunUsesConfiguredRankTableAndHistory() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "reshi.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1000\"/>" + "<job id=\"b\" name=\"b\" runtime=\"1000\"/>");
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        ReshiRankTable table = new ReshiRankTable(Arrays.asList(
                new ReshiRankTable.Entry("a", "vm3", 1.0, 3),
                new ReshiRankTable.Entry("a", "vm8", 2.0, 8),
                new ReshiRankTable.Entry("b", "vm3", 1.0, 3),
                new ReshiRankTable.Entry("b", "vm8", 2.0, 8)));
        java.util.Map<String, Double> history = new java.util.LinkedHashMap<String, Double>();
        history.put("a", 1.0);
        history.put("b", 1.0);
        ReshiConfig reshi = new ReshiConfig(table, new ReshiTaskHistory(history));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.RESHI_C)
                .runtimeReferenceMips(1.0)
                .reshiConfig(reshi)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);

        assertTrue(report.getMakespan() > 0.0);
        assertTrue(report.getJobs().size() >= 2);
    }
}
