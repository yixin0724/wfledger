package org.workflowsim.clustering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.clustering.balancing.BalancedClustering;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationRandom;

class ClusteringConservationPropertyTest {

    private static final long DAG_SEED = 20261019L;
    private static final long STREAM_SEED = 4242L;
    private static final int LEVELS = 4;
    private static final int MAX_PER_LEVEL = 6;

    private boolean logWasDisabled;

    @BeforeEach
    void quietLegacyGlobals() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
        Parameters.reset();
        initLegacy(new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null));
        SimulationRandom.reset(STREAM_SEED);
    }

    @AfterEach
    void restoreLegacyGlobals() {
        Parameters.reset();
        SimulationRandom.reset(0L);
        Log.setDisabled(logWasDisabled);
    }

    private static void initLegacy(ClusteringParameters parameters) {
        Parameters.init(
                2,
                "conservation.dax",
                null,
                null,
                new OverheadParameters(0, null, null, null, null, 0.0),
                parameters,
                SchedulingAlgorithm.FCFS,
                PlanningAlgorithm.INVALID,
                null,
                0L);
        SimulationRandom.reset(STREAM_SEED);
    }

    private static final class Dag {
        final List<Task> tasks;
        final long totalLength;
        final Set<Long> taskEdges = new HashSet<Long>();
        final List<FileItem> sharedInputs = new ArrayList<FileItem>();

        Dag(List<Task> tasks, long totalLength) {
            this.tasks = tasks;
            this.totalLength = totalLength;
        }
    }

    private static Dag layeredDag(boolean withFiles) {
        java.util.Random random = new java.util.Random(DAG_SEED);
        List<Task> tasks = new ArrayList<Task>();
        List<FileItem> sharedInputs = new ArrayList<FileItem>();
        if (withFiles) {

            FileItem inputA = new FileItem("shared-input-a", 300_000.0);
            inputA.setType(FileType.INPUT);
            FileItem inputB = new FileItem("shared-input-b", 700_000.0);
            inputB.setType(FileType.INPUT);
            sharedInputs.add(inputA);
            sharedInputs.add(inputB);
        }
        List<Task> previousLevel = new ArrayList<Task>();
        long totalLength = 0L;
        int id = 1;
        for (int level = 0; level < LEVELS; level++) {
            List<Task> current = new ArrayList<Task>();
            int count = 2 + random.nextInt(MAX_PER_LEVEL - 1);
            for (int i = 0; i < count; i++) {
                Task task = createTask(id++, 500L + random.nextInt(4500), withFiles, sharedInputs);
                if (!previousLevel.isEmpty() && level > 0) {
                    int parents = 1 + random.nextInt(Math.min(3, previousLevel.size()));
                    for (int p = 0; p < parents; p++) {
                        Task parent = previousLevel.get(random.nextInt(previousLevel.size()));
                        if (task.getParentList().contains(parent)) {
                            continue;
                        }
                        parent.addChild(task);
                        task.addParent(parent);
                    }
                }
                current.add(task);
                tasks.add(task);
                totalLength += task.getCloudletLength();
            }
            previousLevel = current;
        }
        WorkflowDagValidator.validateAndAssignDepths(tasks);
        Dag dag = new Dag(tasks, totalLength);
        dag.sharedInputs.addAll(sharedInputs);
        for (Task task : tasks) {
            for (Object parent : task.getParentList()) {
                dag.taskEdges.add(edgeKey((Task) parent, task));
            }
        }
        return dag;
    }

    private static Task createTask(int id, long length, boolean withFiles, List<FileItem> sharedInputs) {
        Task task = new Task(id, length);
        if (withFiles) {
            List<FileItem> files = new ArrayList<FileItem>();
            for (FileItem input : sharedInputs) {

                files.add(input);
                task.addRequiredFile(input.getName());
            }
            FileItem output = new FileItem("task-" + id + "-output", 100_000.0 + id);
            output.setType(FileType.OUTPUT);
            files.add(output);
            task.setFileList(files);
        } else {
            task.setFileList(new ArrayList<FileItem>());
        }
        return task;
    }

    private static long edgeKey(Task parent, Task child) {
        return ((long) parent.getCloudletId() << 32) | (child.getCloudletId() & 0xffffffffL);
    }

    private static List<Job> runEngine(BasicClustering engine, boolean withFiles) {
        Dag dag = layeredDag(withFiles);
        engine.setTaskList(new ArrayList<Task>(dag.tasks));
        engine.run();
        assertPartitionDependenciesFilesLength(engine.getJobList(), dag, engine.getTaskFiles());
        return engine.getJobList();
    }

    private static Map<Integer, Job> taskMembership(List<Job> jobs, Dag dag) {
        Map<Integer, Job> taskJob = new HashMap<Integer, Job>();
        Set<Integer> expectedIds = new HashSet<Integer>();
        for (Task task : dag.tasks) {
            expectedIds.add(Integer.valueOf(task.getCloudletId()));
        }
        for (Job job : jobs) {
            for (Task task : job.getTaskList()) {
                Integer id = Integer.valueOf(task.getCloudletId());
                assertTrue(expectedIds.contains(id), "clustered output contains an unknown task " + id);
                assertTrue(!taskJob.containsKey(id), "clustered output contains a duplicate task " + id);
                taskJob.put(id, job);
            }
        }
        assertEquals(
                expectedIds, taskJob.keySet(), "the clustered output must cover every task as the original partition");
        return taskJob;
    }

    @Test
    void basicClusteringKeepsOneJobPerTaskWithExactDependencies() {
        Dag dag = layeredDag(false);
        BasicClustering engine = new BasicClustering();
        engine.setTaskList(new ArrayList<Task>(dag.tasks));
        engine.run();

        List<Job> jobs = engine.getJobList();
        assertEquals(dag.tasks.size(), jobs.size(), "without clustering the Job count must equal the task count");
        Map<Integer, Job> membership = taskMembership(jobs, dag);
        assertPartitionAndLength(jobs, dag, membership, engine.getTaskFiles());
    }

    @Test
    void horizontalClusterNumBalancesLayersAndConservesEverything() {
        horizontalConserves(3, 0);
    }

    @Test
    void horizontalClusterSizeCollapsesLayersAndConservesEverything() {
        horizontalConserves(0, 2);
    }

    private void horizontalConserves(int clusterNum, int clusterSize) {
        initLegacy(new ClusteringParameters(clusterNum, clusterSize, ClusteringParameters.ClusteringMethod.NONE, null));
        List<Job> jobs = runEngine(new HorizontalClustering(clusterNum, clusterSize), true);
        for (Job job : jobs) {
            int depth = Integer.MIN_VALUE;
            for (Task task : job.getTaskList()) {
                if (depth == Integer.MIN_VALUE) {
                    depth = task.getDepth();
                } else {
                    assertEquals(
                            depth,
                            task.getDepth(),
                            "a horizontal-clustering Job must contain tasks of one depth level only");
                }
            }
        }
        assertTrue(jobs.size() < layeredDag(true).tasks.size(), "enabling clustering must yield fewer Jobs than tasks");
    }

    @Test
    void verticalClusteringMergesAlongDependencyChainsAndConservesEverything() {
        List<Job> jobs = runEngine(new VerticalClustering(1), true);
        for (Job job : jobs) {
            List<Task> members = job.getTaskList();
            for (int i = 1; i < members.size(); i++) {
                Task left = members.get(i - 1);
                Task right = members.get(i);
                assertTrue(
                        left.getChildList().contains(right)
                                || left.getParentList().contains(right),
                        "adjacent members of a VERTICAL clustering must share "
                                + "a task-level parent-child edge: "
                                + left.getCloudletId() + " and " + right.getCloudletId());
            }
        }
    }

    @Test
    void blockClusteringConservesPartitionDependenciesAndFiles() throws Exception {
        runEngine(new BlockClustering(3, 2), true);
    }

    @Test
    void balancedVerticalCodeRestoresOriginalDependenciesAndConservesEverything() {
        balancedConserves("v");
    }

    @Test
    void balancedRandomCodeRestoresOriginalDependenciesAndConservesEverything() {
        balancedConserves("r");
    }

    private void balancedConserves(String code) {
        initLegacy(new ClusteringParameters(3, 0, ClusteringParameters.ClusteringMethod.BALANCED, code));
        Dag dag = layeredDag(true);
        Map<Integer, Set<Integer>> parentsBefore = dependencySnapshot(dag.tasks);
        Map<Integer, Set<Integer>> childrenBefore = dependencySnapshotChildren(dag.tasks);
        BalancedClustering engine = new BalancedClustering(3);
        engine.setTaskList(new ArrayList<Task>(dag.tasks));
        engine.run();

        assertPartitionDependenciesFilesLength(engine.getJobList(), dag, engine.getTaskFiles());

        for (Task task : dag.tasks) {
            Set<Integer> parents = dependencySnapshot(dag.tasks).get(task.getCloudletId());
            Set<Integer> children = dependencySnapshotChildren(dag.tasks).get(task.getCloudletId());
            assertEquals(
                    parentsBefore.get(task.getCloudletId()),
                    parents,
                    "BALANCED(" + code + ") must restore the parent set after execution: task " + task.getCloudletId());
            assertEquals(
                    childrenBefore.get(task.getCloudletId()),
                    children,
                    "BALANCED(" + code + ") must restore the child set after execution: task " + task.getCloudletId());
        }
    }

    private static void assertPartitionDependenciesFilesLength(List<Job> jobs, Dag dag, List<FileItem> taskFiles) {
        Map<Integer, Job> membership = taskMembership(jobs, dag);

        long jobTotal = 0L;
        for (Job job : jobs) {
            jobTotal += job.getCloudletLength();
        }
        assertEquals(dag.totalLength, jobTotal, "the sum of Job lengths must be conserved after clustering");

        Set<Long> expectedJobPairs = new HashSet<Long>();
        for (Task task : dag.tasks) {
            for (Object parentObject : task.getParentList()) {
                Task parent = (Task) parentObject;
                Job parentJob = membership.get(parent.getCloudletId());
                Job childJob = membership.get(task.getCloudletId());
                if (parentJob != childJob) {
                    expectedJobPairs.add(jobEdgeKey(parentJob, childJob));
                }
            }
        }
        Set<Long> actualJobPairs = new HashSet<Long>();
        for (Job job : jobs) {
            for (Object parentObject : job.getParentList()) {
                Job parentJob = (Job) parentObject;
                if (parentJob == job) {
                    throw new AssertionError("the Job graph must not contain self-loops: " + job.getCloudletId());
                }
                assertTrue(
                        actualJobPairs.add(jobEdgeKey(parentJob, job)),
                        "the Job graph must not contain duplicate edges");
            }
        }
        assertEquals(
                expectedJobPairs,
                actualJobPairs,
                "Job dependency edges must be the projection of cross-cluster task edges");

        assertJobGraphAcyclic(jobs);

        Set<String> inputNames = new HashSet<String>();
        Set<String> outputNames = new HashSet<String>();
        for (FileItem file : taskFiles) {
            if (file.getType() == FileType.INPUT) {
                assertTrue(inputNames.add(file.getName()), "input file registered twice: " + file.getName());
            } else if (file.getType() == FileType.OUTPUT) {
                outputNames.add(file.getName());
            }
        }
        Set<String> expectedInputs = new HashSet<String>();
        Set<String> expectedOutputs = new HashSet<String>();
        for (Task task : dag.tasks) {
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.INPUT) {
                    expectedInputs.add(file.getName());
                } else {
                    expectedOutputs.add(file.getName());
                }
            }
        }
        assertEquals(expectedInputs, inputNames, "getTaskFiles must carry exactly the external input set");
        assertTrue(outputNames.containsAll(expectedOutputs), "getTaskFiles must carry every output");
    }

    private static void assertPartitionAndLength(
            List<Job> jobs, Dag dag, Map<Integer, Job> membership, List<FileItem> taskFiles) {
        assertPartitionDependenciesFilesLength(jobs, dag, taskFiles);
    }

    private static long jobEdgeKey(Job parent, Job child) {
        return ((long) parent.getCloudletId() << 32) | (child.getCloudletId() & 0xffffffffL);
    }

    private static void assertJobGraphAcyclic(List<Job> jobs) {
        Map<Integer, Integer> state = new HashMap<Integer, Integer>();
        java.util.ArrayDeque<Job> stack = new java.util.ArrayDeque<Job>();
        for (Job job : jobs) {
            if (state.getOrDefault(Integer.valueOf(job.getCloudletId()), 0) == 0) {
                stack.push(job);
                while (!stack.isEmpty()) {
                    Job current = stack.peek();
                    Integer id = Integer.valueOf(current.getCloudletId());
                    int colour = state.getOrDefault(id, 0);
                    if (colour == 0) {
                        state.put(id, 1);
                        for (Object childObject : current.getChildList()) {
                            Job child = (Job) childObject;
                            if (state.getOrDefault(Integer.valueOf(child.getCloudletId()), 0) == 1) {
                                throw new AssertionError("cycle in the Job graph: " + current.getCloudletId() + " -> "
                                        + child.getCloudletId());
                            }
                            if (state.getOrDefault(Integer.valueOf(child.getCloudletId()), 0) == 0) {
                                stack.push(child);
                            }
                        }
                    } else {
                        state.put(id, 2);
                        stack.pop();
                    }
                }
            }
        }
    }

    private static Map<Integer, Set<Integer>> dependencySnapshot(List<Task> tasks) {
        Map<Integer, Set<Integer>> snapshot = new LinkedHashMap<Integer, Set<Integer>>();
        for (Task task : tasks) {
            Set<Integer> parents = new HashSet<Integer>();
            for (Object parentObject : task.getParentList()) {
                Task parent = (Task) parentObject;
                parents.add(Integer.valueOf(parent.getCloudletId()));
            }
            snapshot.put(Integer.valueOf(task.getCloudletId()), parents);
        }
        return snapshot;
    }

    private static Map<Integer, Set<Integer>> dependencySnapshotChildren(List<Task> tasks) {
        Map<Integer, Set<Integer>> snapshot = new LinkedHashMap<Integer, Set<Integer>>();
        for (Task task : tasks) {
            Set<Integer> children = new HashSet<Integer>();
            for (Object childObject : task.getChildList()) {
                Task child = (Task) childObject;
                children.add(Integer.valueOf(child.getCloudletId()));
            }
            snapshot.put(Integer.valueOf(task.getCloudletId()), children);
        }
        return snapshot;
    }
}
