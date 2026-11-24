package org.workflowsim.scheduling;

import java.util.List;
import java.util.Map;

import org.workflowsim.Task;

/**
 * Optional injection point for online policies that need the parsed workflow structure.
 *
 * <p>FGCS-R5 (2026-11-24): root cause — EBPSM budgets are defined per workflow (Hilman, Rodriguez and Buyya,
 * arXiv:1903.01113, Sec. 3.2), but an online policy only sees ready Jobs, so it could neither run Algorithm 1 over
 * a whole workflow nor attribute a returned Task to its workflow. {@code WorkflowPlanner} hands the parsed Task
 * list and the parser's Task-to-workflow index map to every {@code WorkflowScheduler}, which forwards them to its
 * policy, instead of publishing them through a global static.
 */
public interface WorkflowContextAware {

    /**
     * Receives the parsed workflow structure before the first scheduling round.
     *
     * @param tasks every parsed Task with its parent links
     * @param taskWorkflowIndices workflow index of every Task ID (input order of the configured workflow paths)
     */
    void bindWorkflows(List<Task> tasks, Map<Integer, Integer> taskWorkflowIndices);
}
