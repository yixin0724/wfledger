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
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * SPT-Fastest-Idle: picks the shortest Job (Shortest Processing Time) and assigns it to the
 * fastest idle VM.
 *
 * <p>It was originally named MinMinSchedulingAlgorithm, but its behavior does not match the
 * classical Min-Min definition of Braun et al. 2001 (first compute the ECT of every task on
 * every machine, then take the global minimum among the candidates). This implementation is
 * an online SPT variant, distinct from classical Min-Min.</p>
 *
 * <p>For ready-batch Min-Min see {@link ReadyBatchMinMinSchedulingAlgorithm}.</p>
 *
 * <p><strong>MIPS metric:</strong> a Job always occupies 1 PE, so "fastest VM" compares the
 * single-PE speed {@code getMips()} (the historical implementation used
 * {@code getCurrentRequestedTotalMips()}, which systematically picks the wrong VM under
 * multi-PE heterogeneous configurations); this is consistent with the metric of the
 * ReadyBatch family.</p>
 *
 * <p><strong>Sort key and tie notes (confirmed in review round 2026-09):</strong> the task
 * sort key is MI ({@code getCloudletLength()}) rather than the estimated execution time,
 * which differs slightly from textbook SPT on heterogeneous VMs; "fastest idle VM" traverses
 * the raw {@code vmList}, so equal-MIPS ties depend on the incoming order. With Jobs fixed at
 * 1 PE the difference is unreachable, and this class is outside the standard research entry
 * point, so it is not fixed.</p>
 *
 * <p><strong>Compatibility boundary:</strong> it serves historical direct API exploration
 * only; the standard research entry point does not accept the historical {@code MINMIN}
 * label, and its results must not be presented as classical Min-Min.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @see ReadyBatchMinMinSchedulingAlgorithm
 */
public class SptFastestIdleSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    public SptFastestIdleSchedulingAlgorithm() {
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
            int minIndex = 0;
            Cloudlet minCloudlet = null;
            for (int j = 0; j < size; j++) {
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
                if (!hasChecked.get(j)) {
                    minCloudlet = cloudlet;
                    minIndex = j;
                    break;
                }
            }
            if (minCloudlet == null) {
                break;
            }

            for (int j = 0; j < size; j++) {
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
                if (hasChecked.get(j)) {
                    continue;
                }
                long length = cloudlet.getCloudletLength();
                if (length < minCloudlet.getCloudletLength()) {
                    minCloudlet = cloudlet;
                    minIndex = j;
                }
            }
            hasChecked.set(minIndex, true);

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
            minCloudlet.setVmId(firstIdleVm.getId());
            getScheduledList().add(minCloudlet);
        }
    }
}
