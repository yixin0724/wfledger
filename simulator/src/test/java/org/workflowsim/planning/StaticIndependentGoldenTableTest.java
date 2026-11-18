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

class StaticIndependentGoldenTableTest {

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

        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void minMinMatchesBraunHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMinMinPlanningAlgorithm(), tasks, vms());

        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void maxMinMatchesBraunHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMaxMinPlanningAlgorithm(), tasks, vms());

        assertEquals(0, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void sufferageMatchesBraunHandDerivationAndConvergesWithMinMinOnThisMatrix() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticSufferagePlanningAlgorithm(), tasks, vms());

        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void metStaysPureEctFastestMachineForEveryTask() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMetPlanningAlgorithm(), tasks, vms());

        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void olbQueuesToLeastWorkedMachineThenTieBreaksOnAscendingId() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticOlbPlanningAlgorithm(), tasks, vms());

        assertEquals(0, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }
}
