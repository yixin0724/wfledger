package org.workflowsim.scheduling;

public interface DeferralAwareSchedulingAlgorithm {

    double getNextWakeSeconds();

    int getDeferredJobCount();
}
