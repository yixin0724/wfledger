package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

class SimulationRunnerAlgorithmContractTest {

    @Test
    void configurationRejectsAnUnselectedSchedulerInsteadOfFallingBackToStatic() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("workflow.dax", 1)
                        .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                        .build());

        assertTrue(exception.getMessage().contains("not a runnable configuration"));
    }

    @Test
    void standardRunnerRejectsTimeSharedVmsUntilTheirReadyJobSemanticsAreVerified() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1).build();
        PlatformProfile platform = PlatformProfile.builder("time-shared")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1_000L, 10_000L))
                .addVm(new PlatformProfile.VmSpec(
                        0, 1000.0, 1, 512, 1_000L, 1_000L, "Xen", PlatformProfile.CloudletSchedulerMode.TIME_SHARED))
                .build();

        SimulationConfigurationException exception = assertThrows(
                SimulationConfigurationException.class, () -> new SimulationRunner().run(config, platform));
        assertTrue(exception.getMessage().contains("TIME_SHARED"));
    }
}
