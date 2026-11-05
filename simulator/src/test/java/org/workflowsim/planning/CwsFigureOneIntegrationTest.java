package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/** Reproduces the CWS paper Figure 1 five-to-four-unit scheduling direction (R-PAPER-02). */
class CwsFigureOneIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void dagRankImprovesThePaperFigureOneSchedule() throws Exception {
        StringBuilder jobs = new StringBuilder();
        for (int id = 1; id <= 6; id++) {
            jobs.append("<job id=\"t")
                    .append(id)
                    .append("\" name=\"t")
                    .append(id)
                    .append("\" runtime=\"1000\"/>");
        }
        jobs.append("<child ref=\"t2\"><parent ref=\"t1\"/></child>")
                .append("<child ref=\"t3\"><parent ref=\"t1\"/></child>")
                .append("<child ref=\"t4\"><parent ref=\"t1\"/></child>")
                .append("<child ref=\"t5\"><parent ref=\"t3\"/><parent ref=\"t4\"/></child>")
                .append("<child ref=\"t6\"><parent ref=\"t2\"/><parent ref=\"t5\"/></child>");
        Path workflow = PlanningRegressionFixtures.workflow(directory, "cws-figure-one.dax", jobs.toString());
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);

        SimulationReport fcfs = run(workflow, platform, Parameters.SchedulingAlgorithm.FCFS);
        SimulationReport cws = run(workflow, platform, Parameters.SchedulingAlgorithm.CWS_RANKMIN_RR);

        assertTrue(cws.getMakespan() < fcfs.getMakespan());
        assertEquals(5.21, fcfs.getMakespan(), 0.03);
        assertEquals(4.21, cws.getMakespan(), 0.03);
    }

    private static SimulationReport run(
            Path workflow, PlatformProfile platform, Parameters.SchedulingAlgorithm algorithm) throws Exception {
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(algorithm)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();
        return new SimulationRunner().run(config, platform);
    }
}
