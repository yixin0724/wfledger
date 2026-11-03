/**
 * Copyright 2013-2014 University Of Southern California
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

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * First-Fit-Idle: sort by cloudletId and by vmId, then give each task the first idle VM.
 *
 * <p>It was originally named RoundRobinSchedulingAlgorithm, but the declared vmIndex is dead
 * code, so the actual behavior is not Round-Robin (continuous rotation). This implementation
 * is First-Fit-Idle.</p>
 *
 * <p>For ready-batch Round-Robin see {@link ReadyBatchRoundRobinSchedulingAlgorithm}.</p>
 *
 * <p><strong>Compatibility boundary:</strong> this class is retained for historical direct
 * API calls only; the standard {@code SimulationRunner} does not accept its historical label
 * as a research evidence entry point.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date May 12, 2014
 * @see ReadyBatchRoundRobinSchedulingAlgorithm
 */
public class FirstFitIdleSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        int size = getCloudletList().size();
        Collections.sort(getCloudletList(), new CloudletListComparator());
        List vmList = getVmList();
        Collections.sort(vmList, new VmListComparator());
        for (int j = 0; j < size; j++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
            int vmSize = vmList.size();
            CondorVM firstIdleVm = null;
            for (int l = 0; l < vmSize; l++) {
                CondorVM vm = (CondorVM) vmList.get(l);
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    firstIdleVm = vm;
                    break;
                }
            }
            if (firstIdleVm == null) {
                break;
            }
            firstIdleVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(firstIdleVm.getId());
            getScheduledList().add(cloudlet);
        }
    }

    /** Sorts by ascending VM ID. */
    public class VmListComparator implements Comparator<CondorVM> {
        @Override
        public int compare(CondorVM v1, CondorVM v2) {
            return Integer.compare(v1.getId(), v2.getId());
        }
    }

    /** Sorts by ascending Cloudlet ID. */
    public class CloudletListComparator implements Comparator<Cloudlet> {
        @Override
        public int compare(Cloudlet c1, Cloudlet c2) {
            return Integer.compare(c1.getCloudletId(), c2.getCloudletId());
        }
    }
}
