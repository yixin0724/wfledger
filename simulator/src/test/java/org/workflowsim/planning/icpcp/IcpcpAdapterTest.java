package org.workflowsim.planning.icpcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.FileType;

class IcpcpAdapterTest {

    @Test
    void convertsDeclaredDecimalMegabytesAndCountsDuplicateOutputsAsMembership() {
        Task parent = task(1, 1000L, 1);
        Task child = task(2, 1000L, 1);
        connect(parent, child);
        FileItem output = file("f", 1_000_000.0, FileType.OUTPUT);
        parent.addFile(output);
        parent.addFile(output);
        child.addFile(file("f", 1_000_000.0, FileType.INPUT));
        List<CondorVM> vms = Arrays.asList(vm(3, 1000.0, 1, 100L), vm(8, 1000.0, 1, 100L));

        IcpcpProblem problem = IcpcpAdapter.toProblem(Arrays.asList(parent, child), vms, platform(vms, 20), 100.0);

        assertEquals(0.01, problem.getTask(1).getCommunicationSecondsByChild().get(2), 1.0e-12);
    }

    @Test
    void fanInAdapterProducesAfeasibleProblem() {
        Task parent = task(1, 1000L, 1);
        Task second = task(2, 2000L, 1);
        Task third = task(3, 3000L, 1);
        Task join = task(4, 1000L, 1);
        FileItem output = file("x", 1_000_000.0, FileType.OUTPUT);
        parent.addFile(output);
        parent.addFile(output);
        for (Task child : Arrays.asList(second, third, join)) {
            child.addFile(file("x", 1_000_000.0, FileType.INPUT));
        }
        connect(parent, second);
        connect(parent, third);
        connect(second, join);
        connect(third, join);
        List<CondorVM> vms = Arrays.asList(vm(3, 1000.0, 1, 100L), vm(8, 500.0, 1, 50L));

        IcpcpProblem problem =
                IcpcpAdapter.toProblem(Arrays.asList(parent, second, third, join), vms, platform(vms, 15), 1000.0);
        IcpcpSchedule schedule = new IcpcpPlanner(problem).schedule();

        assertEquals(4, schedule.getTaskPlans().size());
    }

    @Test
    void fasterOnePeVmCannotCaptureATwoPeTaskAndBootstrapUsesTheFallbackVm() {
        Task task = task(1, 100L, 2);
        List<CondorVM> vms = Arrays.asList(vm(3, 2.0, 1, 100L), vm(8, 1.0, 2, 100L));

        IcpcpProblem problem = IcpcpAdapter.toProblem(Collections.singletonList(task), vms, platform(vms, 20), 200.0);
        IcpcpSchedule schedule = new IcpcpPlanner(problem).schedule();

        assertFalse(problem.getTask(1).getComputeSecondsByVm().containsKey(3));
        assertEquals(8, schedule.getTaskPlan(1).getVmId());

        assertEquals(55.1, schedule.getTaskPlan(1).getStartSeconds(), 1.0e-9);
        assertEquals(155.1, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void sharedStorageBootstrapAndIntegerMiInputEnvelopeSetAbsoluteTaskWindows() {
        Task task = task(1, 1000L, 1);
        task.addFile(file("external", 20_000_000.0, FileType.INPUT));
        List<CondorVM> vms = Collections.singletonList(vm(3, 1000.0, 1, 100L));

        IcpcpSchedule schedule = new IcpcpPlanner(
                        IcpcpAdapter.toProblem(Collections.singletonList(task), vms, platform(vms, 20), 3.0))
                .schedule();

        assertEquals(0.21, schedule.getTaskPlan(1).getStartSeconds(), 1.0e-9);
        assertEquals(2.21, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void rejectsADeadlineThatCannotCoverSharedBootstrapAndInputEnvelope() {
        Task task = task(1, 1000L, 1);
        task.addFile(file("external", 20_000_000.0, FileType.INPUT));
        List<CondorVM> vms = Collections.singletonList(vm(3, 1000.0, 1, 100L));

        assertThrows(IllegalArgumentException.class, () -> new IcpcpPlanner(
                        IcpcpAdapter.toProblem(Collections.singletonList(task), vms, platform(vms, 20), 2.0))
                .schedule());
    }

    private static Task task(int id, long length, int pes) {
        Task task = new Task(id, length);
        task.setNumberOfPes(pes);
        return task;
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static FileItem file(String name, double bytes, FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }

    private static CondorVM vm(int id, double mips, int pes, long bandwidth) {
        return new CondorVM(id, 0, mips, pes, 512, bandwidth, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }

    private static PlatformProfile platform(List<CondorVM> vms, int storageRate) {
        PlatformProfile.Builder builder = PlatformProfile.builder("icpcp-adapter-test")
                .storage(new PlatformProfile.StorageSpec(1_000_000L, storageRate));
        for (int index = 0; index < vms.size(); index++) {
            CondorVM vm = vms.get(index);
            builder.addHost(new PlatformProfile.HostSpec(
                            index, vm.getNumberOfPes(), vm.getMips(), 2048, 10_000L, 1_000_000L))
                    .addVm(new PlatformProfile.VmSpec(
                            vm.getId(),
                            vm.getMips(),
                            vm.getNumberOfPes(),
                            512,
                            vm.getBw(),
                            10_000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                    .pinVmToHost(vm.getId(), index);
        }
        return builder.build();
    }
}
