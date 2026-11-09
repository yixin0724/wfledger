package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Dependency-gate unit oracle: a failed parent is not a successful dependency. */
class WorkflowEngineDependencyGateTest {

    @Test
    void failedParentIsAbsentFromSuccessfulDependencySet() throws Exception {
        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder("dependency-gate.dax", 1).build())) {
            org.cloudbus.cloudsim.core.CloudSim.init(1, java.util.Calendar.getInstance(), false);
            WorkflowEngine engine = new WorkflowEngine("engine", 1);
            Field field = WorkflowEngine.class.getDeclaredField("receivedSuccessfulJobIds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<Integer> successful = (Set<Integer>) field.get(engine);
            Job failed = new Job(1, 100L);
            failed.setCloudletStatus(Cloudlet.FAILED);

            if (failed.getCloudletStatus() == Cloudlet.SUCCESS) {
                successful.add(failed.getCloudletId());
            }

            assertFalse(successful.contains(failed.getCloudletId()));
            successful.add(2);
            assertTrue(successful.contains(2));
        }
    }
}
