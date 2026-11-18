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
package org.workflowsim;

import java.util.List;

import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.Vm;

public class CondorVM extends Vm {

    private int state;

    private List<org.workflowsim.platform.PlatformProfile.FrequencyTier> frequencyTiers =
            java.util.Collections.emptyList();

    public void setFrequencyTiers(final List<org.workflowsim.platform.PlatformProfile.FrequencyTier> tiers) {
        this.frequencyTiers = tiers == null || tiers.isEmpty()
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tiers));
    }

    public List<org.workflowsim.platform.PlatformProfile.FrequencyTier> getFrequencyTiers() {
        return this.frequencyTiers;
    }

    public boolean hasFrequencyTiers() {
        return this.frequencyTiers != null && !this.frequencyTiers.isEmpty();
    }

    private double costPerMem = 0.0;

    private double costPerBW = 0.0;

    private double costPerStorage = 0.0;

    private double cost = 0.0;

    public CondorVM(
            int id,
            int userId,
            double mips,
            int numberOfPes,
            int ram,
            long bw,
            long size,
            String vmm,
            CloudletScheduler cloudletScheduler) {
        super(id, userId, mips, numberOfPes, ram, bw, size, vmm, cloudletScheduler);

        setState(WorkflowSimTags.VM_STATUS_IDLE);
    }

    public CondorVM(
            int id,
            int userId,
            double mips,
            int numberOfPes,
            int ram,
            long bw,
            long size,
            String vmm,
            double cost,
            double costPerMem,
            double costPerStorage,
            double costPerBW,
            CloudletScheduler cloudletScheduler) {
        this(id, userId, mips, numberOfPes, ram, bw, size, vmm, cloudletScheduler);
        this.cost = cost;
        this.costPerBW = costPerBW;
        this.costPerMem = costPerMem;
        this.costPerStorage = costPerStorage;
    }

    public double getCost() {
        return this.cost;
    }

    public double getCostPerBW() {
        return this.costPerBW;
    }

    public double getCostPerStorage() {
        return this.costPerStorage;
    }

    public double getCostPerMem() {
        return this.costPerMem;
    }

    public final void setState(int tag) {
        this.state = tag;
    }

    public final int getState() {
        return this.state;
    }
}
