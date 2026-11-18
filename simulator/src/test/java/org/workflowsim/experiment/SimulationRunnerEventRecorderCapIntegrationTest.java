package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationRunnerEventRecorderCapIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void runnerHonorsTheConfiguredEventRecorderCap() throws Exception {
        String workflow = resourcePath("/dax/reproducibility-workflow.dax");
        SimulationConfig config = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(20260901L)
                .eventRecorderCap(1)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("runner-cap-platform", 3);

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        IllegalStateException rejection = assertThrows(IllegalStateException.class, () -> runner.run(config, platform));
        assertTrue(
                rejection.getMessage().contains("capacity cap of 1 events"),
                "the rejection must cite the configured cap: " + rejection.getMessage());
    }

    private String resourcePath(String resource) throws Exception {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
