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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.Task;

/**
 * Block clustering strategy: collects tasks along both same-level and single-branch directions.
 *
 * <p>Tasks are first grouped by DAG depth and then formed into jobs according to
 * {@code clusters.num} or {@code clusters.size}; tasks along a unique parent-child chain are
 * collected together.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BlockClustering extends BasicClustering {

    /**
     * Number of clustering jobs expected per DAG level.
     */
    private final int clusterNum;
    /**
     * Number of tasks expected in each clustering job.
     */
    private final int clusterSize;
    /**
     * Mapping recording whether a task has already been visited.
     */
    private final Map<Integer, Boolean> mHasChecked;
    /**
     * Mapping holding the tasks of each DAG depth.
     */
    private final Map<Integer, List> mDepth2Task;

    /**
     * Creates a block clusterer.
     *
     * @param cNum number of clustering jobs per level ({@code clusters.num})
     * @param cSize number of tasks per clustering job ({@code clusters.size})
     */
    public BlockClustering(int cNum, int cSize) {
        super();
        clusterNum = cNum;
        clusterSize = cSize;
        this.mHasChecked = new HashMap<>();
        this.mDepth2Task = new HashMap<>();
    }

    /**
     * Marks a task as visited.
     *
     * @param index task ID
     */
    private void setCheck(int index) {
        if (mHasChecked.containsKey(index)) {
            mHasChecked.remove(index);
        }
        mHasChecked.put(index, true);
    }

    /**
     * Tests whether a task has already been visited.
     *
     * @param index task ID
     * @return whether the task has already been visited
     */
    private boolean getCheck(int index) {
        if (mHasChecked.containsKey(index)) {
            return mHasChecked.get(index);
        }
        return false;
    }

    /**
     * Executes block clustering with the configured parameters and rebuilds job dependencies
     * and clustering delays.
     */
    @Override
    public void run() {

        // First merge the tasks by DAG depth.
        if (clusterNum > 0 || clusterSize > 0) {
            for (Task task : getTaskList()) {
                int depth = task.getDepth();
                if (!mDepth2Task.containsKey(depth)) {
                    mDepth2Task.put(depth, new ArrayList<>());
                }
                List list = mDepth2Task.get(depth);
                if (!list.contains(task)) {
                    list.add(task);
                }
            }
        }

        if (clusterNum > 0) {
            bundleClustering();
        } else if (clusterSize > 0) {
            collapseClustering();
        }

        mHasChecked.clear();
        super.clean();

        updateDependencies();
        addClustDelay();
    }

    /**
     * Collects, starting from the seed tasks, the candidate tasks that can be merged along a
     * unique dependency chain.
     *
     * @param taskList seed tasks
     * @return candidate tasks that can be merged
     */
    private List searchList(List<Task> taskList) {
        List<Task> sucList = new ArrayList<>();
        for (Task task : taskList) {
            if (!getCheck(task.getCloudletId())) {
                setCheck(task.getCloudletId());
                sucList.add(task);
                // Continue collecting successors only along a unique parent-child relation.
                Task node = task;
                while (node != null) {
                    if (node.getChildList().size() == 1) {
                        Task child = node.getChildList().get(0);
                        if (!getCheck(child.getCloudletId())
                                && child.getParentList().size() == 1) {
                            setCheck(child.getCloudletId());
                            sucList.add(child);
                            node = child;
                        } else {
                            node = null;
                        }
                    } else {
                        node = null;
                    }
                }
            }
        }
        return sucList;
    }

    /**
     * Merges the tasks of each level into a fixed number of jobs.
     */
    private void bundleClustering() {

        for (Map.Entry<Integer, List> pairs : mDepth2Task.entrySet()) {
            List list = pairs.getValue();
            int num = list.size();
            int avg_a = num / this.clusterNum;
            int avg_b = avg_a;
            if (avg_a * this.clusterNum < num) {
                avg_b++;
            }

            int mid = num - this.clusterNum * avg_a;
            if (avg_a <= 0) {
                avg_a = 1;
            }
            if (avg_b <= 0) {
                avg_b = 1;
            }
            int start = 0, end = -1;
            for (int i = 0; i < this.clusterNum; i++) {
                start = end + 1;
                if (i < mid) {
                    // Use the larger group size to cover the remainder.
                    end = start + avg_b - 1;
                } else {
                    // Use the regular group size.
                    end = start + avg_a - 1;
                }

                if (end >= num) {
                    end = num - 1;
                }
                if (end < start) {
                    break;
                }

                addTasks2Job(searchList(list.subList(start, end + 1)));
            }
        }
    }

    /**
     * Merges a fixed number of tasks into a single job.
     */
    private void collapseClustering() {
        for (Map.Entry<Integer, List> pairs : mDepth2Task.entrySet()) {
            List list = pairs.getValue();
            int num = list.size();
            int avg = this.clusterSize;

            int start = 0;
            int end = 0;
            int i = 0;
            do {
                start = i * avg;
                end = start + avg - 1;
                i++;
                if (end >= num) {
                    end = num - 1;
                }
                addTasks2Job(searchList(list.subList(start, end + 1)));
            } while (end < num - 1);
        }
    }
}
