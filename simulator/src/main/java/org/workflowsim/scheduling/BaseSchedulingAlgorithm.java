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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;

public abstract class BaseSchedulingAlgorithm implements SchedulingAlgorithmInterface {

    private List<? extends Cloudlet> cloudletList;

    private List<? extends Vm> vmList;

    private List<Cloudlet> scheduledList;

    private InputReadEstimator inputReadEstimator;

    private MachineReadyTimeEstimator machineReadyTimeEstimator;

    public BaseSchedulingAlgorithm() {
        this.scheduledList = new ArrayList();
    }

    @Override
    public void setCloudletList(List list) {
        this.cloudletList = new ArrayList(list);
    }

    @Override
    public void setVmList(List list) {
        this.vmList = new ArrayList(list);
    }

    @Override
    public List getCloudletList() {
        return this.cloudletList;
    }

    @Override
    public List getVmList() {
        return this.vmList;
    }

    @Override
    public abstract void run() throws Exception;

    @Override
    public List getScheduledList() {
        return this.scheduledList;
    }

    public final void setInputReadEstimator(InputReadEstimator value) {
        this.inputReadEstimator = value;
    }

    public final void setMachineReadyTimeEstimator(MachineReadyTimeEstimator value) {
        this.machineReadyTimeEstimator = value;
    }

    protected final double readySeconds(CondorVM vm) {
        if (vm.getState() == org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE) {
            return 0.0;
        }
        if (machineReadyTimeEstimator == null) {
            throw new IllegalStateException(
                    "VM " + vm.getId() + " is busy but no machine-ready-time estimator is bound to "
                            + getClass().getSimpleName());
        }
        double seconds = machineReadyTimeEstimator.readySeconds(vm);
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalStateException(
                    "Ready time of VM " + vm.getId() + " must be finite and non-negative: " + seconds);
        }
        return seconds;
    }

    protected final double inputReadSeconds(Cloudlet cloudlet) {
        if (inputReadEstimator == null || !(cloudlet instanceof Job)) {
            return 0.0;
        }
        double seconds = inputReadEstimator.inputReadSeconds((Job) cloudlet);
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalStateException("Input-read estimate of Job " + cloudlet.getCloudletId()
                    + " must be finite and non-negative: " + seconds);
        }
        return seconds;
    }

    protected static double envelopeSeconds(Cloudlet cloudlet, CondorVM vm, double inputReadSeconds) {
        long readMi = (long) (vm.getMips() * inputReadSeconds);
        return (cloudlet.getCloudletLength() + readMi) / vm.getMips();
    }

    protected final void validateCloudletCompatibility() {
        for (Object item : getCloudletList()) {
            Cloudlet cloudlet = (Cloudlet) item;
            if (!hasCompatibleVm(cloudlet)) {
                throw new IllegalArgumentException("Ready job " + cloudlet.getCloudletId()
                        + " requires " + cloudlet.getNumberOfPes()
                        + " processing element(s), but no declared VM is compatible");
            }
        }
    }

    protected final boolean isCompatible(Cloudlet cloudlet, CondorVM vm) {
        return cloudlet.getNumberOfPes() <= vm.getNumberOfPes();
    }

    protected final boolean hasCompatibleVm(Cloudlet cloudlet) {
        for (Object item : getVmList()) {
            if (isCompatible(cloudlet, (CondorVM) item)) {
                return true;
            }
        }
        return false;
    }

    protected final List<CondorVM> sortedVmsById() {
        List<CondorVM> result = new ArrayList<CondorVM>();
        for (Object item : getVmList()) {
            result.add((CondorVM) item);
        }
        Collections.sort(result, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return result;
    }
}
