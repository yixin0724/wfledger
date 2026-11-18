package org.workflowsim.rl;

import java.util.HashSet;
import java.util.Set;

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
