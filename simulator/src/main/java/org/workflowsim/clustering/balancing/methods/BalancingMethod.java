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

public class BalancingMethod {

    private final Map<Task, TaskSet> taskMap;

    private final Map<Integer, List<TaskSet>> levelMap;

    private final int clusterNum;

    public BalancingMethod(Map levelMap, Map taskMap, int clusterNum) {
        this.taskMap = taskMap;
        this.levelMap = levelMap;
        this.clusterNum = clusterNum;
    }

    public Map<Task, TaskSet> getTaskMap() {
        return this.taskMap;
    }

    public Map<Integer, List<TaskSet>> getLevelMap() {
        return this.levelMap;
    }

    public int getClusterNum() {
        return this.clusterNum;
    }

    public void addTaskSet2TaskSet(TaskSet tail, TaskSet head) {
        head.addTask(tail.getTaskList());
        head.getParentList().remove(tail);

        for (Task task : tail.getTaskList()) {
            getTaskMap().put(task, head);
        }

        head.setImpactFafctor(head.getImpactFactor() + tail.getImpactFactor());
        for (TaskSet taskSet : tail.getParentList()) {
            if (taskSet == head || taskSet == tail) {

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

    public void run() {
        throw (new RuntimeException("Should not use this function"));
    }

    public void cleanTaskSetChecked() {
        Collection sets = getTaskMap().values();
        for (Iterator it = sets.iterator(); it.hasNext(); ) {
            TaskSet set = (TaskSet) it.next();
            set.hasChecked = false;
        }
    }
}
