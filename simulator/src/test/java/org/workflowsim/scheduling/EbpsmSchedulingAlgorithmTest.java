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

/**
 * Scheduler-level checks of EBPSM (Hilman, Rodriguez and Buyya, arXiv:1903.01113, Algorithms 1–3).
 *
 * <p>FGCS-R5 (2026-11-24): budgets are per workflow and every sub-budget below is derived by hand from Algorithm 1
 * with Eq. 5 costs {@code ceil(prov_c + PT) * price}. Common fixture terms: 100-byte files, global storage rates
 * 100 B/s and VM bandwidth 100 decimal MB/s, so one read or write costs {@code 1 + 1e-6} s; 100 MI at 100 MIPS is
 * 1 s. VMs with equal (MIPS, price, PEs, bandwidth) form one VM type.
 */
class EbpsmSchedulingAlgorithmTest {

    /**
     * Single type (both VMs 100 MIPS, price 1), {@code prov_c = 5}. Task: input + output + 1 s compute, so
     * {@code PT = 3.000002}, Eq. 5 cost {@code ceil(8.000002) = 9}; budget 10 gives sub-budget 9. VM 1 caches the
     * input but lacks the container: {@code 1 + 1.000001 + 5 = 7.000001} s, cost 8 <= 9, and the input tier wins.
     */
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

    /**
     * The cheapest-type Eq. 5 cost of the workflow is 9 (derived above); budget 8 cannot be met by any allocation,
     * and the paper rejects such jobs (Sec. 5), so the run fails before any dispatch.
     */
    @Test
    void rejectsAWorkflowBudgetBelowItsCheapestTypeCost() {
        Task task = cachedTask();
        EbpsmSchedulingAlgorithm scheduler = scheduler(config(8.0, 1.0, 1.0), task);
        scheduler.setCloudletList(Collections.singletonList(job(task)));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, scheduler::run);
        assertTrue(failure.getMessage().contains("budget"), failure.getMessage());
    }

    /**
     * VM 0 (price 1) and VM 1 (price 3) are two types with equal speed. Eq. 5: type 0 costs 9, type 1 costs 27, so
     * budget 10 gives sub-budget 9 (no faster type to upgrade to). VM 0 is busy; idle VM 1 costs
     * {@code ceil(7.000001) * 3 = 24 > 9}. Algorithm 2 schedules only on a suitable VM, and a busy VM will return,
     * so the task is deferred rather than failed.
     */
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

    /**
     * FGCS-R3 (2026-11-24, A2): the prediction uses the effective execution length. The cost matrix gives 1 s on
     * VM 0 (price 1) and 10 s on VM 1 (price 2); the task has no files. Eq. 5 with {@code prov_c = 5}: type 0
     * {@code ceil(6) = 6}, type 1 {@code ceil(15) * 2 = 30}; budget 7 gives sub-budget 6. VM 0 has the container:
     * 1 s, cost 1. The raw 1000 MI / 100 MIPS = 10 s would make the workflow unaffordable.
     */
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

    /**
     * A 2-PE task fits only the 2-PE type (VM 1): {@code PT = 2.000001} (read + compute), Eq. 5
     * {@code ceil(7.000001) = 8}. VM 0 has the container but is PE-incompatible.
     */
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

    /**
     * No VM caches anything; {@code PT = 3.000002} with 100 decimal MB/s bandwidth, plus {@code prov_c = 5}, costs
     * {@code ceil(8.000002) = 9}, so budget 9 is exactly feasible. Reading the bandwidth as bits per second would
     * add 8 s per transfer and make it infeasible.
     */
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

    /**
     * Test (b) of FGCS-R5. VM 0: 100 MIPS, price 1; VM 1: 200 MIPS, price 3; {@code prov_c = 0}, no files. Chain
     * t1 -> t2, each 400 MI: 4 s slow (cost 4) or 2 s fast (cost 6). Budget 9: cheapest 4 + 4, residual 1; no
     * upgrade fits (+2), so t1 and t2 hold 4 each and {@code sb = 1}. Under that static budget t2 can only afford
     * VM 0. t1 runs on VM 0 and finishes after 1 s (cost 1). Algorithm 3: pool {@code 4 + 1 = 5} covers 1, so
     * {@code beta_u = 4 + 4 = 8}; Algorithm 1 over {t2}: 4, then the fast upgrade +2 fits (residual 2), so t2's
     * sub-budget becomes 6 and t2 dispatches to VM 1 (2 s, cost 6).
     */
    @Test
    void spareBudgetFromACheapCompletionUpgradesTheSuccessorToTheFasterVm() throws Exception {
        assertEquals(1, runChainAndReturnSecondVm(1.0));
    }

    /**
     * Counterpart of the previous test: t1 costs exactly its sub-budget 4, so pool 5 leaves {@code sb = 1},
     * {@code beta_u = 5}, the +2 upgrade does not fit, and t2 stays on VM 0.
     */
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

    /**
     * FGCS-R3 (2026-11-24, A1): outputs are published on successful completion. Producer t1 (output new.bin) and
     * consumer t2 (input new.bin) each predict 2.000001 s, cost 3; budget 20 covers both. The producer runs on
     * VM 1 (VM 0 busy). Afterwards both VMs are idle and only VM 1 holds new.bin, so the input tier selects VM 1.
     */
    @Test
    void successfulOutputIsPublishedAndLaterReadAsCached() throws Exception {
        assertEquals(1, consumerVmAfterProducer(Cloudlet.SUCCESS));
    }

    /**
     * FGCS-R3 (2026-11-24, A1): a failed attempt publishes nothing. With no VM holding new.bin, both VMs fall into
     * the container tier, both predict 2.000001 s, and the lowest ID wins: VM 0.
     */
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

    /**
     * FGCS-R3 (2026-11-24, A1): a successful reader of shared.bin on VM 1 leaves shared.bin in VM 1's local storage
     * (paper Sec. 3.2 keeps {@code d_in}), so a later reader selects VM 1 through the input tier.
     */
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

    /**
     * Test (c) of FGCS-R5 plus the FGCS-R3 whole-round stall rule. Single type, {@code prov_c = 0}. Tasks: t1 100 MI
     * without files ({@code PT = 1}, cost 1), consumer t2 200 MI reading new.bin ({@code PT = 3.000001}, cost 4),
     * producer t3 100 MI writing new.bin ({@code PT = 2.000001}, cost 3). All are level 0; EFTs 1, 3.000001,
     * 2.000001 give S = t1, t3, t2. Budget 8 = 1 + 3 + 4 gives exactly those sub-budgets and {@code sb = 0}. t1
     * returns after 5 s: cost 5 exceeds pool {@code 1 + 0}, debt 4, {@code beta_u = 7 - 4 = 3}. Algorithm 1 over
     * {t3, t2}: t3 takes 3, beta reaches 0, the while loop stops, and t2 keeps budget 0 — it fits no VM. In the next
     * round the producer is dispatched (cost 3 <= 3) after the consumer was found unaffordable, so the consumer is
     * deferred instead of failing.
     */
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

    /**
     * Test (c): with the debt derived above and only the consumer ready, the whole pool is idle after the round, no
     * running attempt can change a cache or budget, and the fixed pool cannot provision the paper's fallback VM, so
     * the run fails explicitly.
     */
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

    /** FGCS-R3 (2026-11-24, A2): a Job no declared VM can host fails instead of waiting forever. */
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

    /** FGCS-R5 (2026-11-24): without the injected workflow context no budget can be distributed. */
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
