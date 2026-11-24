package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.utils.ReplicaCatalog;

class SupportedOnlineSchedulingCompatibilityTest {

    @AfterEach
    void resetReplicaCatalog() {
        ReplicaCatalog.reset();
    }

    @Test
    void everySupportedOnlineSchedulerExcludesIncompatibleVmsAndUsesVmIdForEqualChoices() throws Exception {
        for (SchedulerFactory factory : supportedSchedulers()) {
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            Job job = job(10, 1);
            BaseSchedulingAlgorithm scheduler = factory.create();
            scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
            scheduler.setVmList(Arrays.asList(vm(9, 1000.0, 1), vm(2, 1000.0, 1)));

            scheduler.run();

            assertEquals(1, scheduler.getScheduledList().size(), factory.name);
            assertEquals(2, job.getVmId(), factory.name);
            ReplicaCatalog.reset();
        }
    }

    @Test
    void everySupportedOnlineSchedulerMapsMultiPeJobsOnlyToCompatibleVms() throws Exception {
        for (SchedulerFactory factory : supportedSchedulers()) {
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            Job job = job(10, 2);
            BaseSchedulingAlgorithm scheduler = factory.create();
            scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
            scheduler.setVmList(Arrays.asList(vm(2, 1000.0, 1), vm(9, 500.0, 2)));

            scheduler.run();

            assertEquals(1, scheduler.getScheduledList().size(), factory.name);
            assertEquals(9, job.getVmId(), factory.name);
            ReplicaCatalog.reset();
        }
    }

    @Test
    void everySupportedOnlineSchedulerFailsFastWhenNoVmCanRunTheReadyJob() {
        for (SchedulerFactory factory : supportedSchedulers()) {
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            Job job = job(10, 2);
            BaseSchedulingAlgorithm scheduler = factory.create();
            scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
            scheduler.setVmList(Arrays.asList(vm(2, 1000.0, 1)));

            IllegalArgumentException exception =
                    assertThrows(IllegalArgumentException.class, scheduler::run, factory.name);

            assertEquals(
                    "Ready job 10 requires 2 processing element(s), but no declared VM is compatible",
                    exception.getMessage(),
                    factory.name);
            ReplicaCatalog.reset();
        }
    }

    /**
     * FGCS-R3 (2026-11-24, B2): CONTRACTS head-of-line rule. The 2-PE head Job can use only the
     * busy VM 9 while the 1-PE Job behind it could use idle VM 2. Arrival-order policies are strict
     * FIFO and dispatch nothing; selection-based policies are work-conserving and dispatch Job 11.
     */
    @Test
    void headOfLineBlockingFollowsTheDeclaredPolicyFamily() throws Exception {
        for (SchedulerFactory factory : supportedSchedulers()) {
            if (factory.name.equals("DATA")) {
                continue;
            }
            Job head = job(10, 2);
            Job behind = job(11, 1);
            CondorVM busy = vm(9, 1000.0, 2);
            busy.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
            BaseSchedulingAlgorithm scheduler = factory.create();
            scheduler.setCloudletList(Arrays.<Cloudlet>asList(head, behind));
            scheduler.setVmList(Arrays.asList(vm(2, 1000.0, 1), busy));

            scheduler.run();

            boolean workConserving =
                    factory.name.equals("READY_BATCH_MINMIN") || factory.name.equals("READY_BATCH_MAXMIN");
            List<Cloudlet> expected =
                    workConserving ? Arrays.<Cloudlet>asList(behind) : java.util.Collections.<Cloudlet>emptyList();
            assertEquals(expected, scheduler.getScheduledList(), factory.name);
        }
    }

    private static List<SchedulerFactory> supportedSchedulers() {
        return Arrays.asList(
                new SchedulerFactory("FCFS", FCFSSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_MINMIN", ReadyBatchMinMinSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_MAXMIN", ReadyBatchMaxMinSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_MCT", ReadyBatchMCTSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_ROUNDROBIN", ReadyBatchRoundRobinSchedulingAlgorithm::new),
                new SchedulerFactory("DATA", DataAwareSchedulingAlgorithm::new));
    }

    private static Job job(int id, int pes) {
        Job job = new Job(id, 1000);
        job.setNumberOfPes(pes);
        return job;
    }

    private static CondorVM vm(int id, double mips, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, 1000, 10_000, "Xen", new CloudletSchedulerTimeShared());
    }

    private static final class SchedulerFactory {
        private final String name;
        private final Supplier<BaseSchedulingAlgorithm> supplier;

        private SchedulerFactory(String name, Supplier<BaseSchedulingAlgorithm> supplier) {
            this.name = name;
            this.supplier = supplier;
        }

        private BaseSchedulingAlgorithm create() {
            return supplier.get();
        }
    }
}
