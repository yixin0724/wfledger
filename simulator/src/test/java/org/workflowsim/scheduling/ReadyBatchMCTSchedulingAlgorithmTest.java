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
 * Classic MCT (Minimum Completion Time) oracle test.
 *
 * Classic definition (Maheswaran 1999): each task is assigned to the machine with the minimum
 * completion time, where completionTime = length / mips. Each task decides independently.
 *
 * Key difference from the legacy implementation (FastestVm): the legacy implementation
 * compared the MIPS of the VMs (picking the larger one), whereas classic MCT computes
 * length / mips (picking the smaller one). The two agree on homogeneous VMs and differ on
 * heterogeneous VMs running tasks of different lengths.
 */
class ReadyBatchMCTSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchMCTSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchMCTSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(
                id, 0, mips, 1, 512, 1000, 10000, "Xen", new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void longTaskGoesToFastVm() {
        // A long task completes earlier on the fast VM
        cloudlets.add(new Task(1, 5000)); // long task
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        Cloudlet scheduled = (Cloudlet) scheduler.getScheduledList().get(0);
        assertEquals(1, scheduled.getVmId(), "A long task must go to the fast VM (1000 MIPS)");
    }

    @Test
    void shortTaskAlsoGoesToFastVm() {
        // A short task also completes earlier on the fast VM (MCT always picks the fastest VM,
        // which minimizes length/mips)
        cloudlets.add(new Task(1, 100)); // short task
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        Cloudlet scheduled = (Cloudlet) scheduler.getScheduledList().get(0);
        // 100/500=0.2 vs 100/1000=0.1 -> select VM1
        assertEquals(1, scheduled.getVmId());
    }

    @Test
    void eachTaskIndependentlySelectsBestVm() {
        // Two tasks, two VMs: Task1 picks the fastest, Task2 also prefers the fastest (now taken)
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.size());
        // Task1: 1000/500=2.0 vs 1000/1000=1.0 -> VM1
        assertEquals(1, scheduled.get(0).getVmId());
        // Task2: VM1 is BUSY, only VM0 remains -> VM0
        assertEquals(0, scheduled.get(1).getVmId());
    }
}
