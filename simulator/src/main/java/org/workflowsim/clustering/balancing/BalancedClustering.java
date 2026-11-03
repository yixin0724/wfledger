/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.clustering.balancing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.Task;
import org.workflowsim.clustering.BasicClustering;
import org.workflowsim.clustering.TaskSet;
import org.workflowsim.clustering.balancing.methods.ChildAwareHorizontalClustering;
import org.workflowsim.clustering.balancing.methods.HorizontalDistanceBalancing;
import org.workflowsim.clustering.balancing.methods.HorizontalImpactBalancing;
import org.workflowsim.clustering.balancing.methods.HorizontalRandomClustering;
import org.workflowsim.clustering.balancing.methods.HorizontalRuntimeBalancing;
import org.workflowsim.clustering.balancing.methods.VerticalBalancing;
import org.workflowsim.clustering.balancing.metrics.DistanceVariance;
import org.workflowsim.clustering.balancing.metrics.HorizontalRuntimeVariance;
import org.workflowsim.clustering.balancing.metrics.ImpactFactorVariance;
import org.workflowsim.clustering.balancing.metrics.PipelineRuntimeVariance;
import org.workflowsim.utils.Parameters;

/**
 * Shared implementation of balanced clustering: balances job length, impact factor or
 * graph distance under different heuristics while preserving task dependencies.
 *
 * <p>{@code ClusteringParameters.code} selects the concrete balancing method. This
 * implementation describes an abstract clustering strategy and does not represent real
 * resource load balancing or a platform utilization forecast.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BalancedClustering extends BasicClustering {

    /**
     * Expected number of clustered jobs generated per level.
     */
    private final int clusterNum;
    /**
     * Mapping from a logical task to the task set that owns it.
     */
    private final Map<Task, TaskSet> mTask2TaskSet;
    /**
     * Cache from a task set to its computed depth.
     */
    private final Map<TaskSet, Integer> mTaskSet2Depth;

    /**
     * Creates a balanced clusterer.
     *
     * @param clusterNum number of clustered jobs per level ({@code clusters.num})
     */
    public BalancedClustering(int clusterNum) {
        super();
        this.clusterNum = clusterNum;
        this.mTask2TaskSet = new HashMap<>();
        mTaskSet2Depth = new HashMap<>();
    }

    /**
     * Clears the traversal flag of every task set.
     *
     */
    public void cleanTaskSetChecked() {
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            set.hasChecked = false;
        }
    }

    /**
     * Recursively distributes the impact factor toward the parent sets.
     *
     * @param set starting task set
     * @param impact impact factor to add
     */
    private void addImpact(TaskSet set, double impact) {
        /*
         * Propagate the impact factor from the current set to its parent sets.
         */
        set.setImpactFafctor(set.getImpactFactor() + impact);
        int size = set.getParentList().size();
        if (size > 0) {
            double avg = impact / size;
            for (TaskSet parent : set.getParentList()) {
                addImpact(parent, avg);
            }
        }
    }

    /**
     * Computes and prints the balance metrics of the current task sets.
     */
    public void printMetrics() {
        Map<Integer, List<TaskSet>> map = getCurrentTaskSetAtLevels();
        for (TaskSet set : mTask2TaskSet.values()) {
            set.setImpactFafctor(0.0);
        }

        int maxDepth = 0;
        for (Entry<Integer, List<TaskSet>> entry : map.entrySet()) {
            int depth = entry.getKey();
            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }
        List<TaskSet> exits = map.get(maxDepth);
        double avg = 1.0 / exits.size();
        for (TaskSet set : exits) {
            // The legacy branch could assign the impact factor directly; the current path
            // propagates it recursively.
            addImpact(set, avg);
        }

        for (Entry<Integer, List<TaskSet>> entry : map.entrySet()) {
            int depth = entry.getKey();
            List<TaskSet> list = entry.getValue();
            /**
             * Same-level runtime variance.
             */
            double hrv = new HorizontalRuntimeVariance().getMetric(list);
            /**
             * Impact factor variance.
             */
            double ifv = new ImpactFactorVariance().getMetric(list);
            /**
             * Pipeline runtime variance.
             */
            double prv = new PipelineRuntimeVariance().getMetric(list);
            /**
             * Graph distance variance.
             */
            double dv = new DistanceVariance().getMetric(list);
            Log.printLine("HRV " + depth + " " + list.size()
                    + " " + hrv + "\nIFV " + depth + " "
                    + list.size() + " " + ifv + "\nPRV " + depth
                    + " " + list.size() + " " + prv + "\nDV " + depth + " " + list.size() + " " + dv);
        }
    }

    /**
     * Returns the current task sets grouped by computed depth.
     *
     * @return task set list per level
     */
    public Map<Integer, List<TaskSet>> getCurrentTaskSetAtLevels() {
        // hasChecked is restored after the computation so no state leaks into the next
        // balancing step.
        Map<Integer, List<TaskSet>> map = new HashMap<>();
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                int depth = getDepth(set);
                if (!map.containsKey(depth)) {
                    map.put(depth, new ArrayList<>());
                }
                List list = map.get(depth);
                list.add(set);
            }
        }
        mTaskSet2Depth.clear();
        // The traversal flag must be restored for later algorithms to reuse.
        cleanTaskSetChecked();
        return map;
    }

    /**
     * Recursively computes the DAG depth of a task set and caches the result.
     *
     * @param set task set
     * @return DAG depth
     */
    private int getDepth(TaskSet set) {
        if (mTaskSet2Depth.containsKey(set)) {
            return mTaskSet2Depth.get(set);
        } else {
            int depth = 0;
            for (TaskSet parent : set.getParentList()) {
                int curDepth = getDepth(parent);
                if (curDepth > depth) {
                    depth = curDepth;
                }
            }
            depth++;
            mTaskSet2Depth.put(set, depth);
            return depth;
        }
    }

    /**
     * Tests whether one task is an ancestor of another.
     *
     * @param ancestor candidate ancestor task
     * @param set candidate descendant task
     * @return whether the ancestor relation holds
     */
    private boolean check(Task ancestor, Task set) {
        if (ancestor == null || set == null) {
            return false;
        }
        if (ancestor == set) {
            return true;
        }
        for (Task parent : set.getParentList()) {
            if (check(ancestor, parent)) {
                return true;
            } else {
                // parent.hasChecked = true;
            }
        }
        return false;
    }
    /**
     * Edges removed temporarily, restored once clustering completes.
     */
    private final Map<Task, Task> mRecover = new HashMap<>();

    /**
     * Temporarily removes the child edges that would form a cross-level ancestor relation,
     * recording them for restoration.
     */
    private void remove() {

        for (Task set : this.getTaskList()) {
            if (set.getChildList().size() >= 2) {
                for (int i = 0; i < set.getChildList().size(); i++) {
                    Task children = (Task) set.getChildList().get(i);
                    for (int j = i + 1; j < set.getChildList().size(); j++) {
                        Task another = (Task) set.getChildList().get(j);
                        // Check only two sibling candidates at different depths so that
                        // recursion is not wasted.
                        if (children.getDepth() > another.getDepth()) {
                            if (check(another, children)) {
                                // Remove the edge for now and restore it once clustering completes.
                                set.getChildList().remove(children);
                                children.getParentList().remove(set);
                                i--;
                                mRecover.put(set, children);
                                // cleanTaskSetChecked();
                                break;
                            } else {
                                // cleanTaskSetChecked();
                            }
                        }
                        if (another.getDepth() > children.getDepth()) {
                            if (check(children, another)) {
                                set.getChildList().remove(another);
                                another.getParentList().remove(set);
                                i--;
                                mRecover.put(set, another);
                                // cleanTaskSetChecked();
                                break;
                            } else {
                                // cleanTaskSetChecked();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Restores the dependency edges removed earlier.
     */
    private void recover() {
        for (Entry<Task, Task> entry : mRecover.entrySet()) {
            Task set = entry.getKey();
            Task children = entry.getValue();
            set.getChildList().add(children);
            children.getParentList().add(set);
        }
    }

    @Override
    public void run() {

        if (clusterNum > 0) {
            for (Task task : getTaskList()) {
                TaskSet set = new TaskSet();
                set.addTask(task);
                mTask2TaskSet.put(task, set);
            }
        }

        remove();
        updateTaskSetDependencies();

        printMetrics();
        String code = Parameters.getClusteringParameters().getCode();
        Map<Integer, List<TaskSet>> map = getCurrentTaskSetAtLevels();
        if (code != null) {
            for (char c : code.toCharArray()) {

                switch (c) {
                    case 'v':
                        VerticalBalancing v = new VerticalBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        v.run();
                        break;
                    case 'c':
                        ChildAwareHorizontalClustering ch =
                                new ChildAwareHorizontalClustering(map, this.mTask2TaskSet, this.clusterNum);
                        ch.run();
                        updateTaskSetDependencies();
                        break;
                    case 'r':
                        HorizontalRuntimeBalancing r =
                                new HorizontalRuntimeBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        r.run();
                        updateTaskSetDependencies();
                        break;
                    case 'i':
                        HorizontalImpactBalancing i =
                                new HorizontalImpactBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        i.run();
                        break;
                    case 'd':
                        HorizontalDistanceBalancing d =
                                new HorizontalDistanceBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        d.run();
                        break;
                    case 'h':
                        HorizontalRandomClustering h =
                                new HorizontalRandomClustering(map, this.mTask2TaskSet, this.clusterNum);
                        h.run();
                        break;
                    default:
                        break;
                }
            }
            printMetrics();
        }

        printOut();

        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                addTasks2Job(set.getTaskList());
            }
        }
        // Clear this round's traversal flags so no state leaks into later clustering steps.
        cleanTaskSetChecked();

        updateDependencies();
        addClustDelay();

        recover();
    }

    /**
     * Prints clustering diagnostics for the task sets.
     */
    private void printOut() {
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;

                Log.printLine("Job");
                for (Task task : set.getTaskList()) {
                    Log.printLine(
                            "Task " + task.getCloudletId() + " " + task.getImpact() + " " + task.getCloudletLength());
                }
            }
        }
        // Restore the traversal flag before the method exits.
        cleanTaskSetChecked();
    }

    /**
     * Rebuilds the task-set parent/child relations from the logical task dependencies.
     */
    private void updateTaskSetDependencies() {

        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                set.getChildList().clear();
                set.getParentList().clear();
                for (Task task : set.getTaskList()) {
                    for (Task parent : task.getParentList()) {
                        TaskSet parentSet = mTask2TaskSet.get(parent);
                        if (!set.getParentList().contains(parentSet) && set != parentSet) {
                            set.getParentList().add(parentSet);
                        }
                    }
                    for (Task child : task.getChildList()) {
                        TaskSet childSet = mTask2TaskSet.get(child);
                        if (!set.getChildList().contains(childSet) && set != childSet) {
                            set.getChildList().add(childSet);
                        }
                    }
                }
            }
        }
        // Restore the traversal flag before the method exits.
        cleanTaskSetChecked();
    }
}
