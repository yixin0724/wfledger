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

    /**
     * FGCS-R6 (2026-11-25) hand derivation with machine ready times (Braun et al. 2001). ETC on
     * (VM 0 500 MIPS, VM 1 1000 MIPS): task 1 (4, 2), task 2 (2, 1), task 3 (6, 3); both VMs idle.
     * Step 1 minima 2, 1, 3: task 2 to VM 1, r1 = 1. Step 2: task 1 min(4, 3) = 3 on VM 1, task 3
     * min(6, 4) = 4: task 1 to VM 1, r1 = 3. Step 3: task 3 min(6, 6) ties to the lower VM ID 0.
     * Dispatched: task 2 (first plan on VM 1) and task 3 (first plan on VM 0); task 1 stays queued.
     */
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
        assertEquals(2, scheduled.size(), "one first plan per idle VM");

        assertEquals(2, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());

        assertEquals(3, scheduled.get(1).getCloudletId());
        assertEquals(0, scheduled.get(1).getVmId());
    }

    /**
     * FGCS-R6 (2026-11-25): ETC task 1 (50, 5), task 2 (5, 0.5). Task 2 goes to VM 1 (r1 = 0.5), then
     * task 1 also prefers VM 1 (5.5 versus 50) and is not dispatched: VM 0 stays idle.
     */
    @Test
    void readyBatchMinMinPrefersShortestEct() {

        cloudlets.add(new Task(1, 5000));
        cloudlets.add(new Task(2, 500));
        vms.add(createVm(0, 100));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();

        assertEquals(1, scheduled.size());
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
