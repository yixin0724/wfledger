package org.workflowsim.scheduling;

import org.workflowsim.Job;

/** Optional callback for online scheduling policies that keep state derived from completed attempts. */
public interface JobReturnListener {

    void onJobReturned(Job job);
}
