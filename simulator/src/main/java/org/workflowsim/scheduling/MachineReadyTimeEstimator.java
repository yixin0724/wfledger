package org.workflowsim.scheduling;

import org.workflowsim.CondorVM;

/** Supplies the machine ready time {@code r_v} of a created VM at the current scheduling round. */
public interface MachineReadyTimeEstimator {

    double readySeconds(CondorVM vm);
}
