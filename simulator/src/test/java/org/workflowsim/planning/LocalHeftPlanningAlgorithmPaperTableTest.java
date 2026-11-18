package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class LocalHeftPlanningAlgorithmPaperTableTest {

    private static final double EPSILON = 1.0e-9;

    private static final double BOOTSTRAP = 110.1;

    private static final double[][] COSTS = {
        {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
        {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
    };

    private static final int[][] EDGES = {
        {1, 2, 18}, {1, 3, 12}, {1, 4, 9}, {1, 5, 11}, {1, 6, 14},
        {2, 8, 19}, {2, 9, 16}, {3, 7, 23}, {4, 8, 27}, {4, 9, 23},
        {5, 9, 13}, {6, 8, 15}, {7, 10, 17}, {8, 10, 11}, {9, 10, 13}
    };

    @Test
    void paperTableUpwardRanksMatchIndependentHandDerivation() {
        Fixture fixture = paperFixture();

        fixture.planner.run();

        double[] upward = {108, 77, 80, 80, 69, 190.0 / 3.0, 128.0 / 3.0, 107.0 / 3.0, 133.0 / 3.0, 44.0 / 3.0};
        for (int i = 0; i < fixture.tasks.length; i++) {
            assertEquals(
                    upward[i],
                    fixture.planner.upwardRankOf(fixture.tasks[i]),
                    EPSILON,
                    "paper-table task " + (i + 1) + " upward rank must equal the hand-derived value");
        }
    }

    @Test
    void highestRankEntryTaskTakesTriviallyDerivedEarliestFinishProcessor() {
        Fixture fixture = paperFixture();
        Task entry = fixture.tasks[0];

        fixture.planner.run();

        assertEquals(2, entry.getVmId(), "the entry task must choose vm2, the minimal-EFT VM (cost 9 s)");
        assertEquals(BOOTSTRAP, entry.getStaticScheduleStartTime(), EPSILON, "start is the root stage-in finish");
        assertEquals(BOOTSTRAP + 9.0, fixture.planner.plannedFinishOf(entry), EPSILON, "vm2 planned finish is 9 s");
    }

    @Test
    void plannedIntervalsNeverOverlapPerProcessorAndHonourCommunication() {
        Fixture fixture = paperFixture();

        fixture.planner.run();

        Map<Integer, List<double[]>> perVm = new LinkedHashMap<Integer, List<double[]>>();
        for (Task task : fixture.tasks) {
            double start = task.getStaticScheduleStartTime();
            double finish = fixture.planner.plannedFinishOf(task);
            assertTrue(finish > start, "task " + task.getCloudletId() + " has a non-empty planning interval");
            List<double[]> intervals = perVm.get(task.getVmId());
            if (intervals == null) {
                intervals = new ArrayList<double[]>();
                perVm.put(task.getVmId(), intervals);
            }
            intervals.add(new double[] {start, finish});
        }
        for (Map.Entry<Integer, List<double[]>> entry : perVm.entrySet()) {
            List<double[]> intervals = entry.getValue();
            Collections.sort(intervals, (a, b) -> Double.compare(a[0], b[0]));
            for (int i = 1; i < intervals.size(); i++) {
                assertTrue(
                        intervals.get(i)[0] >= intervals.get(i - 1)[1] - EPSILON,
                        "overlapping reserved intervals on vm" + entry.getKey() + ": "
                                + Arrays.toString(intervals.get(i - 1)) + " and "
                                + Arrays.toString(intervals.get(i)));
            }
        }

        for (int[] edge : EDGES) {
            Task parent = fixture.tasks[edge[0] - 1];
            Task child = fixture.tasks[edge[1] - 1];
            double childStart = child.getStaticScheduleStartTime();
            double parentFinish = fixture.planner.plannedFinishOf(parent);
            if (parent.getVmId() == child.getVmId()) {
                assertTrue(childStart >= parentFinish - EPSILON, "child early on same VM: " + edge[0] + "->" + edge[1]);
            } else {
                assertTrue(
                        childStart >= parentFinish + edge[2] - EPSILON,
                        "cross-VM dependency " + edge[0] + "->" + edge[1] + " starts before parent finish + link");
            }
        }
    }

    private static final class Fixture {
        final LocalHeftPlanningAlgorithm planner;
        final Task[] tasks;

        Fixture(LocalHeftPlanningAlgorithm planner, Task[] tasks) {
            this.planner = planner;
            this.tasks = tasks;
        }
    }

    private static Fixture paperFixture() {
        Task[] tasks = new Task[COSTS.length];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = new Task(i + 1, 1);
            Map<Integer, Double> projection = new LinkedHashMap<Integer, Double>();
            for (int vmId = 0; vmId < 3; vmId++) {
                projection.put(Integer.valueOf(vmId), Double.valueOf(COSTS[i][vmId]));
            }
            tasks[i].setVmExecutionCostSeconds(projection);
        }
        for (int[] edge : EDGES) {
            connect(tasks[edge[0] - 1], tasks[edge[1] - 1], edge[2]);
        }

        List<Task> reversed = new ArrayList<Task>(Arrays.asList(tasks));
        Collections.reverse(reversed);
        return new Fixture(planner(reversed, 2, 0, 1), tasks);
    }

    private static void connect(Task parent, Task child, double communicationSeconds) {
        parent.addChild(child);
        child.addParent(parent);
        if (communicationSeconds > 0.0) {
            String name = "edge-" + parent.getCloudletId() + "-" + child.getCloudletId();
            FileItem output = new FileItem(name, communicationSeconds * 1_000_000.0);
            output.setType(Parameters.FileType.OUTPUT);
            parent.addFile(output);
            FileItem input = new FileItem(name, communicationSeconds * 1_000_000.0);
            input.setType(Parameters.FileType.INPUT);
            child.addFile(input);
        }
    }

    private static LocalHeftPlanningAlgorithm planner(List<Task> tasks, int... vmIds) {
        PlatformProfile.Builder platform = PlatformProfile.builder("local-heft-paper");
        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (int index = 0; index < vmIds.length; index++) {
            int vmId = vmIds[index];
            platform.addHost(new PlatformProfile.HostSpec(index, 2, 2.0, 2048, 10_000L, 1_000_000L));
            platform.addVm(new PlatformProfile.VmSpec(
                    vmId, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            vms.add(vm(vmId));
        }
        SimulationConfig config = SimulationConfig.builder("heft-paper.dax", vmIds.length)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        LocalHeftPlanningAlgorithm planner =
                new LocalHeftPlanningAlgorithm(new PlanningContext(config, platform.build()));
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        return planner;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1.0, 1, 512, 1L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
