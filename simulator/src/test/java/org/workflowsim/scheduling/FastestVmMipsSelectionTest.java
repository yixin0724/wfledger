package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;

/**
 * Regression test (PLAT-1): the legacy "fastest VM" algorithm must select a VM by the
 * per-PE speed getMips(), not by total MIPS (mips x PE count).
 *
 * <p>Background: a Job always occupies 1 PE, so its actual execution rate depends only on
 * the per-PE MIPS. The historical implementation compared getCurrentRequestedTotalMips()
 * and systematically picked the wrong VM under heterogeneous multi-PE configurations:
 * for example 2PE@500MIPS (total 1000) beat 1PE@800MIPS (total 800), although the Job
 * actually ran at only 500 MIPS on the former. This test pins the behavior of that fix.</p>
 */
class FastestVmMipsSelectionTest {

    /** 2 PE @ 500 MIPS/PE: total MIPS=1000, but a single PE provides only 500. */
    private static final int MANY_PE_SLOW_VM_ID = 1;
    /** 1 PE @ 800 MIPS/PE: total MIPS=800, but the per-PE speed is higher. */
    private static final int FAST_SINGLE_PE_VM_ID = 2;

    @Test
    void fastestVmSelectsByPerPeMipsNotTotalMips() {
        Job job = job(1, 1);

        FastestVmSchedulingAlgorithm scheduler = new FastestVmSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
        scheduler.setVmList(Arrays.asList(manyPeSlowVm(), fastSinglePeVm()));
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(
                FAST_SINGLE_PE_VM_ID,
                job.getVmId(),
                "FastestVm must compare per-PE getMips(): 1PE@800 beats 2PE@500 for a 1-PE Job");
    }

    @Test
    void sptFastestIdleSelectsByPerPeMipsNotTotalMips() {
        Job shortJob = job(10, 1);
        shortJob.setCloudletLength(100L);
        Job longJob = job(11, 1);
        longJob.setCloudletLength(5000L);

        SptFastestIdleSchedulingAlgorithm scheduler = new SptFastestIdleSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(longJob, shortJob));
        scheduler.setVmList(Arrays.asList(manyPeSlowVm(), fastSinglePeVm()));
        scheduler.run();

        assertEquals(2, scheduler.getScheduledList().size());
        assertEquals(shortJob, scheduler.getScheduledList().get(0));
        assertEquals(
                FAST_SINGLE_PE_VM_ID, shortJob.getVmId(), "SPT must place the shortest Job on the fastest per-PE VM");
    }

    @Test
    void ljfFastestIdleSelectsByPerPeMipsNotTotalMips() {
        Job shortJob = job(10, 1);
        shortJob.setCloudletLength(100L);
        Job longJob = job(11, 1);
        longJob.setCloudletLength(5000L);

        LjfFastestIdleSchedulingAlgorithm scheduler = new LjfFastestIdleSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(shortJob, longJob));
        scheduler.setVmList(Arrays.asList(manyPeSlowVm(), fastSinglePeVm()));
        scheduler.run();

        assertEquals(2, scheduler.getScheduledList().size());
        assertEquals(longJob, scheduler.getScheduledList().get(0));
        assertEquals(
                FAST_SINGLE_PE_VM_ID, longJob.getVmId(), "LJF must place the longest Job on the fastest per-PE VM");
    }

    @Test
    void busyVmIsNeverSelectedEvenIfFasterPerPe() {
        Job job = job(1, 1);
        CondorVM busyFast = fastSinglePeVm();
        busyFast.setState(WorkflowSimTags.VM_STATUS_BUSY);

        FastestVmSchedulingAlgorithm scheduler = new FastestVmSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
        scheduler.setVmList(Arrays.asList(manyPeSlowVm(), busyFast));
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(
                MANY_PE_SLOW_VM_ID,
                job.getVmId(),
                "Idle-only dispatch contract: a busy VM must be skipped regardless of speed");
    }

    private static Job job(int id, int pes) {
        Job job = new Job(id, 1000L);
        job.setNumberOfPes(pes);
        return job;
    }

    private static CondorVM manyPeSlowVm() {
        return new CondorVM(
                MANY_PE_SLOW_VM_ID, 0, 500.0, 2, 512, 1000L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }

    private static CondorVM fastSinglePeVm() {
        return new CondorVM(
                FAST_SINGLE_PE_VM_ID, 0, 800.0, 1, 512, 1000L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
