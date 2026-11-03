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
package org.workflowsim.clustering;

import java.util.ArrayList;
import java.util.List;

import org.workflowsim.Task;

/**
 * A group of logical tasks used internally by a clustering algorithm.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class TaskSet {

    /**
     * Logical tasks in this set.
     */
    private final List<Task> taskList;
    /**
     * Parent sets of this task set.
     */
    private final List<TaskSet> parentList;
    /**
     * Child sets of this task set.
     */
    private final List<TaskSet> childList;
    /**
     * Checked flag used during traversal.
     */
    public boolean hasChecked;
    /**
     * Impact factor used by the clustering heuristic.
     */
    private double impactFactor;

    /**
     * Creates an empty task set.
     */
    public TaskSet() {
        this.taskList = new ArrayList<>();
        this.parentList = new ArrayList<>();
        this.childList = new ArrayList<>();
        this.hasChecked = false;
        this.impactFactor = 0.0;
    }

    /**
     * Returns the impact factor.
     *
     * @return the impact factor
     */
    public double getImpactFactor() {
        return this.impactFactor;
    }

    /**
     * Sets the impact factor.
     *
     * @param factor the impact factor
     */
    public void setImpactFafctor(double factor) {
        this.impactFactor = factor;
    }

    /**
     * Returns the parent task sets.
     *
     * @return the parent task sets
     */
    public List<TaskSet> getParentList() {
        return this.parentList;
    }

    /**
     * Returns the child task sets.
     *
     * @return the child task sets
     */
    public List<TaskSet> getChildList() {
        return this.childList;
    }

    /**
     * Returns the logical tasks in this set.
     *
     * @return the list of logical tasks
     */
    public List<Task> getTaskList() {
        return this.taskList;
    }

    /**
     * Adds one task to this set.
     *
     * @param task the task to add
     */
    public void addTask(Task task) {
        this.taskList.add(task);
    }

    /**
     * Adds a batch of tasks to this set.
     *
     * @param list the tasks to add
     */
    public void addTask(List<Task> list) {
        this.taskList.addAll(list);
    }

    /**
     * Returns the job length of this set, that is, the sum of all task lengths.
     *
     * @return the job length
     */
    public long getJobRuntime() {
        long runtime = 0;
        for (Task task : taskList) {
            runtime += task.getCloudletLength();
        }
        return runtime;
    }
}
