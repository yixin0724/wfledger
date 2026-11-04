package org.cloudbus.cloudsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/**
 * CloudSim 3.0.3 Space-Shared reference arithmetic from commit
 * 46c46604a021c4cbb54a4dac812e91f89d6c495c: 400000 MI plus ten transfer seconds at 1000 MIPS
 * becomes 410000 MI. The ResCloudlet initialization boundary is asserted separately: an
 * unbound Cloudlet reports its raw length as finished, leaving only the transfer work.
 */
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
            // ResCloudlet initializes from Cloudlet.getCloudletFinishedSoFar(): with no resource
            // binding, the API returns the raw 400000 MI. Only the added 10000 MI remains, so
            // the next completion is 10.0 s after the 0.1 s progress update, not 410.1 s.
            assertEquals(10.0, scheduler.updateVmProcessing(0.1, Collections.singletonList(1000.0)), 1.0e-12);
            assertEquals(9900L, scheduler.getCloudletExecList().get(0).getRemainingCloudletLength());
            assertEquals(Double.MAX_VALUE, scheduler.updateVmProcessing(10.0, Collections.singletonList(1000.0)), 0.0);
            assertSame(cloudlet, scheduler.getNextFinishedCloudlet());
        }
    }
}
