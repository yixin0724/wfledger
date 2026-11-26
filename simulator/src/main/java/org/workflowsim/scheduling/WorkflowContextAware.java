package org.workflowsim.scheduling;

import java.util.List;
import java.util.Map;

import org.workflowsim.Task;

/** Optional injection point for online policies that need the parsed workflow structure. */
public interface WorkflowContextAware {

    void bindWorkflows(List<Task> tasks, Map<Integer, Integer> taskWorkflowIndices);
}
