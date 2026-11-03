package org.workflowsim.rl;

import java.util.HashSet;
import java.util.Set;

/**
 * Baseline policy: each ready Job selects the idle compatible VM with the earliest expected
 * completion (ECT = length / mips).
 *
 * <p>This is the reference policy of the RL track, used to verify the closed environment loop and to
 * provide a comparable lower-bound baseline:</p>
 * <ul>
 *   <li>Decisions follow the Job arrival order of the observation; for the same Job, VMs with equal
 *       ECT go to the smaller VM ID;</li>
 *   <li>Within one update each VM receives at most one Job (consistent with the ready-batch scheduler
 *       contract): a VM already reserved in this round counts as unavailable, and later Jobs fall
 *       through to the next-best VM;</li>
 *   <li>A Job with no idle compatible VM returns {@link RlPolicy#NO_ASSIGNMENT}.</li>
 * </ul>
 */
public final class EarliestFinishGreedyPolicy implements RlPolicy {

    @Override
    public int[] selectVms(RlObservation observation) {
        int jobCount = observation.getReadyJobs().size();
        int[] actions = new int[jobCount];
        Set<Integer> reservedVmIndices = new HashSet<Integer>();
        for (int j = 0; j < jobCount; j++) {
            RlObservation.JobView job = observation.getReadyJobs().get(j);
            double bestEct = Double.MAX_VALUE;
            int bestVmIndex = NO_ASSIGNMENT;
            for (int v = 0; v < observation.getVms().size(); v++) {
                RlObservation.VmView vm = observation.getVms().get(v);
                if (!vm.isIdle() || reservedVmIndices.contains(v) || vm.getPes() < job.getPes()) {
                    continue;
                }
                double ect = (double) job.getTotalLength() / vm.getMips();
                if (ect < bestEct) {
                    bestEct = ect;
                    bestVmIndex = v;
                }
            }
            actions[j] = bestVmIndex;
            if (bestVmIndex != NO_ASSIGNMENT) {
                reservedVmIndices.add(bestVmIndex);
            }
        }
        return actions;
    }
}
