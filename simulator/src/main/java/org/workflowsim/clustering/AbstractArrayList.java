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
package org.workflowsim.clustering;

import java.util.ArrayList;

/**
 * Container for a task list used by the clustering process, together with its level
 * information.
 *
 * <p>Unlike a plain {@link ArrayList}, this type additionally stores the DAG depth shared
 * by the tasks of the batch, which level-based clustering algorithms use for grouping.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class AbstractArrayList {

    /**
     * Task list of the current level.
     */
    private final ArrayList taskList;
    /**
     * DAG depth of the current level.
     */
    private final int depth;

    /**
     * Creates a container for a task list and its DAG depth.
     *
     * @param taskList the task list
     * @param depth the DAG depth at which these tasks reside
     */
    public AbstractArrayList(ArrayList taskList, int depth) {
        this.taskList = taskList;
        this.hasChecked = false;
        this.depth = depth;
    }

    /**
     * Returns the task list.
     *
     * @return the task list
     */
    public ArrayList getArrayList() {
        return this.taskList;
    }

    /**
     * Returns the DAG depth at which these tasks reside.
     *
     * @return the DAG depth
     */
    public int getDepth() {
        return this.depth;
    }
    /**
     * Already-checked flag used by the clustering traversal.
     */
    public boolean hasChecked;
}
