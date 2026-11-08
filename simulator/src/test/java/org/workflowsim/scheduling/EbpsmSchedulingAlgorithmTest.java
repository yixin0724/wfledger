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

    @Test
    void skipsPeIncompatibleVmEvenWhenItHasTheContainer() {
        Task task = new Task(1, 100L);
        task.setNumberOfPes(2);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        Job job = new Job(1, 100L);
        job.setNumberOfPes(2);
        job.getTaskList().add(task);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config(20.0));
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0, 1), vm(1, 2)));

        scheduler.run();

        assertEquals(1, job.getVmId());
    }

    @Test
    void decimalMegabyteBandwidthDoesNotInflateTheBudget() {
        Task task = new Task(1, 100L);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        task.getFileList().add(file("output.bin", FileType.OUTPUT));
        Job job = new Job(1, 100L);
        job.getTaskList().add(task);
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        for (int vm : Arrays.asList(0, 1)) {
            inputs.put(vm, Collections.<String>emptySet());
            containers.put(vm, Collections.<String>emptySet());
            costs.put(vm, 1.0);
        }
        EbpsmConfig config = new EbpsmConfig(
                Collections.singletonMap(1, 9.0),
                inputs,
                containers,
                Collections.singletonMap(1, "img"),
                costs,
                100.0,
                100.0,
                5.0);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        scheduler.run();

        assertTrue(scheduler.getScheduledList().contains(job));
    }

    @Test
    void unsuccessfulOutputIsNotPublishedAsAReadableInput() throws Exception {
        Task producer = new Task(1, 100L);
        producer.getFileList().add(file("new.bin", FileType.OUTPUT));
        Job first = new Job(1, 100L);
        first.getTaskList().add(producer);
        Task consumer = new Task(2, 100L);
        consumer.getFileList().add(file("new.bin", FileType.INPUT));
        Job second = new Job(2, 100L);
        second.getTaskList().add(consumer);
        Map<Integer, Double> budgets = new HashMap<Integer, Double>();
        budgets.put(1, 10.0);
        budgets.put(2, 1.5);
        Map<Integer, String> images = new HashMap<Integer, String>();
        images.put(1, "img");
        images.put(2, "img");
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        for (int vm : Arrays.asList(0, 1)) {
            inputs.put(vm, Collections.<String>emptySet());
            containers.put(vm, Collections.singleton("img"));
            costs.put(vm, 1.0);
        }
        EbpsmConfig config = new EbpsmConfig(budgets, inputs, containers, images, costs, 100.0, 100.0, 0.0);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config);
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));
        scheduler.setCloudletList(Collections.singletonList(first));
        scheduler.run();
        for (Object item : scheduler.getVmList()) {
            ((CondorVM) item).setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);
        }

        scheduler.setCloudletList(Collections.singletonList(second));
        scheduler.run();

        assertTrue(!scheduler.getScheduledList().contains(second));
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
        return vm(id, 1);
    }

    private static CondorVM vm(int id, int pes) {
        return new CondorVM(id, 0, 100.0, pes, 512, 100L, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
