package org.workflowsim.rl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.exception.SimulationConfigurationException;

/**
 * Unit contract of the R4 RL track: observation snapshot structure, greedy baseline policy
 * behavior, and registry lifecycle. All assertions rest on hand-built synthetic state (real
 * Job/CondorVM instances, no simulation run required).
 */
class RlPolicyContractTest {

    @AfterEach
    void resetRegistry() {
        RlPolicyRegistry.reset();
    }

    @Test
    void observationPreservesJobOrderAndSortsVmsById() {
        // VMs arrive in shuffled ID order; the observation must sort them by ascending ID;
        // Jobs keep arrival order.
        List<CondorVM> vms = Arrays.asList(vm(2, 1.0, true), vm(0, 2.0, false), vm(1, 4.0, true));
        List<Job> jobs = Arrays.asList(job(7, 100L), job(3, 50L));
        RlObservation observation = RlObservation.of(jobs, vms);
        assertEquals(7, observation.getReadyJobs().get(0).getJobId(), "Jobs keep arrival order");
        assertEquals(3, observation.getReadyJobs().get(1).getJobId());
        assertEquals(0, observation.getVms().get(0).getVmId(), "VMs are ordered by ascending ID");
        assertEquals(1, observation.getVms().get(1).getVmId());
        assertEquals(2, observation.getVms().get(2).getVmId());
        assertEquals(4.0, observation.getVms().get(1).getMips(), 0.0);
        assertFalse(observation.getVms().get(0).isIdle(), "VM0 is busy");
        assertTrue(observation.getVms().get(2).isIdle(), "VM2 is idle");
        assertEquals(100L, observation.getReadyJobs().get(0).getTotalLength());
    }

    @Test
    void greedyPolicyPicksEarliestFinishIdleCompatibleVm() {
        // One Job (100 MI, 1 PE): VM0 mips=2 (ECT 50), VM1 mips=4 (ECT 25),
        // VM2 mips=1 (ECT 100) -> VM1 must be chosen (observation index 1).
        RlObservation observation = RlObservation.of(
                Arrays.asList(job(1, 100L)), Arrays.asList(vm(0, 2.0, true), vm(1, 4.0, true), vm(2, 1.0, true)));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(1, actions.length);
        assertEquals(1, actions[0], "VM1 with the smallest ECT must be selected");
    }

    @Test
    void greedyPolicySkipsBusyAndIncompatibleVms() {
        // VM0 is busy (fastest), VM1 is idle but has too few PEs, VM2 is idle and
        // compatible -> only VM2 is eligible.
        Job job = job(1, 100L);
        job.setNumberOfPes(2);
        CondorVM busyFast = vm(0, 4.0, false);
        CondorVM smallPes = vmWithPes(1, 8.0, true, 1);
        CondorVM compatible = vmWithPes(2, 1.0, true, 2);
        RlObservation observation = RlObservation.of(Arrays.asList(job), Arrays.asList(busyFast, smallPes, compatible));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(2, actions[0], "busy and incompatible VMs must be skipped");
    }

    @Test
    void greedyPolicyReservesAtMostOneJobPerVmPerBatch() {
        // Two Jobs and a single idle VM: the first Job occupies it, the second Job
        // gets no assignment.
        RlObservation observation = RlObservation.of(
                Arrays.asList(job(1, 50L), job(2, 80L)), Arrays.asList(vm(0, 1.0, true), vm(1, 1.0, false)));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(0, actions[0], "the first Job occupies the only idle VM");
        assertEquals(RlPolicy.NO_ASSIGNMENT, actions[1], "a Job with no available VM gets no assignment");
    }

    @Test
    void greedyPolicyTiesChooseLowerVmId() {
        // Two idle VMs of equal speed: an ECT tie selects the lower VM ID
        // (observation index 0).
        RlObservation observation =
                RlObservation.of(Arrays.asList(job(1, 100L)), Arrays.asList(vm(0, 2.0, true), vm(1, 2.0, true)));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(0, actions[0], "an ECT tie must select the lower VM ID");
    }

    @Test
    void registryLifecycleCarriesPolicyAndTrace() {
        // Before registration, current() fails explicitly and points at RlEnvironment.
        SimulationConfigurationException missing =
                assertThrows(SimulationConfigurationException.class, RlPolicyRegistry::current);
        assertTrue(missing.getMessage().contains("RlEnvironment"), missing.getMessage());
        // After registration, current returns the same instance; decisions and steps
        // are counted independently; reset clears both.
        RlPolicy policy = observation -> new int[observation.getReadyJobs().size()];
        RlPolicyRegistry.init(policy);
        assertEquals(policy, RlPolicyRegistry.current());
        RlPolicyRegistry.recordStep();
        RlPolicyRegistry.recordDecision(1.5, 7, 2);
        assertEquals(1, RlPolicyRegistry.stepCount(), "steps count policy queries only");
        assertEquals(1, RlPolicyRegistry.decisions().size());
        assertEquals(7, RlPolicyRegistry.decisions().get(0).getJobId());
        assertEquals(2, RlPolicyRegistry.decisions().get(0).getVmId());
        assertEquals(1.5, RlPolicyRegistry.decisions().get(0).getTime(), 0.0);
        assertThrows(
                UnsupportedOperationException.class,
                () -> RlPolicyRegistry.decisions().add(null),
                "the trace is immutable to callers");
        RlPolicyRegistry.reset();
        assertThrows(SimulationConfigurationException.class, RlPolicyRegistry::current);
        assertEquals(0, RlPolicyRegistry.stepCount());
        assertTrue(RlPolicyRegistry.decisions().isEmpty());
        // init(null) is rejected.
        assertThrows(SimulationConfigurationException.class, () -> RlPolicyRegistry.init(null));
    }

    private static Job job(int id, long length) {
        return new Job(id, length);
    }

    private static CondorVM vm(int id, double mips, boolean idle) {
        return vmWithPes(id, mips, idle, 1);
    }

    private static CondorVM vmWithPes(int id, double mips, boolean idle, int pes) {
        CondorVM vm = new CondorVM(id, 0, mips, pes, 512, 1L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
        vm.setState(idle ? WorkflowSimTags.VM_STATUS_IDLE : WorkflowSimTags.VM_STATUS_BUSY);
        return vm;
    }
}
