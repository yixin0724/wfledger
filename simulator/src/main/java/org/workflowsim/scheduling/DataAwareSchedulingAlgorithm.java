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

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.ReplicaCatalog;

public class DataAwareSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private boolean sharedModeWarningShown = false;

    public DataAwareSchedulingAlgorithm() {
        super();
    }

    @Override
    public void run() {
        if (ReplicaCatalog.getFileSystem() == ReplicaCatalog.FileSystem.SHARED && !sharedModeWarningShown) {
            sharedModeWarningShown = true;
            Log.printLine("Warning: DataAwareSchedulingAlgorithm requires LOCAL filesystem mode; "
                    + "under SHARED mode replica sites are datacenter names that never match VM ids, "
                    + "so locality scoring is ineffective and dispatch degenerates to first-fit-idle");
        }
        validateCloudletCompatibility();

        int size = getCloudletList().size();

        for (int i = 0; i < size; i++) {

            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);

            CondorVM closestVm = null;
            double minimumNonLocalBytes = Double.MAX_VALUE;
            List<CondorVM> vms = sortedVmsById();
            for (CondorVM vm : vms) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    Job job = (Job) cloudlet;
                    double nonLocalBytes = dataTransferTime(job.getFileList(), cloudlet, vm.getId());
                    if (nonLocalBytes < minimumNonLocalBytes) {
                        minimumNonLocalBytes = nonLocalBytes;
                        closestVm = vm;
                    }
                }
            }

            if (closestVm != null) {
                closestVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
                cloudlet.setVmId(closestVm.getId());
                getScheduledList().add(cloudlet);
            }
        }
    }

    protected double dataTransferTime(List<FileItem> requiredFiles, Cloudlet cl, int vmId) {
        double time = 0.0;

        for (FileItem file : requiredFiles) {

            if (file.isRealInputFile(requiredFiles)) {
                List<String> siteList = ReplicaCatalog.getStorageList(file.getName());

                boolean hasFile = false;
                if (siteList != null) {
                    for (String site : siteList) {
                        if (site.equals(Integer.toString(vmId))) {
                            hasFile = true;
                            break;
                        }
                    }
                }
                if (!hasFile) {
                    time += file.getSize();
                }
            }
        }
        return time;
    }
}
