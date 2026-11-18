package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;

class SchedulingListIsolationTest {

    @Test
    void firstFitIdleSortingDoesNotLeakIntoCallerReadyList() {

        Job laterArrival = job(30);
        Job middleArrival = job(20);
        Job firstArrival = job(10);
        List<Cloudlet> callerReadyList =
                new ArrayList<Cloudlet>(Arrays.<Cloudlet>asList(laterArrival, middleArrival, firstArrival));

        FirstFitIdleSchedulingAlgorithm scheduler = new FirstFitIdleSchedulingAlgorithm();
        scheduler.setCloudletList(callerReadyList);
        scheduler.setVmList(Arrays.asList(vm(1), vm(2), vm(3)));
        scheduler.run();

        assertEquals(
                Arrays.<Cloudlet>asList(laterArrival, middleArrival, firstArrival),
                callerReadyList,
                "Algorithm-side sorting must not mutate the caller's ready list");

        assertEquals(3, scheduler.getScheduledList().size());
        assertEquals(firstArrival, scheduler.getScheduledList().get(0));
        assertEquals(middleArrival, scheduler.getScheduledList().get(1));
        assertEquals(laterArrival, scheduler.getScheduledList().get(2));
    }

    @Test
    void firstFitIdleVmSortingDoesNotLeakIntoCallerVmList() {
        Job job = job(10);
        CondorVM higherId = vm(9);
        CondorVM lowerId = vm(3);
        List<CondorVM> callerVmList = new ArrayList<CondorVM>(Arrays.asList(higherId, lowerId));

        FirstFitIdleSchedulingAlgorithm scheduler = new FirstFitIdleSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
        scheduler.setVmList(callerVmList);
        scheduler.run();

        assertEquals(
                Arrays.asList(higherId, lowerId),
                callerVmList,
                "Algorithm-side VM sorting must not mutate the caller's VM list");

        assertEquals(3, job.getVmId());
    }

    private static Job job(int id) {
        Job job = new Job(id, 1000L);
        job.setNumberOfPes(1);
        return job;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 1000L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
