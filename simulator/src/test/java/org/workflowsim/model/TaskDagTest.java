package org.workflowsim.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;

class TaskDagTest {

    private Task taskA;
    private Task taskB;
    private Task taskC;

    @BeforeEach
    void setUp() {

        taskA = new Task(1, 1000);
        taskB = new Task(2, 2000);
        taskC = new Task(3, 3000);
    }

    @Test
    void singleTaskHasNoParentsOrChildren() {
        assertTrue(taskA.getParentList().isEmpty());
        assertTrue(taskA.getChildList().isEmpty());
    }

    @Test
    void chainDependency() {

        taskB.addParent(taskA);
        taskA.addChild(taskB);
        taskC.addParent(taskB);
        taskB.addChild(taskC);

        assertEquals(1, taskB.getParentList().size());
        assertEquals(taskA, taskB.getParentList().get(0));
        assertEquals(1, taskB.getChildList().size());
        assertEquals(taskC, taskB.getChildList().get(0));

        assertTrue(taskA.getParentList().isEmpty());
        assertTrue(taskC.getChildList().isEmpty());
    }

    @Test
    void forkJoinDag() {

        Task taskD = new Task(4, 4000);

        taskA.addChild(taskB);
        taskA.addChild(taskC);
        taskB.addParent(taskA);
        taskC.addParent(taskA);

        taskB.addChild(taskD);
        taskC.addChild(taskD);
        taskD.addParent(taskB);
        taskD.addParent(taskC);

        assertEquals(2, taskA.getChildList().size());
        assertEquals(2, taskD.getParentList().size());
    }

    @Test
    void taskLengthIsPreserved() {
        assertEquals(1000, taskA.getCloudletLength());
        assertEquals(2000, taskB.getCloudletLength());
        assertEquals(3000, taskC.getCloudletLength());
    }

    @Test
    void taskIdIsPreserved() {
        assertEquals(1, taskA.getCloudletId());
        assertEquals(2, taskB.getCloudletId());
    }

    @Test
    void depthCanBeSet() {
        taskA.setDepth(1);
        taskB.setDepth(2);
        assertEquals(1, taskA.getDepth());
        assertEquals(2, taskB.getDepth());
    }

    @Test
    void vmIdAssignment() {
        taskA.setVmId(5);
        assertEquals(5, taskA.getVmId());
    }
}
