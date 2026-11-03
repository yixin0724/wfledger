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

/**
 * Regression test (PLAT-6): a scheduling algorithm must not rewrite the caller-supplied ready queue.
 *
 * <p>Background: {@link BaseSchedulingAlgorithm#setCloudletList(List)} historically held a real
 * reference to the scheduler's ready queue, while {@link FirstFitIdleSchedulingAlgorithm} sorts
 * that list by Cloudlet ID inside {@code run()} -- the leftover sort order leaked back into the
 * scheduler queue and broke arrival order across scheduling cycles (FCFS semantics). The fix makes
 * setCloudletList take a defensive copy, and this test pins that behavior.</p>
 */
class SchedulingListIsolationTest {

    @Test
    void firstFitIdleSortingDoesNotLeakIntoCallerReadyList() {
        // Arrival order is deliberately the reverse of ascending Cloudlet ID: FirstFitIdle reorders to
        // ascending ID internally.
        Job laterArrival = job(30);
        Job middleArrival = job(20);
        Job firstArrival = job(10);
        List<Cloudlet> callerReadyList =
                new ArrayList<Cloudlet>(Arrays.<Cloudlet>asList(laterArrival, middleArrival, firstArrival));

        FirstFitIdleSchedulingAlgorithm scheduler = new FirstFitIdleSchedulingAlgorithm();
        scheduler.setCloudletList(callerReadyList);
        scheduler.setVmList(Arrays.asList(vm(1), vm(2), vm(3)));
        scheduler.run();

        // The caller's list must keep its original arrival order, unaffected by the in-algorithm sort.
        assertEquals(
                Arrays.<Cloudlet>asList(laterArrival, middleArrival, firstArrival),
                callerReadyList,
                "Algorithm-side sorting must not mutate the caller's ready list");

        // The algorithm itself still dispatches in ascending ID order (established FirstFitIdle semantics).
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
        // FirstFitIdle picks the first idle VM in ascending VM ID order.
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
