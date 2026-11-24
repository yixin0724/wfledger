package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SharedStorageDlsPlanningAlgorithmTest {

    /**
     * FGCS-R3 (2026-11-24) m1 regression: Sih and Lee (IEEE TPDS 4(2), 1993) define the heterogeneous
     * dynamic level as {@code DL(n,p) = SL*(n) - max(DA(n,p), TF(p)) + Δ(n,p)} with
     * {@code Δ(n,p) = E*(n) - E(n,p)}, where {@code E*} is the median execution time over the processors
     * and {@code SL*} the static level built from {@code E*}. Hand derivation: 1000 MI tasks on VM0
     * (1000 MIPS, 1.0 s) and VM1 (2000 MIPS, 0.5 s); {@code E* = 0.75}; {@code SL*(1) = 1.5},
     * {@code SL*(2) = SL*(3) = 0.75}; {@code Δ(VM0) = -0.25}, {@code Δ(VM1) = +0.25}; roots are released
     * at 0.21 s. Step 1: (1,VM1) = 1.5 - 0.21 + 0.25 = 1.54 is the maximum (the pre-fix rule without Δ
     * picked (1,VM0) with 1.29). Task 1 runs on VM1 in [0.21, 0.71]. Step 2: (2,VM0) = 0.75 - 0.21 - 0.25
     * = 0.29, (2,VM1) = 0.75 - 0.71 + 0.25 = 0.29, (3,VM0) = -0.21, (3,VM1) = 0.29; the tie resolves to
     * Task 2 then VM0. Step 3: (3,VM0) = 0.75 - 1.21 - 0.25 = -0.71, (3,VM1) = 0.29, so VM1.
     */
    @Test
    void choosesTheMaximumDynamicLevelTaskVmPairAtEachSelection() {
        Task root = new Task(1, 1000);
        Task independent = new Task(2, 1000);
        Task child = new Task(3, 1000);
        connect(root, child);

        SharedStorageDlsPlanningAlgorithm planner = planner(
                Arrays.asList(child, independent, root),
                Arrays.asList(vm(1, 2000.0), vm(0, 1000.0)),
                mixedMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals("DLS", trace.getStrategy());
        assertEquals(0.21, trace.getStageInFinishSeconds(), 1.0e-12);

        assertEquals(1.5, trace.getTaskPlan(1).getUpwardRankSeconds(), 1.0e-12);
        assertEquals(1.54, trace.getTaskPlan(1).getDlsDynamicLevelAtSelection(), 1.0e-12);
        assertEquals(0.29, trace.getTaskPlan(2).getDlsDynamicLevelAtSelection(), 1.0e-12);
        assertEquals(0.29, trace.getTaskPlan(3).getDlsDynamicLevelAtSelection(), 1.0e-12);
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(1).getDlsSelectionOrder());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(2).getDlsSelectionOrder());
        assertEquals(Integer.valueOf(3), trace.getTaskPlan(3).getDlsSelectionOrder());
        assertEquals(1, trace.getTaskPlan(1).getVmId());
        assertEquals(0, trace.getTaskPlan(2).getVmId());
        assertEquals(1, trace.getTaskPlan(3).getVmId());
        assertNull(trace.getTaskPlan(1).getCpopPrioritySeconds());
    }

    /**
     * FGCS-R3 (2026-11-24) m1 regression: the static level uses the median, not the mean, execution
     * time. Hand derivation: one 1000 MI task on VMs of 1000, 1000 and 250 MIPS (1, 1 and 4 s); the
     * median is 1 s (the mean, 2 s, is rejected), so {@code SL* = 1}; on VM0 {@code Δ = 0} and the
     * selected dynamic level is {@code 1 - 0.21 + 0 = 0.79}.
     */
    @Test
    void staticLevelAndDeltaUseTheMedianExecutionTime() {
        Task only = new Task(4, 1000);

        SharedStorageDlsPlanningAlgorithm planner = planner(
                Arrays.asList(only), Arrays.asList(vm(0, 1000.0), vm(1, 1000.0), vm(2, 250.0)), skewedPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals(0, trace.getTaskPlan(4).getVmId());
        assertEquals(0.79, trace.getTaskPlan(4).getDlsDynamicLevelAtSelection(), 1.0e-12);
    }

    @Test
    void resolvesEqualDynamicLevelsByTaskThenVmId() {
        Task higherId = new Task(8, 1000);
        Task lowerId = new Task(2, 1000);

        SharedStorageDlsPlanningAlgorithm planner = planner(
                Arrays.asList(higherId, lowerId), Arrays.asList(vm(7, 1000.0), vm(3, 1000.0)), equalMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(2).getDlsSelectionOrder());
        assertEquals(3, trace.getTaskPlan(2).getVmId());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(8).getDlsSelectionOrder());
    }

    private static SharedStorageDlsPlanningAlgorithm planner(
            List<Task> tasks, List<CondorVM> vms, PlatformProfile platform) {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SharedStorageDlsPlanningAlgorithm result =
                new SharedStorageDlsPlanningAlgorithm(new PlanningContext(config, platform));
        result.setTaskList(tasks);
        result.setVmList(vms);
        return result;
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static CondorVM vm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10_000, "Xen", new CloudletSchedulerSpaceShared());
    }

    private static PlatformProfile mixedMipsPlatform() {
        return PlatformProfile.builder("shared-storage-dls-mixed")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0, 1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 2000.0, 1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(
                        0, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(
                        1, 2000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }

    private static PlatformProfile skewedPlatform() {
        return PlatformProfile.builder("shared-storage-dls-median")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(2, 1, 1000.0, 1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(
                        0, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(
                        1, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(
                        2, 250.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }

    private static PlatformProfile equalMipsPlatform() {
        return PlatformProfile.builder("shared-storage-dls-ties")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(
                        3, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(
                        7, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }
}
