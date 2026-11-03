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
 * Oracle tests for the MinMin scheduling algorithm.
 *
 * Note: the current implementation is an "online SPT-fastest-idle" variant, not the
 * classical Min-Min. Classical Min-Min: first compute each task's minimum completion time
 * (MCT) over all VMs, then pick the global minimum among the candidates. Current
 * implementation: pick the shortest task first, then pick the fastest idle VM. These
 * tests verify the current actual behavior, not the classical definition.
 */
class MinMinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private MinMinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new MinMinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(
                id, 0, mips, 1, 512, 1000, 10000, "Xen", new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void shortestTaskGoesToFastestIdleVm() {
        // 2 tasks, 2 VMs: one run() can schedule only min(tasks, vms) of them
        // because a VM is marked BUSY once assigned, and later tasks break when no idle
        // VM is found
        Task shorter = new Task(1, 1000);
        Task longer = new Task(2, 3000);
        cloudlets.add(longer); // deliberately inserted out of length order
        cloudlets.add(shorter);

        // 2 VMs: slow (500 MIPS), fast (1000 MIPS)
        CondorVM slow = createVm(0, 500);
        CondorVM fast = createVm(1, 1000);
        vms.add(slow);
        vms.add(fast);

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // 2 tasks and 2 VMs, so both can be scheduled
        assertEquals(2, scheduler.getScheduledList().size());
        // The first scheduled must be the shortest task (id=1, 1000 MI), placed on the
        // fastest VM (id=1)
        Cloudlet first = (Cloudlet) scheduler.getScheduledList().get(0);
        assertEquals(1, first.getCloudletId(), "the first scheduled task must be the shortest");
        assertEquals(1, first.getVmId(), "the shortest task must go to the fastest VM");
        // The second is the longest task, placed on the remaining fastest idle VM
        Cloudlet second = (Cloudlet) scheduler.getScheduledList().get(1);
        assertEquals(2, second.getCloudletId());
        assertEquals(0, second.getVmId(), "the longest task goes to the remaining idle VM (500 MIPS)");
    }

    @Test
    void scheduledCountIsCappedByVmCount() {
        // 5 tasks, 3 VMs -> run() schedules at most 3
        for (int i = 1; i <= 5; i++) {
            cloudlets.add(new Task(i, i * 1000));
        }
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));
        vms.add(createVm(2, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // The first 3 shortest tasks are scheduled; the 4th breaks when no idle VM is left
        assertEquals(3, scheduler.getScheduledList().size());
    }

    @Test
    void stopsWhenNoIdleVm() {
        // 3 tasks but only 1 VM -> run() schedules 1 and then breaks
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        cloudlets.add(new Task(3, 3000));
        vms.add(createVm(0, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // Once the VM is marked BUSY, later tasks find no idle VM and break
        assertEquals(1, scheduler.getScheduledList().size());
    }

    @Test
    void taskOrderIsShortestFirst() {
        // 5 tasks and 5 VMs -> all of them can be scheduled; verifies ascending task length
        cloudlets.add(new Task(1, 5000));
        cloudlets.add(new Task(2, 1000));
        cloudlets.add(new Task(3, 3000));
        cloudlets.add(new Task(4, 2000));
        cloudlets.add(new Task(5, 4000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));
        vms.add(createVm(2, 1000));
        vms.add(createVm(3, 1000));
        vms.add(createVm(4, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        // The scheduling order must follow ascending task length: 1000, 2000, 3000, 4000, 5000
        assertEquals(2, scheduled.get(0).getCloudletId()); // 1000 MI
        assertEquals(4, scheduled.get(1).getCloudletId()); // 2000 MI
        assertEquals(3, scheduled.get(2).getCloudletId()); // 3000 MI
        assertEquals(5, scheduled.get(3).getCloudletId()); // 4000 MI
        assertEquals(1, scheduled.get(4).getCloudletId()); // 5000 MI
    }
}
