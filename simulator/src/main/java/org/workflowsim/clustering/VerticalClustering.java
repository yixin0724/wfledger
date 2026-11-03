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
import java.util.Stack;

import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/**
 * Vertical clustering strategy: merges tasks along the same dependency pipeline.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class VerticalClustering extends BasicClustering {

    /* Maximum DAG depth allowed for exploration. */
    private final int mDepth;
    /* Task traversal checkpoints. */
    private final Map<Integer, Boolean> mHasChecked;

    /**
     * Creates a vertical clusterer.
     *
     * @param depth maximum DAG depth allowed for exploration
     */
    public VerticalClustering(int depth) {
        super();
        this.mDepth = depth;
        this.mHasChecked = new HashMap<>();
    }

    /**
     * Marks a task as traversed.
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
     * Tests whether a task has been traversed.
     *
     * @param index task ID
     * @return whether the task has been traversed
     */
    private boolean getCheck(int index) {

        if (mHasChecked.containsKey(index)) {
            return mHasChecked.get(index);
        }
        return false;
    }

    /**
     * Traverses the unique dependency chains of the DAG and produces vertical clustering
     * jobs.
     */
    @Override
    public void run() {
        if (mDepth > 0) {
            /**
             * Historical Montage inputs may contain duplicate edges; remove them up front
             * following the existing reducer rules.
             */
            if (Parameters.getReduceMethod().equals("montage")) {
                removeDuplicateMontage();
            }
            Task root = super.addRoot();
            Task node;
            List<Task> taskList = new ArrayList<>();
            Stack<Task> stack = new Stack<>();
            stack.push(root);
            while (!stack.empty()) {
                node = (Task) stack.pop();
                if (!getCheck(node.getCloudletId())) {
                    setCheck(node.getCloudletId());

                    int pNum = node.getParentList().size();
                    int cNum = node.getChildList().size();

                    for (Task cNode : node.getChildList()) {
                        stack.push(cNode);
                    }

                    if (pNum == 0) {
                        // The virtual root node does not participate in clustering.
                    } else if (pNum > 1) {
                        if (cNum > 1 || cNum == 0) {

                            if (!taskList.isEmpty()) {
                                addTasks2Job(taskList);
                                taskList.clear();
                            }
                            taskList.add(node);
                            addTasks2Job(taskList);
                            taskList.clear();

                        } else { // cNum == 1
                            // Truncate at a branch boundary and start a new clustering chain.

                            if (!taskList.isEmpty()) {
                                addTasks2Job(taskList);
                                taskList.clear();
                            }
                            if (!taskList.contains(node)) {
                                taskList.add(node);
                            }
                        }
                    } else { // pNum == 1
                        if (cNum > 1 || cNum == 0) {
                            // A single-parent branch ends the current clustering chain here.
                            taskList.add(node);
                            addTasks2Job(taskList);
                            taskList.clear();
                        } else {
                            // A single-parent single-child node joins the current clustering chain.
                            if (!taskList.contains(node)) {
                                taskList.add(node);
                            }
                        }
                    }

                } else {
                    if (!taskList.isEmpty()) {
                        addTasks2Job(taskList);
                        taskList.clear();
                    }
                }
            }
        }
        mHasChecked.clear();
        super.clean();
        updateDependencies();
        addClustDelay();
    }

    /**
     * Removes duplicate dependency edges of Montage workflows following the historical
     * {@code reducer.method=montage} rule.
     */
    public void removeDuplicateMontage() {

        List jobList = this.getTaskList();
        for (Object jobList1 : jobList) {
            Task node = (Task) jobList1;
            String name = node.getType();
            switch (name) {
                case "mBackground":
                    // Remove duplicate parent edges from mBackground to mProjectPP.

                    for (int j = 0; j < node.getParentList().size(); j++) {

                        Task parent = (Task) node.getParentList().get(j);
                        if (parent.getType().equals("mProjectPP")) {
                            j--;
                            node.getParentList().remove(parent);
                            parent.getChildList().remove(node);
                        }
                    }
                    break;
                case "mAdd":
                    for (int j = 0; j < node.getParentList().size(); j++) {

                        Task parent = (Task) node.getParentList().get(j);
                        String pName = parent.getType();
                        if (pName.equals("mBackground") || pName.equals("mShrink")) {
                            j--;
                            node.getParentList().remove(parent);
                            parent.getChildList().remove(node);
                        }
                    }
                    break;
                default:
                    // Other task types have no deduplication rule (Montage-specific legacy
                    // logic) and are deliberately skipped.
                    break;
            }
        }
    }
}
