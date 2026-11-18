package org.workflowsim.planning.naco;

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

class NacoAdapterTest {

    @Test
    void convertsBytesToMegabitsAndExcludesParentProducedInputFromDiskRead() {
        Task parent = task(2, 1L, 1);
        Task child = task(1, 1L, 1);
        connect(parent, child);
        parent.addFile(file("shared", 1_000_000.0, FileType.OUTPUT));
        child.addFile(file("shared", 1_000_000.0, FileType.INPUT));
        child.addFile(file("external", 2_000_000.0, FileType.INPUT));
        List<CondorVM> vms = Arrays.asList(vm(0, 100.0, 100L, 1), vm(1, 100.0, 100L, 1));
        PlatformProfile platform = platform(vms, 50);

        NacoDncProblem problem = NacoAdapter.toProblem(Arrays.asList(parent, child), vms, platform);

        assertEquals(16.0, problem.getTask(1).getInputBits(), 1.0e-9);
        assertEquals(8.0, problem.getTask(2).getEdgeBitsByChild().get(1), 1.0e-9);
        assertEquals(100.0, problem.getNetworkSpeed(0), 1.0e-9);
        assertEquals(50.0, problem.getDiskSpeed(0), 1.0e-9);

        NacoSchedule schedule =
                new NacoDncEvaluator().evaluate(Arrays.asList(new NacoGene(2, 0), new NacoGene(1, 0)), problem);

        assertEquals(0.17, schedule.getTaskPlan(1).getStartSeconds(), 1.0e-9);
        assertEquals(0.66, schedule.getTaskPlan(1).getFinishSeconds(), 1.0e-9);
    }

    @Test
    void excludesPeIncompatibleCoordinatesFromEveryGene() {
        Task task = task(1, 100L, 2);
        List<CondorVM> vms = Arrays.asList(vm(0, 100.0, 100L, 1), vm(1, 1.0, 100L, 2));
        PlatformProfile platform = platform(vms, 50);

        NacoDncProblem problem = NacoAdapter.toProblem(Collections.singletonList(task), vms, platform);

        assertFalse(problem.getTask(1).getComputeSecondsByVm().containsKey(0));

        assertEquals(100.0, problem.getTask(1).getComputeSecondsByVm().get(1), 1.0e-9);
        assertEquals(100.0, new NacoDncEvaluator().heft(problem).getMakespanSeconds(), 1.0e-9);
        assertEquals(1, new NacoDncEvaluator().heft(problem).getTaskPlan(1).getVmId());
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

    private static CondorVM vm(int id, double mips, long bandwidth, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, bandwidth, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }

    private static PlatformProfile platform(List<CondorVM> vms, int storageRate) {
        PlatformProfile.Builder builder = PlatformProfile.builder("naco-adapter-test");
        builder.storage(new PlatformProfile.StorageSpec(10000L, storageRate));
        for (int index = 0; index < vms.size(); index++) {
            CondorVM vm = vms.get(index);
            builder.addHost(
                    new PlatformProfile.HostSpec(index, vm.getNumberOfPes(), vm.getMips(), 2048, 10000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    vm.getId(),
                    vm.getMips(),
                    vm.getNumberOfPes(),
                    512,
                    vm.getBw(),
                    10000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(vm.getId(), index);
        }
        return builder.build();
    }
}
