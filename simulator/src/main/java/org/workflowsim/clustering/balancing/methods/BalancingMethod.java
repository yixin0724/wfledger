/*
 *
 *   Copyright 2012-2013 University Of Southern California
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.workflowsim.clustering.balancing.methods;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * Common base class of all balancing clustering methods.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BalancingMethod {

    /** Logical task to task set mapping. */
    private final Map<Task, TaskSet> taskMap;

    /**
     * Task sets organized by DAG level.
     */
    private final Map<Integer, List<TaskSet>> levelMap;
    /** Target number of clustered jobs per level. */
    private final int clusterNum;

    /**
     * Creates the shared state of a balancing method.
     *
     * @param levelMap task sets of each level
     * @param taskMap logical task to task set mapping
     * @param clusterNum target number of clustered jobs per level
     */
    public BalancingMethod(Map levelMap, Map taskMap, int clusterNum) {
        this.taskMap = taskMap;
        this.levelMap = levelMap;
        this.clusterNum = clusterNum;
    }

    /**
     * Returns the logical task to task set mapping.
     *
     * @return task mapping
     */
    public Map<Task, TaskSet> getTaskMap() {
        return this.taskMap;
    }

    /**
     * Returns the task sets organized by level.
     *
     * @return level mapping
     */
    public Map<Integer, List<TaskSet>> getLevelMap() {
        return this.levelMap;
    }

    /**
     * Returns the target number of clustered jobs per level.
     *
     * @return {@code clusters.num}
     */
    public int getClusterNum() {
        return this.clusterNum;
    }

    /**
     * Merges every task of {@code tail} into {@code head} and updates the task mapping and the
     * neighbor relations in step. Vertical balancing strategies may reuse this operation.
     *
     * @param tail set to merge and then clear
     * @param head set that receives the tasks
     */
    public void addTaskSet2TaskSet(TaskSet tail, TaskSet head) {
        head.addTask(tail.getTaskList());
        head.getParentList().remove(tail);
        // The task mapping must stay consistent with the merged set.
        for (Task task : tail.getTaskList()) {
            getTaskMap().put(task, head);
        }
        /*
         * A same-level merge can add impact factors directly; vertical balancing normally does not
         * decide on impact factor, but the value is still kept consistent.
         */
        head.setImpactFafctor(head.getImpactFactor() + tail.getImpactFactor());
        for (TaskSet taskSet : tail.getParentList()) {
            if (taskSet == head || taskSet == tail) {
                // F-008 fix (2026-10-19): reconnecting neighbors must not turn the target set into
                // its own parent or child. In vertical balancing "parent merged into child", tail.childList
                // contains head; the symmetric guard rejects pathological adjacency and prevents a
                // self-cycle in the set graph that would make BalancedClustering.getDepth recurse forever
                // (regression: ClusteringConservationPropertyTest.balancedVertical*).
                continue;
            }
            taskSet.getChildList().remove(tail);
            if (!taskSet.getChildList().contains(head)) {
                taskSet.getChildList().add(head);
            }
            if (!head.getParentList().contains(taskSet)) {
                head.getParentList().add(taskSet);
            }
        }

        for (TaskSet taskSet : tail.getChildList()) {
            if (taskSet == head || taskSet == tail) {
                // As above: head must not appear in its own parent or child list.
                continue;
            }
            taskSet.getParentList().remove(tail);
            if (!taskSet.getParentList().contains(head)) {
                taskSet.getParentList().add(head);
            }
            if (!head.getChildList().contains(taskSet)) {
                head.getChildList().add(taskSet);
            }
        }
        tail.getTaskList().clear();
        tail.getChildList().clear();
        tail.getParentList().clear();
    }
    /**
     * The base class provides no concrete balancing strategy; subclasses must override this method.
     */
    public void run() {
        throw (new RuntimeException("Should not use this function"));
    }

    /**
     * Clears the traversal flags of all task sets.
     */
    public void cleanTaskSetChecked() {
        Collection sets = getTaskMap().values();
        for (Iterator it = sets.iterator(); it.hasNext(); ) {
            TaskSet set = (TaskSet) it.next();
            set.hasChecked = false;
        }
    }
}
