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

    @Test
    void laterTaskUsesTheSlowVmWhenItCompletesEarlier() {
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 500));
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.size());
        assertEquals(1, scheduled.get(0).getVmId());
        assertEquals(0, scheduled.get(1).getVmId());
    }

    @Test
    void busyVmReadyTimeEntersTheCompletionTime() {
        List<Cloudlet> late = mctWithBusyFastVm(3.0);
        assertEquals(1, late.size());
        assertEquals(0, late.get(0).getVmId());
        assertEquals(0, mctWithBusyFastVm(0.5).size());
    }

    private List<Cloudlet> mctWithBusyFastVm(double readySeconds) {
        ReadyBatchMCTSchedulingAlgorithm policy = new ReadyBatchMCTSchedulingAlgorithm();
        CondorVM busy = createVm(1, 1000);
        busy.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
        policy.setCloudletList(java.util.Arrays.<Cloudlet>asList(new Task(1, 1000)));
        policy.setVmList(java.util.Arrays.asList(createVm(0, 500), busy));
        policy.setMachineReadyTimeEstimator(vm -> readySeconds);
        policy.run();
        return (List<Cloudlet>) policy.getScheduledList();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(
                id, 0, mips, 1, 512, 1000, 10000, "Xen", new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void longTaskGoesToFastVm() {

        cloudlets.add(new Task(1, 5000));
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

        cloudlets.add(new Task(1, 100));
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        Cloudlet scheduled = (Cloudlet) scheduler.getScheduledList().get(0);

        assertEquals(1, scheduled.getVmId());
    }

    @Test
    void laterTaskWaitsForTheFasterVmWhenItCompletesEarlier() {

        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(1, scheduled.size());
        assertEquals(1, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());
    }
}
