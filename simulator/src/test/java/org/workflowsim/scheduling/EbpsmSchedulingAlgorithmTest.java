package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.EbpsmConfig;
import org.workflowsim.utils.Parameters.FileType;

/** EBPSM cache preference and budget-gated VM selection. */
class EbpsmSchedulingAlgorithmTest {

    @Test
    void prefersTheVmWithInputAndContainerCaches() {
        Task task = new Task(1, 100L);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        task.getFileList().add(file("output.bin", FileType.OUTPUT));
        Job job = new Job(1, 100L);
        job.getTaskList().add(task);
        EbpsmConfig config = config(10.0);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        scheduler.run();

        assertEquals(1, job.getVmId());
    }

    @Test
    void doesNotDispatchWhenEveryCandidateExceedsTheTaskBudget() {
        Task task = new Task(1, 100L);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        task.getFileList().add(file("output.bin", FileType.OUTPUT));
        Job job = new Job(1, 100L);
        job.getTaskList().add(task);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config(1.0));
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        scheduler.run();

        assertTrue(scheduler.getScheduledList().isEmpty());
    }

    private static EbpsmConfig config(double budget) {
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        inputs.put(0, Collections.<String>emptySet());
        inputs.put(1, Collections.singleton("input.bin"));
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        containers.put(0, Collections.singleton("img"));
        containers.put(1, Collections.<String>emptySet());
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        costs.put(0, 1.0);
        costs.put(1, 1.0);
        return new EbpsmConfig(
                Collections.singletonMap(1, budget),
                inputs,
                containers,
                Collections.singletonMap(1, "img"),
                costs,
                100.0,
                100.0,
                5.0);
    }

    private static FileItem file(String name, FileType type) {
        FileItem file = new FileItem(name, 100.0);
        file.setType(type);
        return file;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 100.0, 1, 512, 100L, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
