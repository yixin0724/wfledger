package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class LocalCommPlanningExtensionsTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void communicationSecondsAreZeroOnSameVmAndMatchEdgeWeightAcrossUniformVms() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        Task sibling = new Task(3, 4);
        connect(entry, exit, 7.0);
        connect(entry, sibling, 0.0);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit, sibling), first, second);
        planner.prepare();

        assertEquals(7.0, planner.communicationSeconds(entry, exit, first, second), EPSILON);
        assertEquals(7.0, planner.communicationSeconds(entry, exit, second, first), EPSILON);
        assertEquals(0.0, planner.communicationSeconds(entry, exit, first, first), EPSILON, "same-VM transfer is zero");
        assertEquals(
                0.0,
                planner.communicationSeconds(entry, sibling, first, second),
                EPSILON,
                "communication seconds are zero when the edge shares no file");
    }

    @Test
    void communicationSecondsUseSlowerEndpointBandwidth() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        connect(entry, exit, 8.0);
        CondorVM slow = vm(3, 2L);
        CondorVM fast = vm(8, 4L);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), slow, fast);
        planner.prepare();

        assertEquals(4.0, planner.communicationSeconds(entry, exit, slow, fast), EPSILON);
        assertEquals(4.0, planner.communicationSeconds(entry, exit, fast, slow), EPSILON);
        assertEquals(
                4.0,
                planner.meanCommunicationSeconds(entry, exit),
                EPSILON,
                "the per-pair convention must match the cross-pair mean convention (equal for uniform pairs)");
    }

    /**
     * FGCS-R3 (2026-11-24) m2 regression. Topcuoglu, Hariri and Wu (IEEE TPDS 13(3), 2002, Section 3)
     * define the average communication cost of an edge as {@code c̄(i,k) = L̄ + data(i,k) / B̄}, where
     * {@code B̄} is the average transfer rate among the processors; it is not the mean of the per-pair
     * transfer times. Hand derivation (no startup term in this model, so {@code L̄ = 0}): an 8 MB edge
     * over three VMs with endpoint bandwidths 1, 2 and 4 MB/s; each ordered distinct pair transfers at
     * the slower endpoint rate: (1,2)=1, (1,4)=1, (2,1)=1, (2,4)=2, (4,1)=1, (4,2)=2, so
     * {@code B̄ = 8/6 = 4/3} MB/s and {@code c̄ = 8 / (4/3) = 6.0} s. The superseded mean-of-times
     * convention gives (8+8+8+4+8+4)/6 = 6.667 s and is rejected.
     */
    @Test
    void meanCommunicationUsesDataOverAverageTransferRatePerTopcuoglu2002() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        connect(entry, exit, 8.0);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), vm(3, 1L), vm(5, 2L), vm(8, 4L));
        planner.prepare();

        assertEquals(6.0, planner.meanCommunicationSeconds(entry, exit), EPSILON);
    }

    /**
     * FGCS-R3 (2026-11-24) m4 regression. A child that declares a file both as INPUT and as OUTPUT does
     * not read it ({@link FileItem#isRealInputFile}); the runtime stage-in estimate and the planner's
     * own data-arrival estimate both skip it, so the communication-cost estimate used by the ranks must
     * skip it too. Expected value by contract: 0 bytes cross the edge, so both the pairwise and the
     * average communication seconds are 0.
     */
    @Test
    void communicationIgnoresAChildInputThatTheChildAlsoProduces() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        connect(entry, exit, 0.0);
        FileItem produced = new FileItem("shared-name", 5_000_000.0);
        produced.setType(Parameters.FileType.OUTPUT);
        entry.addFile(produced);
        FileItem notReallyRead = new FileItem("shared-name", 5_000_000.0);
        notReallyRead.setType(Parameters.FileType.INPUT);
        exit.addFile(notReallyRead);
        FileItem childOutput = new FileItem("shared-name", 5_000_000.0);
        childOutput.setType(Parameters.FileType.OUTPUT);
        exit.addFile(childOutput);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), first, second);
        planner.prepare();

        assertEquals(0.0, planner.communicationSeconds(entry, exit, first, second), EPSILON);
        assertEquals(0.0, planner.meanCommunicationSeconds(entry, exit), EPSILON);
    }

    /**
     * FGCS-R3 (2026-11-24) m4 companion. The runtime pre-execution estimate and the planner's
     * data-arrival estimate charge every real INPUT declaration, so a repeated declaration is charged
     * twice; the rank communication estimate must use the same convention. Derivation: a 2 MB file
     * declared twice as child INPUT over 1 MB/s endpoints gives (2 + 2) / 1 = 4 s.
     */
    @Test
    void repeatedRealInputDeclarationsAreChargedLikeTheRuntimeEstimate() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        connect(entry, exit, 2.0);
        FileItem repeated = new FileItem("edge-1-2", 2_000_000.0);
        repeated.setType(Parameters.FileType.INPUT);
        exit.addFile(repeated);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), first, second);
        planner.prepare();

        assertEquals(4.0, planner.communicationSeconds(entry, exit, first, second), EPSILON);
        assertEquals(4.0, planner.meanCommunicationSeconds(entry, exit), EPSILON);
    }

    @Test
    void allocateOptimisticAddsOctToFinishBeforeChoosingVm() {
        Task entry = new Task(1, 4);
        Task exit = new Task(2, 2);
        connect(entry, exit, 0.0);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), first, second);
        planner.prepare();
        double stageIn = planner.stageInFinishTime();

        double entryFinish = planner.allocate(entry, null, stageIn);
        assertEquals(3, entry.getVmId());
        assertEquals(stageIn + 4.0, entryFinish, EPSILON);

        Map<CondorVM, Double> oct = new LinkedHashMap<CondorVM, Double>();
        oct.put(first, Double.valueOf(100.0));
        oct.put(second, Double.valueOf(0.0));
        double exitFinish = planner.allocateOptimistic(exit, oct, stageIn);

        assertEquals(8, exit.getVmId(), "the OCT penalty must move the task off the smaller-ID VM");
        assertEquals(stageIn + 6.0, exitFinish, EPSILON, "finish time is EFT, excluding the OCT term");
        assertEquals(stageIn + 4.0, exit.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 6.0, planner.plannedFinishOf(exit), EPSILON);
    }

    @Test
    void allocateOptimisticTiesChooseSmallerVmId() {
        Task entry = new Task(1, 2);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(Collections.singletonList(entry), first, second);
        planner.prepare();
        double stageIn = planner.stageInFinishTime();

        Map<CondorVM, Double> oct = new LinkedHashMap<CondorVM, Double>();
        oct.put(first, Double.valueOf(5.0));
        oct.put(second, Double.valueOf(5.0));
        planner.allocateOptimistic(entry, oct, stageIn);

        assertEquals(3, entry.getVmId(), "a score tie picks the smaller VM ID");
        assertEquals(stageIn + 2.0, planner.plannedFinishOf(entry), EPSILON);
    }

    @Test
    void allocateOptimisticRequiresOctForEveryCandidateVm() {
        Task entry = new Task(1, 2);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(Collections.singletonList(entry), first, second);
        planner.prepare();
        double stageIn = planner.stageInFinishTime();

        assertThrows(IllegalArgumentException.class, () -> planner.allocateOptimistic(entry, null, stageIn));

        Map<CondorVM, Double> partial = new LinkedHashMap<CondorVM, Double>();
        partial.put(first, Double.valueOf(1.0));
        IllegalStateException missing =
                assertThrows(IllegalStateException.class, () -> planner.allocateOptimistic(entry, partial, stageIn));
        assertTrue(missing.getMessage().contains("missing OCT"));
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

    private static LocalHeftPlanningAlgorithm planner(List<Task> tasks, CondorVM... vms) {
        PlatformProfile.Builder platform = PlatformProfile.builder("local-ext-unit");
        for (int index = 0; index < vms.length; index++) {
            platform.addHost(new PlatformProfile.HostSpec(index, 2, 2.0, 2048, 10_000L, 1_000_000L));
            platform.addVm(new PlatformProfile.VmSpec(
                    vms[index].getId(),
                    vms[index].getMips(),
                    1,
                    512,
                    vms[index].getBw(),
                    10_000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        SimulationConfig config = SimulationConfig.builder("ext-unit.dax", vms.length)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        LocalHeftPlanningAlgorithm planner =
                new LocalHeftPlanningAlgorithm(new PlanningContext(config, platform.build()));
        planner.setTaskList(new ArrayList<Task>(tasks));
        planner.setVmList(new ArrayList<CondorVM>(Arrays.asList(vms)));
        return planner;
    }

    private static CondorVM vm(int id, long bw) {
        return new CondorVM(id, 0, 1.0, 1, 512, bw, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
