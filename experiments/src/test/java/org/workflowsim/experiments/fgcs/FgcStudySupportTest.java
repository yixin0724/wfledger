package org.workflowsim.experiments.fgcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class FgcStudySupportTest {

    @TempDir
    Path directory;

    @Test
    void auditEvaluatesTheSameRunWithoutASecondSimulation() throws Exception {
        Path workflow = FgcDatasets.smallDag(directory, "chain");
        SimulationConfig config = FgcStudySupport.base(workflow.toString(), 4)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        FgcStudySupport.Measurement measurement =
                FgcStudySupport.run("chain", config, FgcPlatforms.flat(4, 1), directory.resolve("evidence"));
        assertTrue(measurement.getReport().isWorkflowCompletedSuccessfully());
        assertEquals(4, measurement.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
        assertEquals(0.0, measurement.getAudit().getEnergyResidualJoules(), 1.0e-6, "energy");
        assertEquals(0.0, measurement.getAudit().getPeakResidualWatts(), 1.0e-6, "peak");
        assertEquals(0.0, measurement.getAudit().getCostResidual(), 1.0e-6, "cost");
        assertEquals(0.0, measurement.getAudit().getDependencyResidualSeconds(), 1.0e-6, "dependency");
        assertTrue(measurement.getArtifactBytes() > 0L);
        assertTrue(measurement.getSimulationNanos() > 0L);
        assertTrue(measurement.getArtifactWriteNanos() > 0L);
        assertTrue(measurement.getAuditNanos() > 0L);
        FgcStudySupport.writeResults(
                directory.resolve("results.json"), java.util.Collections.singletonList(measurement));
        assertTrue(java.nio.file.Files.isRegularFile(directory.resolve("results.json")));
    }
}
