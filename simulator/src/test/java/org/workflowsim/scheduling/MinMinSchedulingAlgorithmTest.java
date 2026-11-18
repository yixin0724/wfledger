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

        Task shorter = new Task(1, 1000);
        Task longer = new Task(2, 3000);
        cloudlets.add(longer);
        cloudlets.add(shorter);

        CondorVM slow = createVm(0, 500);
        CondorVM fast = createVm(1, 1000);
        vms.add(slow);
        vms.add(fast);

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(2, scheduler.getScheduledList().size());

        Cloudlet first = (Cloudlet) scheduler.getScheduledList().get(0);
        assertEquals(1, first.getCloudletId(), "the first scheduled task must be the shortest");
        assertEquals(1, first.getVmId(), "the shortest task must go to the fastest VM");

        Cloudlet second = (Cloudlet) scheduler.getScheduledList().get(1);
        assertEquals(2, second.getCloudletId());
        assertEquals(0, second.getVmId(), "the longest task goes to the remaining idle VM (500 MIPS)");
    }

    @Test
    void scheduledCountIsCappedByVmCount() {

        for (int i = 1; i <= 5; i++) {
            cloudlets.add(new Task(i, i * 1000));
        }
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));
        vms.add(createVm(2, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(3, scheduler.getScheduledList().size());
    }

    @Test
    void stopsWhenNoIdleVm() {

        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        cloudlets.add(new Task(3, 3000));
        vms.add(createVm(0, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
    }

    @Test
    void taskOrderIsShortestFirst() {

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

        assertEquals(2, scheduled.get(0).getCloudletId());
        assertEquals(4, scheduled.get(1).getCloudletId());
        assertEquals(3, scheduled.get(2).getCloudletId());
        assertEquals(5, scheduled.get(3).getCloudletId());
        assertEquals(1, scheduled.get(4).getCloudletId());
    }
}
