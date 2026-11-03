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
package org.workflowsim.planning;

import java.util.List;

/**
 * Minimal interface for a static planning policy.
 *
 * <p>The interface specifies only the input Task/VM collections and the planning entry point;
 * it does not by itself promise a complete DAG execution order, network calibration results,
 * or a real-platform replay.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 18, 2013
 */
public interface PlanningAlgorithmInterface {

    /** Set the Task list to be planned. */
    public void setTaskList(List list);

    /** Set the VM list. */
    public void setVmList(List list);

    /** Return the Task list to be planned. */
    public List getTaskList();

    /** Return the VM list. */
    public List getVmList();

    /** Execute one offline planning run. */
    public void run() throws Exception;
}
