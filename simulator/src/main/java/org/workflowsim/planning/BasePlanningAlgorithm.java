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

public abstract class BasePlanningAlgorithm implements PlanningAlgorithmInterface {

    private List<Task> tasktList;

    private List<? extends Vm> vmList;

    private List<? extends Datacenter> datacenterList;

    public BasePlanningAlgorithm() {}

    @Override
    public void setTaskList(List list) {
        this.tasktList = list;
    }

    @Override
    public void setVmList(List list) {
        this.vmList = new ArrayList(list);
    }

    @Override
    public List<Task> getTaskList() {
        return this.tasktList;
    }

    @Override
    public List getVmList() {
        return this.vmList;
    }

    public List getDatacenterList() {
        return this.datacenterList;
    }

    public void setDatacenterList(List list) {
        this.datacenterList = list;
    }

    public abstract void run() throws Exception;
}
