package org.workflowsim.scheduling;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.rl.RlObservation;
import org.workflowsim.rl.RlPolicy;
import org.workflowsim.rl.RlPolicyRegistry;

/**
 * Policy-driven scheduler of the R4 RL track: each ready-batch update asks the registered
 * {@link RlPolicy} for Job-to-VM actions and executes them.
 *
 * <p>The execution contract matches the ready-batch schedulers -- only currently ready Jobs
 * are considered, and each VM receives at most one Job per update. Policy actions are
 * interpreted by the Job arrival order of the observation and by ascending VM ID; when an
 * action points at a busy, incompatible, or already-used VM in this round, that Job is
 * skipped (it stays ready and is decided again in the next update). A policy is obliged to
 * eventually return an executable action; a stall caused by persistent skipping is
 * intercepted explicitly by the platform watchdog.</p>
 *
 * <p>Policy instances arrive through {@link RlPolicyRegistry} (the scheduler is constructed by
 * enum and has no constructor channel), and decision records are written back to the same
 * registry for episode collection. With no registered policy it fails explicitly with
 * {@link SimulationConfigurationException} -- RL_POLICY must run through
 * {@code RlEnvironment}.</p>
 */
public class RlPolicySchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() throws Exception {
        validateCloudletCompatibility();
        List<? extends Cloudlet> readyJobs = getCloudletList();
        if (readyJobs.isEmpty()) {
            return;
        }
        List<CondorVM> vms = sortedVmsById();
        RlPolicy policy = RlPolicyRegistry.current();
        RlObservation observation = RlObservation.of(readyJobs, vms);
        RlPolicyRegistry.recordStep();
        int[] actions = policy.selectVms(observation);
        if (actions == null || actions.length != readyJobs.size()) {
            throw new IllegalStateException("RL policy must return exactly "
                    + readyJobs.size() + " action(s), got "
                    + (actions == null ? "null" : actions.length));
        }
        double decisionTime = CloudSim.clock();
        Set<Integer> usedVmIndices = new HashSet<Integer>();
        for (int j = 0; j < readyJobs.size(); j++) {
            int vmIndex = actions[j];
            if (vmIndex == RlPolicy.NO_ASSIGNMENT) {
                continue;
            }
            if (vmIndex < 0 || vmIndex >= vms.size()) {
                throw new IllegalStateException("RL policy returned out-of-range VM index " + vmIndex
                        + " (valid range [0, " + vms.size() + "))");
            }
            if (usedVmIndices.contains(vmIndex)) {
                continue;
            }
            Cloudlet cloudlet = readyJobs.get(j);
            CondorVM vm = vms.get(vmIndex);
            if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(cloudlet, vm)) {
                continue;
            }
            vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(vm.getId());
            getScheduledList().add(cloudlet);
            usedVmIndices.add(vmIndex);
            RlPolicyRegistry.recordDecision(decisionTime, cloudlet.getCloudletId(), vm.getId());
        }
    }
}
