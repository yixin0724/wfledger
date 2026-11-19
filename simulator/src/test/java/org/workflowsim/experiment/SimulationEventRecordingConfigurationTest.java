package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationEventRecordingConfigurationTest {

    @Test
    void disabledRecordingPreservesExecutionAndIsExplicitInConfiguration() throws Exception {
        SimulationConfig config = SimulationConfig.builder("../datasets/dax/montage/n50/Montage_50.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .eventRecordingEnabled(false)
                .build();
        PlatformProfile platform =
                org.workflowsim.platform.PlatformProfiles.homogeneousLocal("event-recording-test", 1);
        SimulationReport report = new SimulationRunner().run(config, platform);
        assertTrue(report.isWorkflowCompletedSuccessfully());
        assertTrue(report.getEvents().isEmpty());
        assertTrue(report.getMakespan() > 0.0);
        assertEquals(false, config.toBuilder().build().isEventRecordingEnabled());
        Object enabled = ExperimentManifestWriter.configurationSnapshot(config).get("eventRecordingEnabled");
        assertEquals(Boolean.FALSE, enabled);
    }

    @Test
    void disabledRecordingRejectsLifecycleCaptureInsteadOfDroppingEvidence() {
        SimulationConfig config = SimulationConfig.builder("../datasets/dax/montage/n50/Montage_50.dax", 1)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .fileSystem(org.workflowsim.utils.ReplicaCatalog.FileSystem.LOCAL)
                .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(100))
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .eventRecordingEnabled(false)
                .build();
        assertThrows(SimulationConfigurationException.class, () -> new SimulationRunner()
                .run(config, org.workflowsim.platform.PlatformProfiles.homogeneousLocal("event-recording-test", 1)));
    }
}
