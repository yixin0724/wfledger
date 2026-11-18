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

class ReadyBatchRoundRobinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchRoundRobinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchRoundRobinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(
                id, 0, mips, 1, 512, 1000, 10000, "Xen", new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void roundRobinAlternatesVms() {
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 1000));
        cloudlets.add(new Task(3, 1000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.size(), "2 VMs schedule at most 2 tasks");

        assertEquals(0, scheduled.get(0).getVmId());
        assertEquals(1, scheduled.get(1).getVmId());
    }

    @Test
    void cursorWrapsAround() {

        for (int i = 1; i <= 3; i++) {
            cloudlets.add(new Task(i, 1000));
        }
        for (int i = 0; i < 3; i++) {
            vms.add(createVm(i, 1000));
        }

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(3, scheduled.size());

        assertEquals(0, scheduled.get(0).getVmId());
        assertEquals(1, scheduled.get(1).getVmId());
        assertEquals(2, scheduled.get(2).getVmId());
    }

    @Test
    void cursorPersistsAcrossRuns() {

        cloudlets.add(new Task(1, 1000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();
        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(0, ((Cloudlet) scheduler.getScheduledList().get(0)).getVmId());

        List<Cloudlet> cloudlets2 = new ArrayList<>();
        cloudlets2.add(new Task(2, 1000));
        scheduler.getScheduledList().clear();
        scheduler.setCloudletList(cloudlets2);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(
                1,
                ((Cloudlet) scheduler.getScheduledList().get(0)).getVmId(),
                "The cursor must persist across ready batches on the same policy instance and point at VM1");
    }
}
