package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.junit.jupiter.api.Test;
import org.workflowsim.scheduling.MinMinSchedulingAlgorithm;
import org.workflowsim.scheduling.RoundRobinSchedulingAlgorithm;

/**
 * Reproducibility test: within a single JVM, the same configuration run twice must produce
 * bit-identical results. This is the baseline guarantee for reproducible experiments.
 */
class ReproducibilityTest {

    private List<Cloudlet> buildCloudlets() {
        List<Cloudlet> list = new ArrayList<>();
        list.add(new Task(1, 5000));
        list.add(new Task(2, 1000));
        list.add(new Task(3, 3000));
        list.add(new Task(4, 2000));
        return list;
    }

    private List<Vm> buildVms() {
        List<Vm> list = new ArrayList<>();
        list.add(createVm(0, 500));
        list.add(createVm(1, 1000));
        list.add(createVm(2, 2000));
        list.add(createVm(3, 800));
        return list;
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(
                id, 0, mips, 1, 512, 1000, 10000, "Xen", new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void minMinSameInputSameOutput() {
        // First run.
        MinMinSchedulingAlgorithm s1 = new MinMinSchedulingAlgorithm();
        List<Cloudlet> c1 = buildCloudlets();
        List<Vm> v1 = buildVms();
        s1.setCloudletList(c1);
        s1.setVmList(v1);
        s1.run();

        // Second run: fresh objects, same configuration.
        MinMinSchedulingAlgorithm s2 = new MinMinSchedulingAlgorithm();
        List<Cloudlet> c2 = buildCloudlets();
        List<Vm> v2 = buildVms();
        s2.setCloudletList(c2);
        s2.setVmList(v2);
        s2.run();

        // Both scheduling runs must agree.
        assertEquals(s1.getScheduledList().size(), s2.getScheduledList().size());
        for (int i = 0; i < s1.getScheduledList().size(); i++) {
            Cloudlet a = (Cloudlet) s1.getScheduledList().get(i);
            Cloudlet b = (Cloudlet) s2.getScheduledList().get(i);
            assertEquals(a.getCloudletId(), b.getCloudletId(), "task ID mismatch at scheduled position " + i);
            assertEquals(a.getVmId(), b.getVmId(), "VM assignment mismatch at scheduled position " + i);
        }
    }

    @Test
    void roundRobinSameInputSameOutput() {
        RoundRobinSchedulingAlgorithm s1 = new RoundRobinSchedulingAlgorithm();
        s1.setCloudletList(buildCloudlets());
        s1.setVmList(buildVms());
        s1.run();

        RoundRobinSchedulingAlgorithm s2 = new RoundRobinSchedulingAlgorithm();
        s2.setCloudletList(buildCloudlets());
        s2.setVmList(buildVms());
        s2.run();

        assertEquals(s1.getScheduledList().size(), s2.getScheduledList().size());
        for (int i = 0; i < s1.getScheduledList().size(); i++) {
            Cloudlet a = (Cloudlet) s1.getScheduledList().get(i);
            Cloudlet b = (Cloudlet) s2.getScheduledList().get(i);
            assertEquals(a.getCloudletId(), b.getCloudletId());
            assertEquals(a.getVmId(), b.getVmId());
        }
    }
}
