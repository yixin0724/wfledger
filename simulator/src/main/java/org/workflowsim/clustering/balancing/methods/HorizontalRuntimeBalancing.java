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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;
import org.workflowsim.utils.SimulationRandom;

public class HorizontalRuntimeBalancing extends BalancingMethod {

    public HorizontalRuntimeBalancing(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        for (List<TaskSet> taskList : map.values()) {

            Collections.shuffle(taskList, SimulationRandom.newJavaRandom("clustering.balancing.runtime"));
            Collections.shuffle(taskList, SimulationRandom.newJavaRandom("clustering.balancing.runtime"));

            if (taskList.size() > getClusterNum()) {
                List<TaskSet> jobList = new ArrayList<>();
                for (int i = 0; i < getClusterNum(); i++) {
                    jobList.add(new TaskSet());
                }
                sortListDecreasing(taskList);
                for (TaskSet set : taskList) {

                    sortListIncreasing(jobList);
                    TaskSet job = (TaskSet) jobList.get(0);
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

    private void sortListIncreasing(List<TaskSet> taskList) {
        Collections.sort(taskList, RUNTIME_INCREASING);
    }

    private void sortListDecreasing(List<TaskSet> taskList) {
        Collections.sort(taskList, RUNTIME_DECREASING);
    }

    static final Comparator<TaskSet> RUNTIME_INCREASING = new Comparator<TaskSet>() {
        @Override
        public int compare(TaskSet t1, TaskSet t2) {

            return Long.compare(t1.getJobRuntime(), t2.getJobRuntime());
        }
    };

    static final Comparator<TaskSet> RUNTIME_DECREASING = new Comparator<TaskSet>() {
        @Override
        public int compare(TaskSet t1, TaskSet t2) {

            return Long.compare(t2.getJobRuntime(), t1.getJobRuntime());
        }
    };
}
