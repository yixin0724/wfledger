package org.workflowsim.scheduling;

import org.workflowsim.Job;

/** Supplies the input-read seconds that the runtime will add to a ready Job's execution envelope. */
public interface InputReadEstimator {

    double inputReadSeconds(Job job);
}
