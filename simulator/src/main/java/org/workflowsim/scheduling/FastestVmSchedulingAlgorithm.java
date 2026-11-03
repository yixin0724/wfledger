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

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Fastest-VM: for each task, greedily assign the idle VM with the highest MIPS.
 *
 * <p>It was originally named MCTSchedulingAlgorithm, but its behavior does not match the
 * classical MCT definition of Maheswaran et al. 1999 (for each task, compute the completion
 * time length/mips and assign it to the machine with the smallest completion time). This
 * implementation ignores task length and compares only the VM's single-PE speed
 * {@code getMips()}, so it differs from classical MCT.</p>
 *
 * <p><strong>MIPS metric:</strong> a Job always occupies 1 PE ({@code Task} constructor
 * pesNumber=1), so the actual execution rate depends only on single-PE MIPS. The historical
 * implementation compared {@code getCurrentRequestedTotalMips()} (= mips x PE count), which
 * systematically picks the wrong VM under multi-PE heterogeneous VM configurations (for
 * example 2PE@500 with total MIPS=1000 beats 1PE@800, although the Job actually runs at only
 * 500 MIPS on the former). It is now unified on {@code getMips()}, consistent with the metric
 * of {@link ReadyBatchMCTSchedulingAlgorithm}.</p>
 *
 * <p>For ready-batch MCT see {@link ReadyBatchMCTSchedulingAlgorithm}.</p>
 *
 * <p><strong>Tie and compatibility-check notes (confirmed in review round 2026-09):</strong>
 * this class traverses the raw {@code vmList}, so an equal-MIPS tie depends on the incoming
 * order (it is not sorted by VM ID), and it performs no PE compatibility check -- unlike the
 * maintained-track FCFS/ReadyBatch family ({@code sortedVmsById} + compatibility check). With
 * Jobs fixed at 1 PE and VMs always at 1 PE or more the compatibility check is unreachable;
 * the tie-order difference affects only the edge case of heterogeneous equal-speed VMs, and
 * this class is outside the standard research entry point, so it is not fixed.</p>
 *
 * <p><strong>Compatibility boundary:</strong> this class is kept for the historical direct
 * API only; the standard research entry point should use
 * {@link ReadyBatchMCTSchedulingAlgorithm}, and its results must not be called classical MCT
 * evidence.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @see ReadyBatchMCTSchedulingAlgorithm
 */
public class FastestVmSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    public FastestVmSchedulingAlgorithm() {
        super();
    }

    @Override
    public void run() {
        int size = getCloudletList().size();

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);
            int vmSize = getVmList().size();
            CondorVM firstIdleVm = null;

            for (int j = 0; j < vmSize; j++) {
                CondorVM vm = (CondorVM) getVmList().get(j);
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    firstIdleVm = vm;
                    break;
                }
            }
            if (firstIdleVm == null) {
                break;
            }

            for (int j = 0; j < vmSize; j++) {
                CondorVM vm = (CondorVM) getVmList().get(j);
                if ((vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) && (vm.getMips() > firstIdleVm.getMips())) {
                    firstIdleVm = vm;
                }
            }
            firstIdleVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(firstIdleVm.getId());
            getScheduledList().add(cloudlet);
            Log.printLine("Schedules " + cloudlet.getCloudletId() + " with "
                    + cloudlet.getCloudletLength() + " to VM " + firstIdleVm.getId()
                    + " with " + firstIdleVm.getMips() + " MIPS/PE");
        }
    }
}
