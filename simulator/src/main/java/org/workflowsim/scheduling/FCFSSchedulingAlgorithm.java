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
package org.workflowsim.scheduling;

import java.util.Iterator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * First-come, first-served (FCFS) online scheduler.
 *
 * <p>Scans the ready Jobs in the order they are supplied and, for each Job, selects the first
 * idle compatible VM found in ascending VM ID order. The round ends as soon as no VM is
 * available, so it builds neither a prediction of nor a reservation for future VM idle time.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FCFSSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    /** Perform one FCFS dispatch pass in the current ready order. */
    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();

        for (Iterator it = getCloudletList().iterator(); it.hasNext(); ) {
            Cloudlet cloudlet = (Cloudlet) it.next();
            boolean stillHasVm = false;
            for (CondorVM vm : vms) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    stillHasVm = true;
                    vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
                    cloudlet.setVmId(vm.getId());
                    getScheduledList().add(cloudlet);
                    break;
                }
            }
            // No idle compatible VM is available now; the remaining Jobs are left for later
            // scheduling events.
            if (!stillHasVm) {
                break;
            }
        }
    }
}
