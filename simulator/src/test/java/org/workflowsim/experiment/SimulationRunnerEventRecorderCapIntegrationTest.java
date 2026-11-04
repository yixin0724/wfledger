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

/**
 * AUD-F07 follow-up (2026-11-04, post-completion review): the runner must honor the configured
 * event-recorder cap. Before the configuration outlet, the only way to raise the cap above the
 * default 20000 was the Java constructor {@code new SimulationEventRecorder(n)}, so a
 * config-driven run that legitimately needed more events failed with no adjustment path. The
 * wiring test pins the semantic axis end to end: a cap of 1 makes the second recorded event
 * fail fast with the configured number in the message.
 */
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
