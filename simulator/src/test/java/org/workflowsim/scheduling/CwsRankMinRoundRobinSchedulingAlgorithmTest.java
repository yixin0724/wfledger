package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.utils.Parameters;

class CwsRankMinRoundRobinSchedulingAlgorithmTest {

    @Test
    void ranksThenInputBytesThenJobIdControlOrderAndVmCursor() {
        Job t2 = job(2, 2, 10.0);
        Job t3 = job(3, 3, 30.0);
        Job t4 = job(4, 3, 20.0);
        CwsRankMinRoundRobinSchedulingAlgorithm scheduler = new CwsRankMinRoundRobinSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.asList(t2, t3, t4));
        scheduler.setVmList(Arrays.asList(vm(1), vm(2)));

        scheduler.run();

        assertEquals(Arrays.asList(t4, t3), scheduler.getScheduledList());
        assertEquals(1, t4.getVmId());
        assertEquals(2, t3.getVmId());

        for (Object vm : scheduler.getVmList()) {
            ((CondorVM) vm).setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);
        }
        scheduler.setCloudletList(java.util.Collections.singletonList(t2));
        scheduler.run();
        assertEquals(Arrays.asList(t4, t3, t2), scheduler.getScheduledList());
        assertEquals(1, t2.getVmId());
    }

    @Test
    void equalRanksUseRealInputBytesNotSameJobOutputBytes() {
        Job internallySupplied = job(1, 3, 100.0);
        FileItem output = new FileItem("input-1", 100.0);
        output.setType(Parameters.FileType.OUTPUT);
        internallySupplied.addFile(output);
        Job externallySupplied = job(2, 3, 50.0);
        CwsRankMinRoundRobinSchedulingAlgorithm scheduler = new CwsRankMinRoundRobinSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.asList(externallySupplied, internallySupplied));
        scheduler.setVmList(Arrays.asList(vm(1), vm(2)));

        scheduler.run();

        assertEquals(Arrays.asList(internallySupplied, externallySupplied), scheduler.getScheduledList());
    }

    private static Job job(int id, int rank, double inputBytes) {
        Job job = new Job(id, 100L);
        job.setCwsRank(rank);
        FileItem file = new FileItem("input-" + id, inputBytes);
        file.setType(Parameters.FileType.INPUT);
        job.addFile(file);
        return job;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 1L, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
