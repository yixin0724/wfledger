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

import java.util.List;

import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;

/**
 * Common interface for all task clustering policies.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public interface ClusteringInterface {

    /**
     * Set the list of logical tasks to cluster.
     *
     * @param list the tasks to cluster
     */
    public void setTaskList(List<Task> list);

    /**
     * Return the Jobs produced by clustering.
     *
     * @return the clustered Job list
     */
    public List<Job> getJobList();

    /**
     * Return the current logical task list.
     *
     * @return the logical task list
     */
    public List<Task> getTaskList();

    /**
     * Run clustering and create the Jobs together with their dependencies.
     */
    public void run();

    /**
     * Return the task file references collected during clustering.
     *
     * @return the task file list
     */
    public List<FileItem> getTaskFiles();
}
