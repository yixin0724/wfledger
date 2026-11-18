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

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.workflowsim.clustering.TaskSet;

public class VerticalBalancing extends BalancingMethod {

    public VerticalBalancing(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    @Override
    public void run() {
        Collection<TaskSet> sets = getTaskMap().values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
            }

            List<TaskSet> list = set.getChildList();
            if (list.size() == 1) {

                TaskSet child = list.get(0);
                List pList = child.getParentList();
                if (pList.size() == 1) {

                    addTaskSet2TaskSet(set, child);
                }
            }
        }

        cleanTaskSetChecked();
    }
}
