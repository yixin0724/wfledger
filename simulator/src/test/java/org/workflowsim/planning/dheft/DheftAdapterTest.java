package org.workflowsim.planning.dheft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

class DheftAdapterTest {

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

        DheftProblem problem = DheftAdapter.toProblem(Arrays.asList(parent, child), vms, platform(vms));

        assertEquals(100_000_000.0, problem.getBandwidth(3, 8), 1.0e-9);
        DheftEdge edge = problem.getTask(1).getEdgesByChild().get(2);
        assertEquals(1_000_000.0, edge.getDataBytes(), 1.0e-9);
        assertEquals(0.01, edge.getDataBytes() / problem.getBandwidth(3, 8), 1.0e-12);
    }

    @Test
    void fasterOnePeVmCannotCaptureATwoPeTask() {
        Task task = task(1, 100L, 2);
        List<CondorVM> vms = Arrays.asList(vm(3, 2.0, 1, 100L), vm(8, 1.0, 2, 100L));

        DheftProblem problem = DheftAdapter.toProblem(Collections.singletonList(task), vms, platform(vms));
        DheftSchedule schedule = new DheftPlanner().schedule(problem);

        assertFalse(problem.getTask(1).getComputeSecondsByVm().containsKey(3));
        assertEquals(8, schedule.getVmId(1));
        assertEquals(100.0, schedule.getMakespanSeconds(), 1.0e-9);
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

    private static PlatformProfile platform(List<CondorVM> vms) {
        PlatformProfile.Builder builder = PlatformProfile.builder("dheft-adapter-test");
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
