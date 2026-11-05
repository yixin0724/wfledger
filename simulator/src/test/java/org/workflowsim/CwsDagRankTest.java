package org.workflowsim;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Exact CWS rank derivation from the paper's Figure 1 physical DAG (R-PAPER-02). */
class CwsDagRankTest {

    @Test
    void figureOneRanksAreLongestDescendantProcessPaths() {
        List<Task> tasks = Arrays.asList(
                new Task(1, 100L),
                new Task(2, 100L),
                new Task(3, 100L),
                new Task(4, 100L),
                new Task(5, 100L),
                new Task(6, 100L));
        connect(tasks.get(0), tasks.get(1));
        connect(tasks.get(0), tasks.get(2));
        connect(tasks.get(0), tasks.get(3));
        connect(tasks.get(2), tasks.get(4));
        connect(tasks.get(3), tasks.get(4));
        connect(tasks.get(1), tasks.get(5));
        connect(tasks.get(4), tasks.get(5));

        WorkflowDagValidator.validateAndAssignDepths(tasks);

        int[] ranks = tasks.stream().mapToInt(Task::getCwsRank).toArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {4, 2, 3, 3, 2, 1}, ranks);
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }
}
