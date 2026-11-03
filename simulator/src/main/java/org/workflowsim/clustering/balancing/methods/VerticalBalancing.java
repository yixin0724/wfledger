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

/**
 * Vertical merge method within the balanced clustering framework.
 *
 * <p>It merges the parent set into its child set when the task set has a unique child
 * set, so that it can be combined with the other balancing methods on the same task set
 * representation.</p>
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class VerticalBalancing extends BalancingMethod {

    /**
     * Create the vertical balancing method.
     *
     * @param levelMap task sets organized by level
     * @param taskMap mapping from logical tasks to task sets
     * @param clusterNum target number of clustering jobs per level
     */
    public VerticalBalancing(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * Merge the task sets that have a unique child set.
     */
    @Override
    public void run() {
        Collection<TaskSet> sets = getTaskMap().values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
            }
            // Check whether the current set can be safely merged into its unique child set.
            List<TaskSet> list = set.getChildList();
            if (list.size() == 1) {
                //
                TaskSet child = list.get(0);
                List pList = child.getParentList();
                if (pList.size() == 1) {
                    // Merge the parent set into the child set; the direction must not be reversed.
                    addTaskSet2TaskSet(set, child);
                }
            }
        }
        // Restore the traversal flags before the method exits.
        cleanTaskSetChecked();
    }
}
