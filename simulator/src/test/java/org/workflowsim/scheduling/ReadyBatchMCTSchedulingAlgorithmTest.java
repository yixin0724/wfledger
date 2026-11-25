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

    /**
     * FGCS-R6 (2026-11-25): task 1 (1000 MI) goes to VM 1 (CT 1 versus 2, r1 = 1); task 2 (500 MI)
     * compares 1 on VM 0 (500 MIPS) with 1 + 0.5 = 1.5 on VM 1, so the slow idle VM wins and both
     * tasks are dispatched (1 to VM 1, 2 to VM 0).
     */
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

    /**
     * FGCS-R6 (2026-11-25): a busy VM contributes its bound ready time. VM 1 (1000 MIPS) is busy with
     * r1 = 3; task 1 (1000 MI) compares 2 on idle VM 0 with 3 + 1 = 4 on VM 1, so it starts on VM 0.
     * With r1 = 0.5 it compares 2 with 1.5 and waits for VM 1 (nothing is dispatched).
     */
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

    /**
     * FGCS-R6 (2026-11-25), Maheswaran et al. (1999) immediate-mode MCT with ready times: task 1 goes
     * to VM 1 (CT 1 versus 2, r1 = 1); task 2 compares 4 on VM 0 with 1 + 2 = 3 on VM 1 and is planned
     * behind task 1, so only task 1 is dispatched this round.
     */
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
