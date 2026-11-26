package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.EbpsmConfig;
import org.workflowsim.utils.Parameters.FileType;

class EbpsmSchedulingAlgorithmTest {

    @Test
    void prefersTheVmWithInputAndContainerCaches() {
        Task task = cachedTask();
        Job job = job(task);
        EbpsmSchedulingAlgorithm scheduler = scheduler(config(10.0, 1.0, 1.0), task);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        scheduler.run();

        assertEquals(1, job.getVmId());
        assertEquals(9.0, scheduler.getBudgetLedger().budgetOf(1), 1.0e-9);
    }

    @Test
    void rejectsAWorkflowBudgetBelowItsCheapestTypeCost() {
        Task task = cachedTask();
        EbpsmSchedulingAlgorithm scheduler = scheduler(config(8.0, 1.0, 1.0), task);
        scheduler.setCloudletList(Collections.singletonList(job(task)));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, scheduler::run);
        assertTrue(failure.getMessage().contains("budget"), failure.getMessage());
    }

    @Test
    void defersWhenOnlyABusyVmFitsTheTaskBudget() {
        Task task = cachedTask();
        Job job = job(task);
        EbpsmSchedulingAlgorithm scheduler = scheduler(config(10.0, 1.0, 3.0), task);
        CondorVM busy = vm(0);
        busy.setState(WorkflowSimTags.VM_STATUS_BUSY);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(busy, vm(1)));

        scheduler.run();

        assertTrue(scheduler.getScheduledList().isEmpty());
    }

    @Test
    void predictionUsesTheEffectiveExecutionLength() {
        Task task = new Task(1, 1000L);
        Map<Integer, Double> costs = new HashMap<Integer, Double>();
        costs.put(0, 1.0);
        costs.put(1, 10.0);
        task.setVmExecutionCostSeconds(costs);
        Job job = job(task);
        EbpsmSchedulingAlgorithm scheduler = scheduler(config(7.0, 1.0, 2.0), task);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        scheduler.run();

        assertEquals(0, job.getVmId());
        assertEquals(6.0, scheduler.getBudgetLedger().budgetOf(1), 1.0e-9);
    }

    @Test
    void skipsPeIncompatibleVmEvenWhenItHasTheContainer() {
        Task task = new Task(1, 100L);
        task.setNumberOfPes(2);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        Job job = job(task);
        job.setNumberOfPes(2);
        EbpsmSchedulingAlgorithm scheduler = scheduler(config(20.0, 1.0, 1.0), task);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0, 1), vm(1, 2)));

        scheduler.run();

        assertEquals(1, job.getVmId());
    }

    @Test
    void decimalMegabyteBandwidthDoesNotInflateTheBudget() {
        Task task = cachedTask();
        Job job = job(task);
        Map<Integer, Set<String>> empty = new HashMap<Integer, Set<String>>();
        Map<Integer, Double> prices = new HashMap<Integer, Double>();
        for (int vm : Arrays.asList(0, 1)) {
            empty.put(vm, Collections.<String>emptySet());
            prices.put(vm, 1.0);
        }
        EbpsmConfig config = EbpsmConfig.forWorkflowBudgets(
                Collections.singletonMap(0, 9.0),
                empty,
                empty,
                Collections.singletonMap(1, "img"),
                prices,
                100.0,
                100.0,
                5.0);
        EbpsmSchedulingAlgorithm scheduler = scheduler(config, task);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        scheduler.run();

        assertTrue(scheduler.getScheduledList().contains(job));
    }

    @Test
    void spareBudgetFromACheapCompletionUpgradesTheSuccessorToTheFasterVm() throws Exception {
        assertEquals(1, runChainAndReturnSecondVm(1.0));
    }

    @Test
    void completionAtTheSubBudgetKeepsTheSuccessorOnTheCheapVm() throws Exception {
        assertEquals(0, runChainAndReturnSecondVm(4.0));
    }

    private static int runChainAndReturnSecondVm(double firstFinish) throws Exception {
        Task first = new Task(1, 400L);
        Task second = new Task(2, 400L);
        second.addParent(first);
        first.addChild(second);
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, Double> prices = new HashMap<Integer, Double>();
        for (int vm : Arrays.asList(0, 1)) {
            inputs.put(vm, Collections.<String>emptySet());
            containers.put(vm, Collections.singleton("default"));
        }
        prices.put(0, 1.0);
        prices.put(1, 3.0);
        EbpsmConfig config = EbpsmConfig.forWorkflowBudgets(
                Collections.singletonMap(0, 9.0),
                inputs,
                containers,
                Collections.<Integer, String>emptyMap(),
                prices,
                100.0,
                100.0,
                0.0);
        EbpsmSchedulingAlgorithm scheduler = scheduler(config, first, second);
        CondorVM slow = vm(0, 1, 100.0);
        CondorVM fast = vm(1, 1, 200.0);
        scheduler.setVmList(Arrays.asList(slow, fast));
        Job firstJob = job(first);
        scheduler.setCloudletList(Collections.singletonList(firstJob));
        scheduler.run();
        assertEquals(0, firstJob.getVmId());
        assertEquals(4.0, scheduler.getBudgetLedger().budgetOf(2), 1.0e-9);
        finish(firstJob, Cloudlet.SUCCESS, 0.0, firstFinish);
        scheduler.onJobReturned(firstJob);
        slow.setState(WorkflowSimTags.VM_STATUS_IDLE);

        Job secondJob = job(second);
        scheduler.getScheduledList().clear();
        scheduler.setCloudletList(Collections.singletonList(secondJob));
        scheduler.run();
        assertTrue(scheduler.getScheduledList().contains(secondJob));
        return secondJob.getVmId();
    }

    @Test
    void successfulOutputIsPublishedAndLaterReadAsCached() throws Exception {
        assertEquals(1, consumerVmAfterProducer(Cloudlet.SUCCESS));
    }

    @Test
    void unsuccessfulOutputIsNotPublishedAsAReadableInput() throws Exception {
        assertEquals(0, consumerVmAfterProducer(Cloudlet.FAILED));
    }

    private static int consumerVmAfterProducer(int status) throws Exception {
        Task producer = producerTask(1);
        Task consumer = consumerTask(2, 100L);
        EbpsmSchedulingAlgorithm scheduler = scheduler(publicationConfig(20.0), producer, consumer);
        CondorVM vm0 = vm(0);
        CondorVM vm1 = vm(1);
        vm0.setState(WorkflowSimTags.VM_STATUS_BUSY);
        scheduler.setVmList(Arrays.asList(vm0, vm1));
        Job first = job(producer);
        scheduler.setCloudletList(Collections.singletonList(first));
        scheduler.run();
        assertEquals(1, first.getVmId());
        finish(first, status, 0.0, 2.0);
        scheduler.onJobReturned(first);
        vm0.setState(WorkflowSimTags.VM_STATUS_IDLE);
        vm1.setState(WorkflowSimTags.VM_STATUS_IDLE);
        Job second = job(consumer);
        scheduler.setCloudletList(Collections.singletonList(second));
        scheduler.getScheduledList().clear();
        scheduler.run();
        assertTrue(scheduler.getScheduledList().contains(second));
        return second.getVmId();
    }

    @Test
    void successfulTaskInputsAreRetainedInTheVmCache() throws Exception {
        Task reader = new Task(1, 100L);
        reader.getFileList().add(file("shared.bin", FileType.INPUT));
        Task again = new Task(2, 100L);
        again.getFileList().add(file("shared.bin", FileType.INPUT));
        EbpsmSchedulingAlgorithm scheduler = scheduler(publicationConfig(20.0), reader, again);
        CondorVM vm0 = vm(0);
        CondorVM vm1 = vm(1);
        vm0.setState(WorkflowSimTags.VM_STATUS_BUSY);
        scheduler.setVmList(Arrays.asList(vm0, vm1));
        Job first = job(reader);
        scheduler.setCloudletList(Collections.singletonList(first));
        scheduler.run();
        finish(first, Cloudlet.SUCCESS, 0.0, 2.0);
        scheduler.onJobReturned(first);
        vm0.setState(WorkflowSimTags.VM_STATUS_IDLE);
        vm1.setState(WorkflowSimTags.VM_STATUS_IDLE);

        Job second = job(again);
        scheduler.setCloudletList(Collections.singletonList(second));
        scheduler.getScheduledList().clear();
        scheduler.run();

        assertEquals(1, second.getVmId());
    }

    @Test
    void aLaterDispatchInTheSameRoundPreventsAPrematureFailure() throws Exception {
        DebtFixture fixture = debtFixture();
        Job blocked = job(fixture.consumer);
        Job producing = job(fixture.producer);
        fixture.scheduler.setCloudletList(Arrays.asList(blocked, producing));

        fixture.scheduler.run();

        assertEquals(Collections.singletonList(producing), fixture.scheduler.getScheduledList());
        assertEquals(0.0, fixture.scheduler.getBudgetLedger().budgetOf(2), 1.0e-9);
        assertEquals(3.0, fixture.scheduler.getBudgetLedger().budgetOf(3), 1.0e-9);
    }

    @Test
    void debtThatLeavesATaskUnaffordableOnAnIdlePoolFailsExplicitly() throws Exception {
        DebtFixture fixture = debtFixture();
        fixture.scheduler.setCloudletList(Collections.singletonList(job(fixture.consumer)));

        assertThrows(IllegalStateException.class, fixture.scheduler::run);
    }

    private static DebtFixture debtFixture() throws Exception {
        Task first = new Task(1, 100L);
        Task consumer = consumerTask(2, 200L);
        Task producer = producerTask(3);
        EbpsmSchedulingAlgorithm scheduler = scheduler(publicationConfig(8.0), first, consumer, producer);
        CondorVM vm0 = vm(0);
        scheduler.setVmList(Arrays.asList(vm0, vm(1)));
        Job firstJob = job(first);
        scheduler.setCloudletList(Collections.singletonList(firstJob));
        scheduler.run();
        assertEquals(1.0, scheduler.getBudgetLedger().budgetOf(1), 1.0e-9);
        assertEquals(4.0, scheduler.getBudgetLedger().budgetOf(2), 1.0e-9);
        assertEquals(3.0, scheduler.getBudgetLedger().budgetOf(3), 1.0e-9);
        finish(firstJob, Cloudlet.SUCCESS, 0.0, 5.0);
        scheduler.onJobReturned(firstJob);
        vm0.setState(WorkflowSimTags.VM_STATUS_IDLE);
        scheduler.getScheduledList().clear();
        DebtFixture fixture = new DebtFixture();
        fixture.scheduler = scheduler;
        fixture.consumer = consumer;
        fixture.producer = producer;
        return fixture;
    }

    private static final class DebtFixture {
        private EbpsmSchedulingAlgorithm scheduler;
        private Task consumer;
        private Task producer;
    }

    @Test
    void failsFastWhenNoDeclaredVmIsCompatible() {
        Task producer = producerTask(1);
        Job job = job(producer);
        job.setNumberOfPes(2);
        EbpsmSchedulingAlgorithm scheduler = scheduler(publicationConfig(20.0), producer);
        scheduler.setCloudletList(Collections.singletonList(job));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        assertThrows(IllegalArgumentException.class, scheduler::run);
    }

    @Test
    void failsFastWithoutTheWorkflowContext() {
        Task task = cachedTask();
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config(10.0, 1.0, 1.0));
        scheduler.setCloudletList(Collections.singletonList(job(task)));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        assertThrows(IllegalStateException.class, scheduler::run);
    }

    private static EbpsmSchedulingAlgorithm scheduler(EbpsmConfig config, Task... tasks) {
        EbpsmSchedulingAlgorithm scheduler = new EbpsmSchedulingAlgorithm(config);
        Map<Integer, Integer> indices = new TreeMap<Integer, Integer>();
        List<Task> list = new ArrayList<Task>();
        for (Task task : tasks) {
            indices.put(task.getCloudletId(), 0);
            list.add(task);
        }
        scheduler.bindWorkflows(list, indices);
        return scheduler;
    }

    private static Task cachedTask() {
        Task task = new Task(1, 100L);
        task.getFileList().add(file("input.bin", FileType.INPUT));
        task.getFileList().add(file("output.bin", FileType.OUTPUT));
        return task;
    }

    private static Task producerTask(int id) {
        Task producer = new Task(id, 100L);
        producer.getFileList().add(file("new.bin", FileType.OUTPUT));
        return producer;
    }

    private static Task consumerTask(int id, long length) {
        Task consumer = new Task(id, length);
        consumer.getFileList().add(file("new.bin", FileType.INPUT));
        return consumer;
    }

    private static Job job(Task task) {
        Job job = new Job(task.getCloudletId(), task.getCloudletLength());
        job.getTaskList().add(task);
        return job;
    }

    private static void finish(Job job, int status, double start, double finish) throws Exception {
        job.setExecStartTime(start);
        for (Task task : job.getTaskList()) {
            task.setTaskFinishTime(finish);
            task.setCloudletStatus(status);
        }
        job.setCloudletStatus(status);
    }

    private static EbpsmConfig publicationConfig(double budget) {
        Map<Integer, String> images = new HashMap<Integer, String>();
        for (int task = 1; task <= 3; task++) {
            images.put(task, "img");
        }
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        Map<Integer, Double> prices = new HashMap<Integer, Double>();
        for (int vm : Arrays.asList(0, 1)) {
            inputs.put(vm, Collections.<String>emptySet());
            containers.put(vm, Collections.singleton("img"));
            prices.put(vm, 1.0);
        }
        return EbpsmConfig.forWorkflowBudgets(
                Collections.singletonMap(0, budget), inputs, containers, images, prices, 100.0, 100.0, 0.0);
    }

    private static EbpsmConfig config(double budget, double firstPrice, double secondPrice) {
        Map<Integer, Set<String>> inputs = new HashMap<Integer, Set<String>>();
        inputs.put(0, Collections.<String>emptySet());
        inputs.put(1, Collections.singleton("input.bin"));
        Map<Integer, Set<String>> containers = new HashMap<Integer, Set<String>>();
        containers.put(0, Collections.singleton("img"));
        containers.put(1, Collections.<String>emptySet());
        Map<Integer, Double> prices = new HashMap<Integer, Double>();
        prices.put(0, firstPrice);
        prices.put(1, secondPrice);
        return EbpsmConfig.forWorkflowBudgets(
                Collections.singletonMap(0, budget),
                inputs,
                containers,
                Collections.singletonMap(1, "img"),
                prices,
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
        return vm(id, pes, 100.0);
    }

    private static CondorVM vm(int id, int pes, double mips) {
        return new CondorVM(id, 0, mips, pes, 512, 100L, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
