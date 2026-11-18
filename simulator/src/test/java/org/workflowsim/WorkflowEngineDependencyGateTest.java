package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.SimEvent;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

class WorkflowEngineDependencyGateTest {

    @Test
    void failedParentIsAbsentFromSuccessfulDependencySet() throws Exception {
        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder("dependency-gate.dax", 1).build())) {
            org.cloudbus.cloudsim.core.CloudSim.init(1, java.util.Calendar.getInstance(), false);
            WorkflowEngine engine = new WorkflowEngine("engine", 1);
            org.workflowsim.failure.FailureParameters.init(
                    org.workflowsim.failure.FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                    org.workflowsim.failure.FailureParameters.FTCMonitor.MONITOR_NONE,
                    org.workflowsim.failure.FailureParameters.FTCFailure.FAILURE_VM,
                    Collections.singletonMap(1, new org.workflowsim.utils.DistributionGenerator[] {
                        org.workflowsim.utils.DistributionSpec.of(
                                        org.workflowsim.utils.DistributionGenerator.DistributionFamily.WEIBULL,
                                        1.0,
                                        1.0)
                                .createGenerator("dependency-gate-test")
                    }),
                    org.workflowsim.utils.DistributionGenerator.DistributionFamily.WEIBULL,
                    1,
                    0L);
            Field field = WorkflowEngine.class.getDeclaredField("receivedSuccessfulJobIds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<Integer> successful = (Set<Integer>) field.get(engine);
            Job failed = new Job(1, 100L);
            failed.setClassType(Parameters.ClassType.COMPUTE.value);
            failed.getTaskList().add(new Task(2, 100L));
            failed.setCloudletStatus(Cloudlet.FAILED);

            Constructor<SimEvent> constructor = SimEvent.class.getDeclaredConstructor(
                    int.class, double.class, int.class, int.class, int.class, Object.class);
            constructor.setAccessible(true);
            SimEvent event = constructor.newInstance(
                    1, 1.0, 0, 0, org.cloudbus.cloudsim.core.CloudSimTags.CLOUDLET_RETURN, failed);
            Method process = WorkflowEngine.class.getDeclaredMethod("processJobReturn", SimEvent.class);
            process.setAccessible(true);
            process.invoke(engine, event);

            assertFalse(successful.contains(failed.getCloudletId()));
            successful.add(2);
            assertTrue(successful.contains(2));
        }
    }
}
