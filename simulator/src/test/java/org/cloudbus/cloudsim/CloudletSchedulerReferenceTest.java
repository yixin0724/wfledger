package org.cloudbus.cloudsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

class CloudletSchedulerReferenceTest {

    @Test
    void spaceSharedAddsTransferWorkAndClampsPredictedCompletion() throws Exception {
        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder("space-shared-reference.dax", 1).build())) {
            session.initializeCloudSim(1, null, false);
            CloudletSchedulerSpaceShared scheduler = new CloudletSchedulerSpaceShared();
            scheduler.updateVmProcessing(0.0, Collections.singletonList(1000.0));
            Cloudlet cloudlet = new Cloudlet(
                    1,
                    400000L,
                    1,
                    1L,
                    1L,
                    new UtilizationModelFull(),
                    new UtilizationModelFull(),
                    new UtilizationModelFull());
            assertEquals(410.0, scheduler.cloudletSubmit(cloudlet, 10.0), 0.0);
            assertEquals(410000L, cloudlet.getCloudletLength());

            assertEquals(10.0, scheduler.updateVmProcessing(0.1, Collections.singletonList(1000.0)), 1.0e-12);
            assertEquals(9900L, scheduler.getCloudletExecList().get(0).getRemainingCloudletLength());
            assertEquals(Double.MAX_VALUE, scheduler.updateVmProcessing(10.0, Collections.singletonList(1000.0)), 0.0);
            assertSame(cloudlet, scheduler.getNextFinishedCloudlet());
        }
    }
}
