package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.CrchRecovery;
import org.workflowsim.utils.CrchRegistry;

class CrchSchedulingAlgorithmTest {

    @AfterEach
    void clear() {
        CrchRegistry.clear();
    }

    @Test
    void retryResumesOnTheCheckpointingVmWhenThatFinishesFirst() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));
        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0L, 4000L, 3L, 3.5, 1000.0);
        CrchRegistry.registerRetry(9, recovery);
        Job job = job(9, 1, 4000L);

        run(job, vm(3, 1000.0, 1), vm(8, 1000.0, 1));

        assertEquals(3, job.getVmId());
    }

    @Test
    void aFasterVmWinsOnlyWhenItsFullReexecutionFinishesFirst() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));
        CrchRegistry.registerRetry(9, CrchRegistry.recordFailure(1, 3, 0L, 4000L, 3L, 3.5, 1000.0));
        Job tie = job(9, 1, 4000L);
        run(tie, vm(3, 1000.0, 1), vm(8, 4000.0, 1));
        assertEquals(3, tie.getVmId());

        Job faster = job(9, 1, 4000L);
        run(faster, vm(3, 1000.0, 1), vm(8, 5000.0, 1));
        assertEquals(8, faster.getVmId());
    }

    @Test
    void aFreshJobGoesToTheEarliestFinishingVm() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));
        Job job = job(1, 1, 3000L);

        run(job, vm(3, 1000.0, 1), vm(8, 2000.0, 1));

        assertEquals(8, job.getVmId());
    }

    @Test
    void neverSelectsAPeIncompatibleVm() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));
        Job job = job(1, 1, 4000L);
        job.setNumberOfPes(2);
        job.getTaskList().get(0).setNumberOfPes(2);

        run(job, vm(3, 4000.0, 1), vm(8, 1000.0, 2));

        assertEquals(8, job.getVmId());
    }

    private static void run(Job job, CondorVM... vms) {
        CrchSchedulingAlgorithm scheduler = new CrchSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.asList(job));
        scheduler.setVmList(Arrays.asList(vms));
        scheduler.run();
    }

    private static Job job(int jobId, int taskId, long workMi) {
        Job job = new Job(jobId, workMi);
        job.getTaskList().add(new Task(taskId, workMi));
        return job;
    }

    private static CondorVM vm(int id, double mips, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, 100L, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
