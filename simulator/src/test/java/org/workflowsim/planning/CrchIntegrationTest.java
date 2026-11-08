package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/** Real FAILURE_VM integration of the CRCH retry/recovery path. */
class CrchIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void failureCreatesARecoveryRetryAndCompletesTheWorkflow() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory, "crch.dax", "<job id=\"a\" name=\"a\" runtime=\"1000\"/>");
        java.util.List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        java.util.Map<Integer, DistributionSpec[]> specs = new java.util.LinkedHashMap<Integer, DistributionSpec[]>();
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
                .maxTotalRetryJobs(1)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                .failureModel(failure)
                .crchConfig(new CrchConfig(1.0, 0.1))
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);

        assertTrue(report.getJobs().size() >= 2);
        assertTrue(report.getMakespan() > 0.0);
    }
}
