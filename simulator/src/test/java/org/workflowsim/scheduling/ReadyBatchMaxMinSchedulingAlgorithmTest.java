package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Oracle test for the classic Max-Min algorithm.
 *
 * Classic definition (Braun 2001): for each task compute the minimum ECT, then pick the
 * globally largest one. Difference from Min-Min: Max-Min schedules large tasks first.
 *
 * Hand-computed oracle: 3 tasks x 2 VMs (heterogeneous)
 *   VM0: 500 MIPS, VM1: 1000 MIPS
 *   Task1: 2000 MI -> minECT=2.0 @VM1
 *   Task2: 1000 MI -> minECT=1.0 @VM1
 *   Task3: 3000 MI -> minECT=3.0 @VM1
 *
 *   Max-Min picks the globally largest minECT = Task3 (3.0) -> schedule to VM1
 *   then VM1 is BUSY, leaving only VM0:
 *   Task1: ECT(VM0)=4.0, Task2: ECT(VM0)=2.0 -> largest Task1 (4.0) -> VM0
 *
 *   Scheduling order: Task3->VM1, Task1->VM0
 */
class ReadyBatchMaxMinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchMaxMinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchMaxMinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(
                id, 0, mips, 1, 512, 1000, 10000, "Xen", new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void oracleThreeTasksTwoVms() {
        cloudlets.add(new Task(1, 2000));
        cloudlets.add(new Task(2, 1000));
        cloudlets.add(new Task(3, 3000));
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.size());

        // Max-Min schedules the large task first: Task3 (minECT=3.0) -> VM1
        assertEquals(3, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());

        // Then VM1 is BUSY and only VM0 remains; Task1(4.0) > Task2(2.0) -> Task1 -> VM0
        assertEquals(1, scheduled.get(1).getCloudletId());
        assertEquals(0, scheduled.get(1).getVmId());
    }

    @Test
    void maxMinPrefersLongTaskFirst() {
        // Key difference from Min-Min: Max-Min schedules large tasks first
        cloudlets.add(new Task(1, 500));
        cloudlets.add(new Task(2, 5000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.get(0).getCloudletId(), "Max-Min must schedule the long task first");
    }
}
