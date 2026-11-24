package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * FGCS-R3 (2026-11-24) golden (a): SHARED_STORAGE_HEFT and SHARED_STORAGE_CPOP on the ten-task DAG of
 * Topcuoglu, Hariri and Wu (IEEE TPDS 13(3), 2002, Fig. 3) with zero communication, hand-derived.
 *
 * <p>Adaptation. The SHARED family scales one task length by VM speed, so the paper's heterogeneous
 * cost matrix cannot be used verbatim; task lengths are the paper's P1 column times 1000 MI
 * (14, 13, 11, 13, 12, 13, 7, 5, 18, 21) and the edges are the paper's DAG
 * (1→2..6, 2→8,9, 3→7, 4→8,9, 5→9, 6→8, 7/8/9→10). There are no files, so every edge and every
 * input-read term is zero, matching the "zero communication" case. VMs: VM0 at 1000 MIPS and VM1–VM3 at
 * 2000 MIPS. Roots are released at 0.21 s (110 MI stage-in on VM0, raised to 0.1 + 0.01 s, plus 0.1 s).
 *
 * <p>Derivation (exact arithmetic). {@code w̄(t) = (L/1000 + 3·L/2000) / 4 = 0.625·P1(t)}, so the
 * upward ranks are r(10)=13.125, r(9)=24.375, r(8)=16.25, r(7)=17.5, r(6)=24.375, r(5)=31.875,
 * r(4)=32.5, r(3)=24.375, r(2)=32.5, r(1)=41.25. HEFT order (rank, then ID): 1, 2, 4, 5, 3, 6, 9, 7, 8, 10.
 * Insertion EFT (ties to the lower VM ID): 1→VM1 [0.21, 7.21]; 2→VM1 [7.21, 13.71]; 4→VM2
 * [7.21, 13.71]; 5→VM3 [7.21, 13.21]; 3→VM0 [7.21, 18.21] (VM0 18.21 beats VM1 19.21);
 * 6→VM3 [13.21, 19.71]; 9→VM1 [13.71, 22.71]; 7 (ready 18.21)→VM2 [18.21, 21.71]; 8 (ready 19.71)→VM3
 * [19.71, 22.21]; 10 (ready 22.71)→VM1 [22.71, 33.21]. Makespan 33.21 s.
 *
 * <p>CPOP: downward ranks give priorities r_u + r_d = 41.25 for 1, 2, 4, 9, 10, 40.625 for 5 and 33.125
 * for 3, 6, 7, 8. The critical path follows the entry task 1 along children of priority 41.25, preferring
 * the higher upward rank then the lower ID: 1→2 (2 and 4 tie at 32.5, lower ID), 2→9, 9→10. Its summed
 * duration is smallest on a 2000-MIPS VM; the lowest such ID is VM1. Ready-list order by priority:
 * 1, 2, 4, 5, 9, 3, 6, 7, 8, 10. Pinning 1, 2, 9, 10 to VM1 and inserting the rest by EFT reproduces the
 * HEFT windows above (makespan 33.21 s): with zero communication both algorithms place the critical
 * path on the same VM here. That agreement is a property of this instance, not of the algorithms.
 */
class SharedStorageTopcuogluZeroCommunicationGoldenTest {

    private static final double EPSILON = 1.0e-9;
    private static final long[] P1 = {14, 13, 11, 13, 12, 13, 7, 5, 18, 21};
    private static final int[][] EDGES = {
        {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6}, {2, 8}, {2, 9}, {3, 7}, {4, 8}, {4, 9}, {5, 9}, {6, 8}, {7, 10},
        {8, 10}, {9, 10}
    };
    private static final int[] VM = {1, 1, 0, 2, 3, 3, 2, 3, 1, 1};
    private static final double[] START = {0.21, 7.21, 7.21, 7.21, 7.21, 13.21, 18.21, 19.71, 13.71, 22.71};
    private static final double[] FINISH = {7.21, 13.71, 18.21, 13.71, 13.21, 19.71, 21.71, 22.21, 22.71, 33.21};
    private static final double[] UPWARD = {41.25, 32.5, 24.375, 32.5, 31.875, 24.375, 17.5, 16.25, 24.375, 13.125};

