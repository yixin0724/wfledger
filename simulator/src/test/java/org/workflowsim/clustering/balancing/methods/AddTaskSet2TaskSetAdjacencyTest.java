package org.workflowsim.clustering.balancing.methods;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

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

        grandparent.getChildList().add(tail);
        tail.getParentList().add(grandparent);
        tail.getChildList().add(child);
        child.getParentList().add(tail);

        method.addTaskSet2TaskSet(tail, head);

        assertTrue(head.getParentList().contains(grandparent), "head must take over the parent-side adjacency");
        assertTrue(head.getChildList().contains(child), "head must take over the child-side adjacency");
        assertTrue(head.getTaskList().contains(tailTask), "head must receive the task membership");
        assertTrue(taskMap.get(tailTask) == head, "task map must now point to head");

        assertFalse(child.getParentList().contains(tail), "child parent must not retain tail (missing remove(tail))");
        assertFalse(grandparent.getChildList().contains(tail), "grandparent's child list must not retain tail");
        assertTrue(child.getParentList().contains(head));
        assertTrue(grandparent.getChildList().contains(head));

        assertTrue(
                tail.getTaskList().isEmpty()
                        && tail.getParentList().isEmpty()
                        && tail.getChildList().isEmpty(),
                "the three lists of the merged set must be emptied");
    }

    @Test
    void mergingIntoDirectChildKeepsHeadFreeOfSelfLoops() {

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
