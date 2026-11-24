package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * FGCS-R3 (2026-11-24, A2): with input 100 B at 100 B/s and output 100 B at 100 B/s, the
     * uncached VM 0 predicts 1 + 1e-6 (read) + 1 (compute) + 1 + 1e-6 (write) = 3.000002 s, cost
     * ceil = 4; the cached VM 1 predicts 2.000001 s plus the 5 s container delay = 7.000001 s, cost
     * 8. A budget of 1 fits no VM of the declared pool. EBPSM's published fallback is elastic
     * provisioning, which this fixed-pool subset does not model, so the task must fail explicitly
     * instead of being skipped until the event queue drains.
     */
    @Test
    void failsFastWhenNoVmOfThePoolFitsTheTaskBudget() {
        Task task = new Task(1, 100L);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        task.getFileList().add(file("output.bin", FileType.OUTPUT));
        Job job = new Job(1, 100L);
        job.getTaskList().add(task);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config(1.0));
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, scheduler::run);
        assertTrue(failure.getMessage().contains("budget"), failure.getMessage());
    }

    /**
     * FGCS-R3 (2026-11-24, A2): Algorithm 2 schedules a task only when a suitable VM is found.
     * Budget 4 fits VM 0 (cost 4, derived above) but VM 0 is busy; idle VM 1 costs 8. Because a
     * busy VM always returns and caches only grow, the task is deferred to a later cycle and cannot
     * starve.
     */
    @Test
    void defersWhenOnlyABusyVmFitsTheTaskBudget() {
        Task task = new Task(1, 100L);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        task.getFileList().add(file("output.bin", FileType.OUTPUT));
        Job job = new Job(1, 100L);
        job.getTaskList().add(task);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config(4.0));
        CondorVM busy = vm(0);
        busy.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(busy, vm(1)));

        scheduler.run();

        assertTrue(scheduler.getScheduledList().isEmpty());
    }

    /**
     * FGCS-R3 (2026-11-24, A2): the prediction uses the effective execution length of the task on
     * the candidate VM (TaskExecutionModel), not the raw length. Raw 1000 MI / 100 MIPS is 10 s on
     * both VMs (cost >= 10 > budget 5, so the raw model would fail the task), but the declared cost
     * matrix gives VM 0 (container deployed) 1 s, cost 1; VM 1 costs 10 + 5 s container = 15.
     */
    @Test
    void predictionUsesTheEffectiveExecutionLength() {
        Task task = new Task(1, 1000L);
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        costs.put(0, 1.0);
        costs.put(1, 10.0);
        task.setVmExecutionCostSeconds(costs);
        Job job = new Job(1, 1000L);
        job.getTaskList().add(task);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config(5.0));
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        scheduler.run();

        assertEquals(0, job.getVmId());
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

    /**
     * FGCS-R3 (2026-11-24, A1): root cause — outputs were only "published" at dispatch, when the
     * Task status is never SUCCESS, so nothing ever entered the cache. Publication now happens in
     * the job-completion callback. The producer runs on VM 1 (VM 0 busy). Consumer with input
     * new.bin (100 B at 100 B/s), 100 MI at 100 MIPS and no output: uncached VM 0 predicts
     * 1 + 1e-6 + 1 = 2.000001 s (cost 3); VM 1, holding the published output, predicts 1 s
     * (cost 1). Budget 1.5 therefore selects VM 1 only through the cache.
     */
    @Test
    void successfulOutputIsPublishedAndLaterReadAsCached() throws Exception {
        Job first = producer();
        Job second = consumer();
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(publicationConfig());
        CondorVM vm0 = vm(0);
        CondorVM vm1 = vm(1);
        vm0.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
        scheduler.setVmList(Arrays.asList(vm0, vm1));
        scheduler.setCloudletList(Collections.singletonList(first));
        scheduler.run();
        assertEquals(1, first.getVmId());
        markOutcome(first, org.cloudbus.cloudsim.Cloudlet.SUCCESS);
        scheduler.onJobReturned(first);
        vm0.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);
        vm1.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);

        scheduler.setCloudletList(Collections.singletonList(second));
        scheduler.getScheduledList().clear();
        scheduler.run();

        assertTrue(scheduler.getScheduledList().contains(second));
        assertEquals(1, second.getVmId());
    }

    /**
     * FGCS-R3 (2026-11-24, A1): EBPSM's VM local storage keeps both d_in and d_out after a task
     * executes (Hilman et al., Sec. 3.2). A successful reader of shared.bin on VM 1 therefore makes
     * VM 1 the cached choice for a later reader of shared.bin: its prediction drops from
     * 1 + 1e-6 + 1 = 2.000001 s (cost 3) to 1 s (cost 1), and budget 1.5 admits only VM 1.
     */
    @Test
    void successfulTaskInputsAreRetainedInTheVmCache() throws Exception {
        Task reader = new Task(1, 100L);
        reader.getFileList().add(file("shared.bin", FileType.INPUT));
        Job first = new Job(1, 100L);
        first.getTaskList().add(reader);
        Task again = new Task(2, 100L);
        again.getFileList().add(file("shared.bin", FileType.INPUT));
        Job second = new Job(2, 100L);
        second.getTaskList().add(again);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(publicationConfig());
        CondorVM vm0 = vm(0);
        CondorVM vm1 = vm(1);
        vm0.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
        scheduler.setVmList(Arrays.asList(vm0, vm1));
        scheduler.setCloudletList(Collections.singletonList(first));
        scheduler.run();
        markOutcome(first, org.cloudbus.cloudsim.Cloudlet.SUCCESS);
        scheduler.onJobReturned(first);
        vm0.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);
        vm1.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);

        scheduler.setCloudletList(Collections.singletonList(second));
        scheduler.getScheduledList().clear();
        scheduler.run();

        assertEquals(1, second.getVmId());
    }

    /**
     * FGCS-R3 (2026-11-24, A1): a failed attempt publishes nothing, so the consumer derived above
     * costs 3 > 1.5 on both idle VMs and fails explicitly under the A2 contract.
     */
    @Test
    void unsuccessfulOutputIsNotPublishedAsAReadableInput() throws Exception {
        Job first = producer();
        Job second = consumer();
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(publicationConfig());
        CondorVM vm0 = vm(0);
        CondorVM vm1 = vm(1);
        vm0.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
        scheduler.setVmList(Arrays.asList(vm0, vm1));
        scheduler.setCloudletList(Collections.singletonList(first));
        scheduler.run();
        markOutcome(first, org.cloudbus.cloudsim.Cloudlet.FAILED);
        scheduler.onJobReturned(first);
        vm0.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);
        vm1.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);

        scheduler.setCloudletList(Collections.singletonList(second));
        scheduler.getScheduledList().clear();

        assertThrows(IllegalStateException.class, scheduler::run);
    }

    /**
     * FGCS-R3 (2026-11-24, A2): the stall check is a whole-round property. The consumer (Job 2,
     * cost 3 > 1.5 on both idle VMs, derived above) precedes producer Job 3 in the queue order;
     * dispatching the producer makes a VM busy whose return can publish new.bin, so the consumer is
     * deferred, not failed.
     */
    @Test
    void aLaterDispatchInTheSameRoundPreventsAPrematureFailure() {
        Job blocked = consumer();
        Task task = new Task(1, 100L);
        task.getFileList().add(file("new.bin", FileType.OUTPUT));
        Job producing = new Job(3, 100L);
        producing.getTaskList().add(task);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(publicationConfig());
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));
        scheduler.setCloudletList(Arrays.asList(blocked, producing));

        scheduler.run();

        assertEquals(Collections.singletonList(producing), scheduler.getScheduledList());
    }

    /** FGCS-R3 (2026-11-24, A2): a Job no declared VM can host fails instead of waiting forever. */
    @Test
    void failsFastWhenNoDeclaredVmIsCompatible() {
        Job job = producer();
        job.setNumberOfPes(2);
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(publicationConfig());
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        assertThrows(IllegalArgumentException.class, scheduler::run);
    }

    private static Job producer() {
        Task producer = new Task(1, 100L);
        producer.getFileList().add(file("new.bin", FileType.OUTPUT));
        Job first = new Job(1, 100L);
        first.getTaskList().add(producer);
        return first;
    }

    private static Job consumer() {
        Task consumer = new Task(2, 100L);
        consumer.getFileList().add(file("new.bin", FileType.INPUT));
        Job second = new Job(2, 100L);
        second.getTaskList().add(consumer);
        return second;
    }

    private static void markOutcome(Job job, int status) throws Exception {
        job.setCloudletStatus(status);
        for (Task task : job.getTaskList()) {
            task.setCloudletStatus(status);
        }
    }

    private static EbpsmConfig publicationConfig() {
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
        return new EbpsmConfig(budgets, inputs, containers, images, costs, 100.0, 100.0, 0.0);
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
