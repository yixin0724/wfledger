package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlannerImpactDeepChainTest {

    private boolean logWasDisabled;

    @BeforeEach
    void kernelUp() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);
    }

    @AfterEach
    void kernelDown() {
        CloudSim.reset();
        Log.setDisabled(logWasDisabled);
    }

    private static List<Task> chain(int depth) {
        List<Task> tasks = new ArrayList<Task>(depth);
        Task previous = null;
        for (int id = 1; id <= depth; id++) {
            Task task = new Task(id, 1000L);
            if (previous != null) {
                previous.addChild(task);
                task.addParent(previous);
            }
            tasks.add(task);
            previous = task;
        }
        return tasks;
    }

    private static void runImpactPropagation(WorkflowPlanner planner, List<Task> tasks) throws Exception {
        Method method = WorkflowPlanner.class.getDeclaredMethod("processImpactFactors", List.class);
        method.setAccessible(true);
        try {
            method.invoke(planner, tasks);
        } catch (InvocationTargetException invocation) {

            Throwable cause = invocation.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw invocation;
        }
    }

    @Test
    void shallowChainPropagatesExactUnityImpact() throws Exception {
        WorkflowPlanner planner = new WorkflowPlanner("impact-shallow", 1);
        List<Task> tasks = chain(16);
        runImpactPropagation(planner, tasks);
        for (Task task : tasks) {
            assertEquals(
                    1.0,
                    task.getImpact(),
                    0.0,
                    "the impact of task " + task.getCloudletId() + " on the chain must be exactly 1.0");
        }
    }

    @Test
    void deepChainCompletesWithoutStackOverflow() throws Exception {
        final int depth = 50_000;
        WorkflowPlanner planner = new WorkflowPlanner("impact-deep", 1);
        List<Task> tasks = chain(depth);

        runImpactPropagation(planner, tasks);
        for (int i = 0; i < depth; i += (i < 4 || i >= depth - 4) ? 1 : 997) {
            assertEquals(
                    1.0,
                    tasks.get(i).getImpact(),
                    0.0,
                    "the impact of the task at depth " + (i + 1) + " must be exactly 1.0");
        }
    }

    @Test
    void randomDagImpactsMatchDocumentedRecursiveSemanticsBitwise() throws Exception {
        java.util.Random random = new java.util.Random(20261019L);
        for (int graph = 0; graph < 40; graph++) {
            java.util.List<int[]> parentSpec = randomParentSpec(random, 30 + random.nextInt(170));
            List<Task> production = materialise(parentSpec);
            List<Task> reference = materialise(parentSpec);

            WorkflowPlanner planner = new WorkflowPlanner("impact-diff-" + graph, 1);
            runImpactPropagation(planner, production);
            runReferenceImpactPropagation(reference);

            for (int i = 0; i < production.size(); i++) {
                assertEquals(
                        Double.doubleToRawLongBits(reference.get(i).getImpact()),
                        Double.doubleToRawLongBits(production.get(i).getImpact()),
                        "DAG#" + graph + " task " + production.get(i).getCloudletId()
                                + " impact must match the literal documented semantics bit for bit");
            }
        }
    }

    private static java.util.List<int[]> randomParentSpec(java.util.Random random, int size) {
        java.util.List<int[]> spec = new ArrayList<int[]>(size);
        for (int i = 0; i < size; i++) {
            int parentCount = Math.min(i, random.nextInt(4));
            java.util.Set<Integer> parents = new java.util.TreeSet<Integer>();
            while (parents.size() < parentCount) {
                parents.add(random.nextInt(i));
            }
            int[] ids = new int[parents.size()];
            int index = 0;
            for (Integer parent : parents) {
                ids[index++] = parent.intValue();
            }
            spec.add(ids);
        }
        return spec;
    }

    private static List<Task> materialise(java.util.List<int[]> parentSpec) {
        List<Task> tasks = new ArrayList<Task>(parentSpec.size());
        for (int i = 0; i < parentSpec.size(); i++) {
            Task task = new Task(i + 1, 1000L);
            tasks.add(task);
        }
        for (int i = 0; i < parentSpec.size(); i++) {
            for (int parentId : parentSpec.get(i)) {
                tasks.get(parentId).addChild(tasks.get(i));
                tasks.get(i).addParent(tasks.get(parentId));
            }
        }
        return tasks;
    }

    private static void runReferenceImpactPropagation(List<Task> tasks) {
        List<Task> exits = new ArrayList<Task>();
        for (Task task : tasks) {
            if (task.getChildList().isEmpty()) {
                exits.add(task);
            }
        }
        double avg = 1.0 / exits.size();
        for (Task exit : exits) {
            referenceAddImpact(exit, avg);
        }
    }

    private static void referenceAddImpact(Task task, double impact) {
        task.setImpact(task.getImpact() + impact);
        int size = task.getParentList().size();
        if (size > 0) {
            double avg = impact / size;
            for (Task parent : task.getParentList()) {
                referenceAddImpact(parent, avg);
            }
        }
    }
}
