package org.workflowsim.rl;

public interface RlPolicy {

    int NO_ASSIGNMENT = -1;

    int[] selectVms(RlObservation observation);
}
