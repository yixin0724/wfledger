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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.clustering.AbstractArrayList;
import org.workflowsim.clustering.TaskSet;

/**
 * Child-aware horizontal balancing method.
 *
 * <p>When two task sets in the same level share a mergeable parent set, this strategy
 * merges them and propagates the check state to the subsequent levels.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ChildAwareHorizontalClustering extends BalancingMethod {

    /**
     * Creates the child-aware horizontal balancing method.
     *
     * @param levelMap task sets organized by level
     * @param taskMap mapping from logical tasks to task sets
     * @param clusterNum target number of clustered jobs per level
     */
    public ChildAwareHorizontalClustering(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * Runs the child-aware merge starting from the level with the most task sets.
     */
    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        Map<List<TaskSet>, AbstractArrayList> tmpMap = new HashMap();
        for (Map.Entry entry : map.entrySet()) {
            int depth = (Integer) entry.getKey();
            ArrayList<TaskSet> list = (ArrayList) entry.getValue();
            AbstractArrayList abList = new AbstractArrayList(list, depth);
            tmpMap.put(list, abList);
        }
        List<AbstractArrayList> abList = new ArrayList(tmpMap.values());
        sortMap(abList);
        for (AbstractArrayList list : abList) {
            if (!list.hasChecked) {
                boolean hasClustered = CHBcheckLevel(list.getArrayList());
                // Log.printLine("Depth:"+list.getDepth());
                if (hasClustered) {
                    list.hasChecked = true;
                    // Continue checking the subsequent levels to propagate the relation
                    // changes introduced by this merge.
                    int depth = list.getDepth();

                    int i = depth + 1;
                    while (map.containsKey(i)) {
                        List<TaskSet> tsList = map.get(i);
                        CHBcheckLevel(tsList);
                        AbstractArrayList tsAbList = tmpMap.get(tsList);
                        tsAbList.hasChecked = true;
                        i++;
                    }
                }
            }
        }
        // Restore the task-set traversal flags before the method returns.
        cleanTaskSetChecked();
    }

    /**
     * Sorts the level list by descending task-set size.
     *
     * @param list level list to sort
     */
    private void sortMap(List<AbstractArrayList> list) {
        Collections.sort(list, new Comparator<AbstractArrayList>() {
            @Override
            public int compare(AbstractArrayList l1, AbstractArrayList l2) {

                return (int) (l2.getArrayList().size() - l1.getArrayList().size());
            }
        });
    }

    /**
     * Processes the task sets of one level and attempts to merge the candidates that
     * share a parent set.
     *
     * @param taskList task sets to process
     * @return whether any merge was performed
     */
    private boolean CHBcheckLevel(List<TaskSet> taskList) {
        boolean hasClustered = false;
        for (TaskSet setA : taskList) {
            setA.hasChecked = false; // ensure this level starts from a clean traversal state
        }
        for (int i = 0; i < taskList.size(); i++) {
            TaskSet setA = taskList.get(i);
            if (!setA.hasChecked) {
                for (int j = i + 1; j < taskList.size(); j++) {
                    TaskSet setB = taskList.get(j);
                    if (!setB.hasChecked) {

                        TaskSet kid = CHBhasOneParent(setA, setB);
                        if (kid != null) {
                            if (true) { // the historical implementation imposed no additional runtime constraint
                                setA.hasChecked = true; // mark as handled in this round
                                setB.hasChecked = true;
                                addTaskSet2TaskSet(setA, setB);
                                hasClustered = true;
                            }
                        }
                    }
                }
            }
        }
        return hasClustered;
    }

    /**
     * Determines whether two task sets own the same unique child set.
     *
     * @param setA first set to compare
     * @param setB second set to compare
     * @return the shared unique child set; {@code null} when none exists
     */
    private TaskSet CHBhasOnlyChild(TaskSet setA, TaskSet setB) {

        if (setA.getChildList().size() == 1 && setB.getChildList().size() == 1) {
            TaskSet kidA = setA.getChildList().get(0);
            TaskSet kidB = setB.getChildList().get(0);
            if (kidA.equals(kidB)) {
                return kidA;
            }
        }
        return null;
    }

    /**
     * Determines whether two task sets own the same unique parent set.
     *
     * @param setA first set to compare
     * @param setB second set to compare
     * @return the shared unique parent set; {@code null} when none exists
     */
    private TaskSet CHBhasOnlyParent(TaskSet setA, TaskSet setB) {

        if (setA.getParentList().size() == 1 && setB.getParentList().size() == 1) {
            TaskSet kidA = setA.getParentList().get(0);
            TaskSet kidB = setB.getParentList().get(0);
            if (kidA.equals(kidB)) {
                return kidA;
            }
        }
        return null;
    }

    /**
     * Finds any common parent set of two task sets.
     *
     * @param setA first set to compare
     * @param setB second set to compare
     * @return the common parent set; {@code null} when none exists
     */
    private TaskSet CHBhasOneParent(TaskSet setA, TaskSet setB) {
        for (TaskSet parentA : setA.getParentList()) {
            for (TaskSet parentB : setB.getParentList()) {
                if (parentA.equals(parentB)) {
                    return parentA;
                }
            }
        }
        return null;
    }
}