    @Test
    void sharedStorageHeftReproducesTheHandDerivedSchedule() {
        List<Task> tasks = graph();
        SharedStorageHeftPlanningAlgorithm planner =
                new SharedStorageHeftPlanningAlgorithm(context(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT));
        planner.setTaskList(tasks);
        planner.setVmList(vms());
        planner.run();

        assertSchedule(planner.getLastPlanTrace());
    }

    @Test
    void sharedStorageCpopReproducesTheHandDerivedCriticalPathAndSchedule() {
        List<Task> tasks = graph();
        SharedStorageCpopPlanningAlgorithm planner =
                new SharedStorageCpopPlanningAlgorithm(context(Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP));
        planner.setTaskList(tasks);
        planner.setVmList(vms());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals(Arrays.asList(1, 2, 9, 10), trace.getCriticalPathTaskIds());
        assertEquals(Integer.valueOf(1), trace.getCriticalProcessorVmId());
        assertEquals(41.25, trace.getTaskPlan(1).getCpopPrioritySeconds(), EPSILON);
        assertEquals(40.625, trace.getTaskPlan(5).getCpopPrioritySeconds(), EPSILON);
        assertEquals(33.125, trace.getTaskPlan(3).getCpopPrioritySeconds(), EPSILON);
        assertSchedule(trace);
    }

    private static void assertSchedule(SharedStorageDagPlanTrace trace) {
        double makespan = 0.0;
        for (int index = 0; index < P1.length; index++) {
            SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(index + 1);
            String subject = "task " + (index + 1);
            assertEquals(UPWARD[index], plan.getUpwardRankSeconds(), EPSILON, subject + " upward rank");
            assertEquals(VM[index], plan.getVmId(), subject + " VM");
            assertEquals(START[index], plan.getPlannedStartSeconds(), EPSILON, subject + " start");
            assertEquals(FINISH[index], plan.getPlannedFinishSeconds(), EPSILON, subject + " finish");
            makespan = Math.max(makespan, plan.getPlannedFinishSeconds());
        }
        assertEquals(33.21, makespan, EPSILON);
    }

    private static List<Task> graph() {
        List<Task> tasks = new ArrayList<Task>();
        for (int index = 0; index < P1.length; index++) {
            tasks.add(new Task(index + 1, P1[index] * 1000L));
        }
        for (int[] edge : EDGES) {
            Task parent = tasks.get(edge[0] - 1);
            Task child = tasks.get(edge[1] - 1);
            parent.addChild(child);
            child.addParent(parent);
        }
        return tasks;
    }

    private static List<CondorVM> vms() {
        List<CondorVM> result = new ArrayList<CondorVM>();
        double[] mips = {1000.0, 2000.0, 2000.0, 2000.0};
        for (int id = 0; id < mips.length; id++) {
            result.add(new CondorVM(id, 0, mips[id], 1, 512, 1000, 10_000, "Xen", new CloudletSchedulerSpaceShared()));
        }
        return result;
    }

    private static PlanningContext context(Parameters.PlanningAlgorithm algorithm) {
        SimulationConfig config = SimulationConfig.builder("topcuoglu.dax", 4)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("topcuoglu-zero-communication");
        double[] mips = {1000.0, 2000.0, 2000.0, 2000.0};
        for (int id = 0; id < mips.length; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 1, 2000.0, 1024, 1000L, 100_000L))
                    .addVm(new PlatformProfile.VmSpec(
                            id,
                            mips[id],
                            1,
                            512,
                            1000L,
                            10_000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                    .pinVmToHost(id, id);
        }
        return new PlanningContext(config, builder.build());
    }
}
