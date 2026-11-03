/*
 *
 *  Copyright 2012-2013 University Of Southern California
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;
import org.workflowsim.utils.SimulationRandom;

/**
 * Random horizontal clustering method.
 *
 * <p>After a controlled random permutation, this method distributes the task sets of the same
 * level across the target cluster jobs in round-robin order.</p>
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class HorizontalRandomClustering extends BalancingMethod {

    /**
     * Create a random horizontal clustering method.
     *
     * @param levelMap task sets organized by level
     * @param taskMap mapping from logical task to task set
     * @param clusterNum number of target cluster jobs per level
     */
    public HorizontalRandomClustering(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * Permute the candidate set of each level, then assign tasks by the round-robin rule.
     */
    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        for (List<TaskSet> taskList : map.values()) {
            // Break the input order with a random stream derived from the session seed.
            Collections.shuffle(taskList, SimulationRandom.newJavaRandom("clustering.balancing.random"));
            Collections.shuffle(taskList, SimulationRandom.newJavaRandom("clustering.balancing.random"));

            if (taskList.size() > getClusterNum()) {
                List<TaskSet> jobList = new ArrayList<>();
                for (int i = 0; i < getClusterNum(); i++) {
                    jobList.add(new TaskSet());
                }
                int index = 0;
                for (TaskSet set : taskList) {
                    // Select the next target task set in round-robin order.
                    TaskSet job = (TaskSet) jobList.get(index);
                    index++;
                    if (index == getClusterNum()) {
                        index = 0;
                    }
                    job.addTask(set.getTaskList());
                    // Update the owning task set mapping of each logical task.
                    for (Task task : set.getTaskList()) {
                        getTaskMap().put(task, job); // dependencies are rebuilt centrally by the caller
                    }
                }
                taskList.clear();
            } else {
                // No merging is needed when the candidate count does not exceed the target count.
            }
        }
    }
}
