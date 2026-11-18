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

public class HorizontalRandomClustering extends BalancingMethod {

    public HorizontalRandomClustering(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        for (List<TaskSet> taskList : map.values()) {

            Collections.shuffle(taskList, SimulationRandom.newJavaRandom("clustering.balancing.random"));
            Collections.shuffle(taskList, SimulationRandom.newJavaRandom("clustering.balancing.random"));

            if (taskList.size() > getClusterNum()) {
                List<TaskSet> jobList = new ArrayList<>();
                for (int i = 0; i < getClusterNum(); i++) {
                    jobList.add(new TaskSet());
                }
                int index = 0;
                for (TaskSet set : taskList) {

                    TaskSet job = (TaskSet) jobList.get(index);
                    index++;
                    if (index == getClusterNum()) {
                        index = 0;
                    }
                    job.addTask(set.getTaskList());

                    for (Task task : set.getTaskList()) {
                        getTaskMap().put(task, job);
                    }
                }
                taskList.clear();
            } else {

            }
        }
    }
}
