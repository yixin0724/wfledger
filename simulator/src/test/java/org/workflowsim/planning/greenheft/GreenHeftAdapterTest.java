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
                        Collections.singletonList(task), Arrays.asList(incompatible, compatible), platform, 20.0, 0.1)
                .getTask(1);

        assertFalse(converted.getComputeSecondsByVm().containsKey(0));
        assertEquals(0.2, converted.getComputeSecondsByVm().get(1), 1.0e-9);
        assertEquals(20.0, converted.getEnergyJoulesByVm().get(1), 1.0e-9);
    }

    /**
     * FGCS-R3 (2026-11-24) M6: SHARED envelope and root release (contract: CONTRACTS.md SHARED compute
     * envelope; same arithmetic as SharedStorageDagPlanner). Hand derivation: a 1000 MI task reads 3 MB at
     * 20 MB/s = 0.15 s; on a 1000 MIPS VM floor(150) MI gives (1000 + 150) / 1000 = 1.15 s and energy
     * 1.15 s x 50 W = 57.5 J. The 110 MI stage-in Job on the lowest-ID VM (1000 MIPS) takes 0.11 s, which
     * is raised to the 0.1 + 0.01 safety minimum 0.11, plus the 0.1 s root-release interval: 0.21 s.
     */
    @Test
    void sharedEnvelopeIncludesInputReadSecondsAndRootsWaitForStageIn() {
        Task task = new Task(1, 1000L);
        org.workflowsim.FileItem input = new org.workflowsim.FileItem("in", 3_000_000.0);
        input.setType(org.workflowsim.utils.Parameters.FileType.INPUT);
        task.addFile(input);
        CondorVM only = vm(0, 1, 1000.0);
        PlatformProfile platform = PlatformProfile.builder("green-envelope")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 4096, 10000L, 100000L))
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
                .build();

        GreenHeftProblem problem = GreenHeftAdapter.toProblem(
                Collections.singletonList(task), Collections.singletonList(only), platform, 20.0, 0.1);
        GreenHeftSchedule schedule = new GreenHeftPlanner().schedule(problem);

        assertEquals(1.15, problem.getTask(1).getComputeSecondsByVm().get(0), 1.0e-12);
        assertEquals(57.5, problem.getTask(1).getEnergyJoulesByVm().get(0), 1.0e-9);
        assertEquals(0.21, problem.getRootReleaseSeconds(), 1.0e-12);
        assertEquals(0.21, schedule.getTaskPlan(1).getStartSeconds(), 1.0e-12);
        assertEquals(1.36, schedule.getMakespanSeconds(), 1.0e-12);
    }

    private static CondorVM vm(int id, int pes, double mips) {
        return new CondorVM(id, 0, mips, pes, 4096, 10000L, 1000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
