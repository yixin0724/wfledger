package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.CrchRecovery;
import org.workflowsim.utils.CrchRegistry;

class CrchSchedulingAlgorithmTest {

    @AfterEach
    void clear() {
        CrchRegistry.clear();
    }

    @Test
    void retryUsesTheBestNonFailedVm() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));
        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0.0, 3.5, 4000L, 1000.0);
        CrchRegistry.registerRetry(9, recovery);
        Job job = new Job(9, 1000L);
        CrchSchedulingAlgorithm scheduler = new CrchSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.asList(job));
        scheduler.setVmList(Arrays.asList(vm(3), vm(8)));

        scheduler.run();

        assertEquals(8, job.getVmId());
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 100L, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
