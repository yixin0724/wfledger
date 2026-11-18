package org.workflowsim.planning.greenheft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Collections;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;

class GreenHeftAdapterTest {

    @Test
    void filtersIncompatibleVmsAndUsesParallelPeDuration() {
        Task task = new Task(1, 100L);
        task.setNumberOfPes(2);
        CondorVM incompatible = vm(0, 1, 1000.0);
        CondorVM compatible = vm(1, 2, 500.0);
        PlatformProfile platform = PlatformProfile.builder("green-pe")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(1, 2, 500.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        10.0,
                        50.0,
                        null,
                        Collections.emptyList()))
                .addVm(new PlatformProfile.VmSpec(
                        1,
                        500.0,
                        2,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        10.0,
                        100.0,
                        null,
                        Collections.emptyList()))
                .build();

        GreenHeftTask converted = GreenHeftAdapter.toProblem(
                        Collections.singletonList(task), Arrays.asList(incompatible, compatible), platform)
                .getTask(1);

        assertFalse(converted.getComputeSecondsByVm().containsKey(0));
        assertEquals(0.2, converted.getComputeSecondsByVm().get(1), 1.0e-9);
        assertEquals(20.0, converted.getEnergyJoulesByVm().get(1), 1.0e-9);
    }

    private static CondorVM vm(int id, int pes, double mips) {
        return new CondorVM(id, 0, mips, pes, 4096, 10000L, 1000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
