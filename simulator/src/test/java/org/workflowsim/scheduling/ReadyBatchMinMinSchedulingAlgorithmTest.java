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
 * Ready-batch Min-Min oracle test.
 *
 * Ready-batch definition: compute the ECT only against currently idle VMs, and assign at
 * most one task to each VM per update.
 *
 * Hand-derived oracle: 3 tasks x 2 heterogeneous VMs
 *   VM0: 500 MIPS, VM1: 1000 MIPS
 *   Task1: 2000 MI -> ECT(VM0)=4.0, ECT(VM1)=2.0 -> min=2.0 @VM1
 *   Task2: 1000 MI -> ECT(VM0)=2.0, ECT(VM1)=1.0 -> min=1.0 @VM1
 *   Task3: 3000 MI -> ECT(VM0)=6.0, ECT(VM1)=3.0 -> min=3.0 @VM1
 *
 *   Global minimum ECT = Task2 (1.0 @VM1) -> schedule Task2 onto VM1
 *   Then VM1 is BUSY, leaving only VM0:
 *   Task1: ECT(VM0)=4.0, Task3: ECT(VM0)=6.0 -> global minimum Task1 (4.0 @VM0)
 *   Finally Task3 cannot be scheduled (no idle VM)
 *
 *   Scheduling order: Task2->VM1, Task1->VM0
 */
class ReadyBatchMinMinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchMinMinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchMinMinSchedulingAlgorithm();
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
        assertEquals(2, scheduled.size(), "2 VMs schedule at most 2 tasks");

        // First: global minimum ECT = Task2 (1000/1000=1.0 @VM1)
        assertEquals(2, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());

        // Second: VM1 is BUSY, leaving only VM0; Task1(2000/500=4.0) < Task3(3000/500=6.0)
        assertEquals(1, scheduled.get(1).getCloudletId());
        assertEquals(0, scheduled.get(1).getVmId());
    }

    @Test
    void readyBatchMinMinPrefersShortestEct() {
        // Key difference from the SPT variant: ready-batch Min-Min compares the ECT
        // (length/mips), not the raw task length. A short task has the smallest ECT on a
        // fast VM.
        cloudlets.add(new Task(1, 5000)); // long task
        cloudlets.add(new Task(2, 500)); // short task
        vms.add(createVm(0, 100));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        // Task2: ECT=500/1000=0.5 @VM1 -> global minimum -> scheduled first onto VM1
        assertEquals(2, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());
    }

    @Test
    void allTasksScheduledWhenEnoughVms() {
        for (int i = 1; i <= 3; i++) {
            cloudlets.add(new Task(i, i * 1000));
        }
        for (int i = 0; i < 3; i++) {
            vms.add(createVm(i, 1000));
        }

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(3, scheduler.getScheduledList().size());
    }

    @Test
    void stopsWhenNoIdleVm() {
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        vms.add(createVm(0, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
    }
}
