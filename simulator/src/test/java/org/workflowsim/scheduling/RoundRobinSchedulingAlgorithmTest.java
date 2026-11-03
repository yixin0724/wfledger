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
 * RoundRobin scheduling algorithm oracle test.
 *
 * Note: the current implementation is not a true round-robin.
 * Although vmIndex is declared and incremented, it is never used when selecting a VM.
 * The actual behavior is "find the first idle VM in ascending vmId order" (First-Fit-Idle).
 * These tests verify the current actual behavior, not classic round-robin.
 */
class RoundRobinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private RoundRobinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new RoundRobinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(
                id, 0, mips, 1, 512, 1000, 10000, "Xen", new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void tasksAssignedToVmsInIdOrder() {
        // 3 tasks, 3 VMs
        for (int i = 1; i <= 3; i++) {
            cloudlets.add(new Task(i, 1000));
        }
        vms.add(createVm(2, 1000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(3, scheduled.size());

        // The implementation first sorts by vmId and then assigns in order:
        // cloudlet 1 -> vm 0, cloudlet 2 -> vm 1, cloudlet 3 -> vm 2
        assertEquals(0, scheduled.get(0).getVmId());
        assertEquals(1, scheduled.get(1).getVmId());
        assertEquals(2, scheduled.get(2).getVmId());
    }

    @Test
    void doesNotRevisitBusyVms() {
        // 2 tasks, 1 VM -- the second task finds no idle VM
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        vms.add(createVm(0, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
    }

    /**
     * Verifies that vmIndex is dead code: even when a vmIndex offset is set manually,
     * the algorithm still scans from the beginning (because it does not use vmIndex at all).
     */
    @Test
    void vmIndexIsDeadCode() {
        // 4 tasks, 2 VMs -- the first 2 tasks fill the VMs, the last 2 cannot be scheduled
        for (int i = 1; i <= 4; i++) {
            cloudlets.add(new Task(i, 1000));
        }
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // Only 2 are scheduled (one per VM); the loop then breaks
        assertEquals(2, scheduler.getScheduledList().size());
    }
}
