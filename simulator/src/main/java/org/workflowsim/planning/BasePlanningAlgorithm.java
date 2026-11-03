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

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.Task;

/**
 * Planner base class that holds the Task, VM and datacenter collections to be mapped.
 *
 * <p>Concrete planning policies should extend this class; this class produces neither a
 * mapping nor an execution order. The output semantics of a planner are defined by the
 * concrete implementation: it may be only a Task-to-VM mapping, or the complete preserved
 * plan of a controlled static DAG.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 17, 2013
 */
public abstract class BasePlanningAlgorithm implements PlanningAlgorithmInterface {

    /** Task list to be planned. */
    private List<Task> tasktList;
    /** VM list available during planning. */
    private List<? extends Vm> vmList;

    /** Visible datacenter list. */
    private List<? extends Datacenter> datacenterList;
    /** Create the planner base class. */
    public BasePlanningAlgorithm() {}

    /** Set the Task list to be planned. */
    @Override
    public void setTaskList(List list) {
        this.tasktList = list;
    }

    /** Set the VM list; the list is copied internally to stabilize the candidate set. */
    @Override
    public void setVmList(List list) {
        this.vmList = new ArrayList(list);
    }

    /** Return the Task list to be planned. */
    @Override
    public List<Task> getTaskList() {
        return this.tasktList;
    }

    /** Return the VM list. */
    @Override
    public List getVmList() {
        return this.vmList;
    }

    /** Return the datacenter list. */
    public List getDatacenterList() {
        return this.datacenterList;
    }

    /** Set the datacenter list. */
    public void setDatacenterList(List list) {
        this.datacenterList = list;
    }

    /** Execute one offline planning pass. */
    public abstract void run() throws Exception;
}
