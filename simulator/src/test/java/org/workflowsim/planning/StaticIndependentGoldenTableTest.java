package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Chain C (scientific-validity audit S-C1, 2026-10-20): Braun et al. 2001 ECT-matrix
 * golden table for independent-task heuristics.
 *
 * <p>Literature source: Braun, Siegel, Beck, Boloni, Maheswaran, Reuther, Robertson,
 * Theys, Yao, Hensgen &amp; Freund, <i>A Comparison of Eleven Static Heuristics for
 * Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing
 * Systems</i>, Journal of Parallel and Distributed Computing 61(5), 2001
 * (the algorithm definition is written as an ECT matrix: Each task's Estimated
 * Completion Time on each machine; each per-round selection rule is stated with its
 * hand derivation).</p>
 *
 * <p>All expected values are hand-derived from the ECT matrix (three tasks x three
 * machines) and never taken from implementation output. On this platform the same ECT
 * semantics is obtained through the {@code TaskExecutionModel} cost matrix (the matrix
 * seconds are authoritative).</p>
 *
 * <p>Cost matrix (ECT=availability+seconds, all three machines idle initially):</p>
 *
 * <pre>
 *           VM0    VM1    VM2
 *   t1      2      5      1
 *   t2      3      2      6
 *   t3      4      3      2
 * </pre>
 *
 * <b>MCT (Braun et al. 2001 §4.2) hand derivation</b> -- each task is assigned
 * independently to its minimum-ECT machine: t1 -> 3 (ECT1), t2 -> 1 (2), t3 -> 2 (2).
 *
 * <b>MinMin (§4.4) hand derivation</b> -- round by round, the task with the smallest
 * ECT on its best machine is assigned first:
 * R1: t1@VM2(1), t2@VM1(2), t3@VM2(2); the minimum ECT=1, so select t1 -> VM2
 * (availability[VM2]=1).
 * R2: t2@VM1(2), t3@VM2(1+2=3) -- VM0 is the worst at 4; minimum =2 -> t2 -> VM1
 * (av1=2). R3: t3: min(vm0=4,vm1=2+3=5,vm2=1+2=3)=3 -> VM2. => t1 -> 2, t2 -> 1,
 * t3 -> 2.
 *
 * <b>MaxMin (§4.4) hand derivation</b> -- round by round, select "the largest of the
 * minimum ECTs":
 * R1: candidate completion times t1@2(1), t2@1(2), t3@2(2): the minimum ECTs are 1, 2,
 * 2; take the largest and break the t2/t3 tie by task ID: assign t2@VM1(2) (av1=2).
 * R2: t1@2(1), t3@2(3) -- their minimums are 1 and 3, take the largest =3 -> t3 -> VM2
 * (av2=3). R3: t1's minimum ECT: vm0=2, vm1=2+5, vm2=1+3+1=5 -> VM0. => t1 -> 0,
 * t2 -> 1, t3 -> 2.
 *
 * <b>Sufferage (Maheswaran et al. 1999 / Braun et al. 2001 §4.7) hand derivation</b> --
 * each round selects the task with the largest second-best minus best gap:
 * R1: t1 sufferage=2-1=1... compute the best and second-best of each task concretely:
 * t1 best VM2(1), second VM1 or VM0 -> min(2,5)=2 -> sufferage=2-1=1.
 * t2 best VM1(2), second=vm0(3) or vm2(6) -> 3 -> sufferage=1.
 * t3 best VM2(2), second=vm1(3) or vm0(4) -> 3-2=1.
 * All three sufferage values=1. Ascending task ID breaks the tie -> t1@VM2 (av2=1).
 * R2: t2: best vm1(2, second vm0=3)=sufferage 1; t3: best vm2(1+2=3), second vm1(3)
 * -> sufferage 0. Select t2@VM1. R3: t3 best: vm0=4, vm1=2+3=5, vm2=1+2=3 -> VM2.
 * => t1 -> 2, t2 -> 1, t3 -> 2 (identical to MinMin -- a plausible literature
 * phenomenon: the two methods converge on this matrix).
 */
class StaticIndependentGoldenTableTest {

    /** ECT matrix in the Braun et al. 2001 form (seconds). Rows = tasks, columns =
     * [VM0, VM1, VM2]. */
    private static final double[][] ECT = {
        {2, 5, 1},
        {3, 2, 6},
        {4, 3, 2}
    };

    private static List<CondorVM> vms() {
        return vms(vm(0), vm(1), vm(2));
    }

    private static List<Task> matrixTasks() {
        List<Task> tasks = new ArrayList<Task>();
        for (int id = 1; id <= 3; id++) {
            Task task = new Task(id, 1000L);
            Map<Integer, Double> projection = new LinkedHashMap<Integer, Double>();
            for (int vmId = 0; vmId < 3; vmId++) {
                projection.put(Integer.valueOf(vmId), Double.valueOf(ECT[id - 1][vmId]));
            }
            task.setVmExecutionCostSeconds(projection);
            tasks.add(task);
        }
        return tasks;
    }

    private static void run(StaticIndependentPlanningSupport planner, List<Task> tasks, List<CondorVM> vms)
            throws Exception {
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        planner.run();
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 1L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }

    private static List<CondorVM> vms(CondorVM... array) {
        return new ArrayList<CondorVM>(Arrays.asList(array));
    }

    @Test
    void mctMatchesBraunEctMatrixHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMctPlanningAlgorithm(), tasks, vms());
        // Braun et al. 2001 §4.2 hand derivation (implemented as availability-aware MCT):
        // t1: min(2,5,1) -> VM2 (av2=1)
        // t2: av=(0,0,1) -> min(3, 2, 1+6=7) -> VM1 (av1=2)
        // t3: av=(0,2,1) -> min(4, 2+3=5, 1+2=3) -> VM2
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void minMinMatchesBraunHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMinMinPlanningAlgorithm(), tasks, vms());
        // Hand derivation (§4.4, round-by-round minimum ECT): t1 -> 2, t2 -> 1, t3 -> 2.
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void maxMinMatchesBraunHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMaxMinPlanningAlgorithm(), tasks, vms());
        // Hand derivation (largest of the round-by-round minimum ECTs): t1 -> 0, t2 -> 1,
        // t3 -> 2.
        assertEquals(0, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void sufferageMatchesBraunHandDerivationAndConvergesWithMinMinOnThisMatrix() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticSufferagePlanningAlgorithm(), tasks, vms());
        // Hand derivation (largest second-best minus best gap, ties broken by ascending
        // task ID): t1 -> 2, t2 -> 1, t3 -> 2; on this matrix it converges with MinMin
        // (a known literature phenomenon, not a coincidence -- the two methods also
        // tend to agree when no contention stacking occurs).
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void metStaysPureEctFastestMachineForEveryTask() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMetPlanningAlgorithm(), tasks, vms());
        // MET (§4.1) pure ECT: t1 -> 2 (1), t2 -> 1 (2), t3 -> 2 (2).
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void olbQueuesToLeastWorkedMachineThenTieBreaksOnAscendingId() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticOlbPlanningAlgorithm(), tasks, vms());
        // Braun et al. 2001 §4.3 hand derivation (least availability, ties to the
        // smaller VM ID):
        // t1: av=(0,0,0) -> VM0 (av0=2)
        // t2: av=(2,0,0) -> VM1 (av1=2)
        // t3: av=(2,2,0) -> VM2
        assertEquals(0, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }
}
