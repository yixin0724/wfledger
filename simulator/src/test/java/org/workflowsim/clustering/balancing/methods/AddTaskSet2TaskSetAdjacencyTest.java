package org.workflowsim.clustering.balancing.methods;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * M7 killer (review-engineering Phase 3: targeted regression for a mutation-pilot
 * survivor).
 *
 * <p>The mutation pilot demonstrated that after {@code BalancingMethod.addTaskSet2TaskSet}
 * loses {@code taskSet.getParentList().remove(tail)} in its child-side rewiring loop,
 * <b>none of the 1024 existing tests fails</b> -- the adjacency-release contract of the
 * merge primitive was previously covered up by the wholesale rebuild in the downstream
 * {@code updateTaskSetDependencies()} (the dirt is laundered by a later stage), so the
 * single-call semantics had no test anchor at all.</p>
 *
 * <p>This test establishes an <b>adjacency-closure contract</b> directly on the merge
 * primitive (a conservation law, not copied from the implementation): after tail is
 * merged into head, every original tail neighbour's adjacency list must <b>point only to
 * head and no longer to tail</b>; tail's own three lists are emptied.</p>
 */
class AddTaskSet2TaskSetAdjacencyTest {

    @Test
    void mergingTailRewiresEveryNeighbourAdjacencyExclusivelyToHead() {
        Map levelMap = new HashMap();
        Map<Task, TaskSet> taskMap = new HashMap<>();
        BalancingMethod method = new BalancingMethod(levelMap, taskMap, 4);

        TaskSet grandparent = new TaskSet();
        TaskSet tail = new TaskSet();
        TaskSet head = new TaskSet();
        TaskSet child = new TaskSet();
        Task tailTask = new Task(1, 100L);
        tail.addTask(tailTask);
        taskMap.put(tailTask, tail);

        // Graph: grandparent -> tail -> child (tail has one neighbour on each side).
        grandparent.getChildList().add(tail);
        tail.getParentList().add(grandparent);
        tail.getChildList().add(child);
        child.getParentList().add(tail);

        method.addTaskSet2TaskSet(tail, head);

        // head takes over every adjacency of tail.
        assertTrue(head.getParentList().contains(grandparent), "head must take over the parent-side adjacency");
        assertTrue(head.getChildList().contains(child), "head must take over the child-side adjacency");
        assertTrue(head.getTaskList().contains(tailTask), "head must receive the task membership");
        assertTrue(taskMap.get(tailTask) == head, "task map must now point to head");

        // Neighbours must release their references to tail (the M7 mutation loses that
        // release -- this assertion group is the killer).
        assertFalse(child.getParentList().contains(tail), "child parent must not retain tail (missing remove(tail))");
        assertFalse(grandparent.getChildList().contains(tail), "grandparent's child list must not retain tail");
        assertTrue(child.getParentList().contains(head));
        assertTrue(grandparent.getChildList().contains(head));

        // All three lists of tail are emptied.
        assertTrue(
                tail.getTaskList().isEmpty()
                        && tail.getParentList().isEmpty()
                        && tail.getChildList().isEmpty(),
                "the three lists of the merged set must be emptied");
    }

    @Test
    void mergingIntoDirectChildKeepsHeadFreeOfSelfLoops() {
        // F-008 regression for the adjacent case: when tail -> head is a parent-child
        // relation (VerticalBalancing scenario), head must not acquire a self-loop.
        Map levelMap = new HashMap();
        Map<Task, TaskSet> taskMap = new HashMap<>();
        BalancingMethod method = new BalancingMethod(levelMap, taskMap, 4);

        TaskSet tail = new TaskSet();
        TaskSet head = new TaskSet();
        TaskSet downstream = new TaskSet();
        tail.getChildList().add(head);
        tail.getChildList().add(downstream);
        head.getParentList().add(tail);
        downstream.getParentList().add(tail);

        method.addTaskSet2TaskSet(tail, head);

        assertFalse(head.getParentList().contains(head), "head must not become its own parent");
        assertFalse(head.getChildList().contains(head), "head must not become its own child");
        assertTrue(head.getChildList().contains(downstream), "second child-side neighbour must be rewired");
        assertFalse(downstream.getParentList().contains(tail));
    }
}
