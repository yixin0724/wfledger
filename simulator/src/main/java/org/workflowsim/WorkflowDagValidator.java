package org.workflowsim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates task directed-acyclic-graph (DAG) invariants and computes task depths in a
 * deterministic order.
 *
 * <p>A root task has depth 1; any other task has the longest parent path length plus 1. The
 * computation is both part of input validation and a source of stable level information for
 * dependency-aware clustering and scheduling policies.</p>
 */
public final class WorkflowDagValidator {

    private WorkflowDagValidator() {}

    /**
     * Validates task membership, parent-child edge symmetry, task-ID uniqueness, and
     * acyclicity, and writes the task depths.
     *
     * <p>A root task has depth 1; any other task has the maximum depth over all parents
     * plus 1. Roots and children are processed in task-ID order so that the same input
     * yields identical depths across JVM runs.</p>
     *
     * @param tasks task list from one workflow input
     * @throws WorkflowValidationException when the task list does not form a valid DAG
     */
    public static void validateAndAssignDepths(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new WorkflowValidationException("Workflow must contain at least one task");
        }

        Set<Task> taskSet = indexAndValidateTasks(tasks);

        Map<Task, Integer> remainingParents = new HashMap<>();
        // Per-validation lazy indexes only: Task lists remain authoritative and keep their order.
        Map<Task, Set<Task>> parentMembership = new HashMap<>();
        Map<Task, Set<Task>> childMembership = new HashMap<>();
        for (Task task : tasks) {
            validateNeighbours(task, task.getParentList(), taskSet, true, childMembership);
            validateNeighbours(task, task.getChildList(), taskSet, false, parentMembership);
            remainingParents.put(task, task.getParentList().size());
        }

        Deque<Task> ready = new ArrayDeque<>();
        List<Task> roots = new ArrayList<>();
        for (Task task : tasks) {
            if (remainingParents.get(task) == 0) {
                roots.add(task);
            }
        }
        roots.sort(Comparator.comparingInt(Task::getCloudletId));
        for (Task root : roots) {
            root.setDepth(1);
            ready.addLast(root);
        }

        int visited = 0;
        while (!ready.isEmpty()) {
            Task parent = ready.removeFirst();
            visited++;
            List<Task> children = new ArrayList<>(parent.getChildList());
            children.sort(Comparator.comparingInt(Task::getCloudletId));
            for (Task child : children) {
                // Depth is defined by the longest parent path, not by whichever path reaches the child first.
                child.setDepth(Math.max(child.getDepth(), parent.getDepth() + 1));
                int remaining = remainingParents.get(child) - 1;
                remainingParents.put(child, remaining);
                if (remaining == 0) {
                    ready.addLast(child);
                }
            }
        }

        if (visited != tasks.size()) {
            throw new WorkflowValidationException("Workflow graph contains a cycle");
        }
    }

    /**
     * 2026-10-26 complexity extraction from validateAndAssignDepths; statements verbatim; order unchanged.
     * Scope: per-task validation of non-nullness, length, ID uniqueness, and adjacency-list
     * presence, plus indexing, and resets every depth to zero.
     */
    private static Set<Task> indexAndValidateTasks(List<Task> tasks) {
        Map<Integer, Task> taskById = new HashMap<>();
        Set<Task> taskSet = new HashSet<>();
        for (Task task : tasks) {
            if (task == null) {
                throw new WorkflowValidationException("Workflow cannot contain a null task");
            }
            if (task.getCloudletLength() <= 0L) {
                throw new WorkflowValidationException(
                        "Task " + task.getCloudletId() + " has non-positive Cloudlet length");
            }
            if (taskById.put(task.getCloudletId(), task) != null) {
                throw new WorkflowValidationException("Duplicate task ID " + task.getCloudletId());
            }
            if (task.getParentList() == null || task.getChildList() == null) {
                throw new WorkflowValidationException("Task " + task.getCloudletId() + " has a null adjacency list");
            }
            taskSet.add(task);
            task.setDepth(0);
        }
        return taskSet;
    }

    private static void validateNeighbours(
            Task task,
            List<Task> neighbours,
            Set<Task> taskSet,
            boolean parents,
            Map<Task, Set<Task>> reverseMembership) {
        Set<Task> unique = new HashSet<>();
        for (Task neighbour : neighbours) {
            String relation = parents ? "parent" : "child";
            if (neighbour == null || !taskSet.contains(neighbour)) {
                throw new WorkflowValidationException(
                        "Task " + task.getCloudletId() + " references a task outside this workflow as " + relation);
            }
            if (neighbour == task) {
                throw new WorkflowValidationException("Task " + task.getCloudletId() + " has a self dependency");
            }
            if (!unique.add(neighbour)) {
                throw new WorkflowValidationException("Task " + task.getCloudletId() + " has a duplicate " + relation
                        + " " + neighbour.getCloudletId());
            }
            Set<Task> reverse = reverseMembership.get(neighbour);
            if (reverse == null) {
                reverse = new HashSet<>(parents ? neighbour.getChildList() : neighbour.getParentList());
                reverseMembership.put(neighbour, reverse);
            }
            if (!reverse.contains(task)) {
                throw new WorkflowValidationException("Task " + task.getCloudletId()
                        + " has an asymmetric " + relation + " relation with task "
                        + neighbour.getCloudletId());
            }
        }
    }
}
