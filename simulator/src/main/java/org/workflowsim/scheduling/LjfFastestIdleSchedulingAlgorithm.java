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

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * LJF-Fastest-Idle: picks the longest Job (Longest Job First) and assigns it to the fastest
 * idle VM.
 *
 * <p>It was originally named MaxMinSchedulingAlgorithm, but its behavior does not match the
 * classical Max-Min definition of Braun et al. 2001 (take the global maximum among the minimum
 * ECTs of all tasks). This implementation is an LJF variant, distinct from classical
 * Max-Min.</p>
 *
 * <p>For ready-batch Max-Min see {@link ReadyBatchMaxMinSchedulingAlgorithm}.</p>
 *
 * <p><strong>MIPS metric:</strong> a Job always occupies 1 PE, so "fastest VM" compares the
 * single-PE speed {@code getMips()} (the historical implementation used
 * {@code getCurrentRequestedTotalMips()}, which systematically picks the wrong VM under
 * multi-PE heterogeneous configurations); this is consistent with the metric of the
 * ReadyBatch family.</p>
 *
 * <p><strong>Sort key and tie notes (confirmed in review round 2026-09):</strong> the task
 * sort key is MI ({@code getCloudletLength()}) rather than the estimated execution time,
 * which differs slightly from textbook LJF on heterogeneous VMs; "fastest idle VM" traverses
 * the raw {@code vmList}, so equal-MIPS ties depend on the incoming order. With Jobs fixed at
 * 1 PE the difference is unreachable, and this class is outside the standard research entry
 * point, so it is not fixed.</p>
 *
 * <p><strong>Compatibility boundary:</strong> it serves historical direct API exploration
 * only; the standard research entry point does not accept the historical {@code MAXMIN}
 * label, and its results must not be presented as classical Max-Min.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @see ReadyBatchMaxMinSchedulingAlgorithm
 */
public class LjfFastestIdleSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    public LjfFastestIdleSchedulingAlgorithm() {
        super();
    }

    private final List<Boolean> hasChecked = new ArrayList<>();

    @Override
    public void run() {
        int size = getCloudletList().size();
        hasChecked.clear();
        for (int t = 0; t < size; t++) {
            hasChecked.add(false);
        }
        for (int i = 0; i < size; i++) {
            int maxIndex = 0;
            Cloudlet maxCloudlet = null;
            for (int j = 0; j < size; j++) {
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
                if (!hasChecked.get(j)) {
                    maxCloudlet = cloudlet;
                    maxIndex = j;
                    break;
                }
            }
            if (maxCloudlet == null) {
                break;
            }

            for (int j = 0; j < size; j++) {
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
                if (hasChecked.get(j)) {
                    continue;
                }
                long length = cloudlet.getCloudletLength();
                if (length > maxCloudlet.getCloudletLength()) {
                    maxCloudlet = cloudlet;
                    maxIndex = j;
                }
            }
            hasChecked.set(maxIndex, true);

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
                if ((vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) && vm.getMips() > firstIdleVm.getMips()) {
                    firstIdleVm = vm;
                }
            }
            firstIdleVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            maxCloudlet.setVmId(firstIdleVm.getId());
            getScheduledList().add(maxCloudlet);
            Log.printLine("Schedules " + maxCloudlet.getCloudletId() + " with "
                    + maxCloudlet.getCloudletLength() + " to VM " + firstIdleVm.getId()
                    + " with " + firstIdleVm.getMips() + " MIPS/PE");
        }
    }
}
