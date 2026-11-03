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

/**
 * The WorkflowSim virtual machine (VM) model.
 *
 * <p>Beyond the CloudSim {@link Vm} resource attributes, this class keeps the idle/busy state
 * and the resource-cost parameters that workflow scheduling needs. It does not model
 * host-level CPU, network or storage contention itself.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class CondorVM extends Vm {

    /**
     * VM status; only the IDLE, READY (currently reserved) or BUSY tags of
     * {@link WorkflowSimTags} are legal here.
     */
    private int state;

    /*
     * BAT-D2 (2026-10-29): the DVFS frequency-tier table (P-state table) of this VM.
     *
     * <p>Isomorphic to {@link org.workflowsim.platform.PlatformProfile.VmSpec#getFrequencyTiers()}:
     * an empty table means v1 single-tier mode (no DVFS semantics); a non-empty table is ordered
     * by descending MIPS with the first tier as the default tier. It is injected once at VM
     * assembly time by {@link org.workflowsim.platform.PlatformFactory} and is read-only
     * afterwards -- the table lives on the VM because a P-state table is a hardware property, so
     * the dispatch path (WorkflowScheduler) can make tier decisions without looking the platform
     * profile back up.</p>
     */
    private List<org.workflowsim.platform.PlatformProfile.FrequencyTier> frequencyTiers =
            java.util.Collections.emptyList();

    /**
     * BAT-D2: installs the DVFS frequency-tier table of this VM (called once at assembly time,
     * read-only afterwards).
     *
     * @param tiers platform-declared frequency tiers; null or empty keeps v1 mode
     */
    public void setFrequencyTiers(final List<org.workflowsim.platform.PlatformProfile.FrequencyTier> tiers) {
        this.frequencyTiers = tiers == null || tiers.isEmpty()
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tiers));
    }

    /**
     * BAT-D2: @return the DVFS frequency-tier table of this VM (immutable; empty = v1
     * single-tier mode)
     */
    public List<org.workflowsim.platform.PlatformProfile.FrequencyTier> getFrequencyTiers() {
        return this.frequencyTiers;
    }

    /** BAT-D2: @return whether this VM declares a DVFS frequency-tier table */
    public boolean hasFrequencyTiers() {
        return this.frequencyTiers != null && !this.frequencyTiers.isEmpty();
    }

    /** Cost per unit of memory. */
    private double costPerMem = 0.0;

    /** Cost per unit of bandwidth. */
    private double costPerBW = 0.0;

    /** Cost per unit of storage. */
    private double costPerStorage = 0.0;

    /** Cost per unit of CPU. */
    private double cost = 0.0;

    /**
     * Creates a VM without explicitly configured costs.
     *
     * @param id unique VM id
     * @param userId id of the user that owns the VM
     * @param mips processing capacity in million instructions per second
     * @param numberOfPes number of CPU processing elements
     * @param ram memory capacity
     * @param bw bandwidth capacity
     * @param size storage capacity
     * @param vmm name of the virtual machine monitor
     * @param cloudletScheduler Cloudlet scheduling policy
     */
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
        /*
         * Every VM can accept jobs at the start of a session.
         */
        setState(WorkflowSimTags.VM_STATUS_IDLE);
    }

    /**
     * Creates a VM with resource-cost parameters.
     *
     * @param id unique VM id
     * @param userId id of the user that owns the VM
     * @param mips processing capacity in million instructions per second
     * @param numberOfPes number of CPU processing elements
     * @param ram memory capacity
     * @param bw bandwidth capacity
     * @param size storage capacity
     * @param vmm name of the virtual machine monitor
     * @param cost cost per CPU unit
     * @param costPerMem cost per memory unit
     * @param costPerStorage cost per storage unit
     * @param costPerBW cost per bandwidth unit
     * @param cloudletScheduler Cloudlet scheduling policy
     */
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

    /** @return cost per unit of CPU */
    public double getCost() {
        return this.cost;
    }

    /** @return cost per unit of bandwidth */
    public double getCostPerBW() {
        return this.costPerBW;
    }

    /** @return cost per unit of storage */
    public double getCostPerStorage() {
        return this.costPerStorage;
    }

    /** @return cost per unit of memory */
    public double getCostPerMem() {
        return this.costPerMem;
    }

    /**
     * Sets the VM status.
     *
     * @param tag VM status tag defined in {@link WorkflowSimTags}
     */
    public final void setState(int tag) {
        this.state = tag;
    }

    /** @return current VM status tag */
    public final int getState() {
        return this.state;
    }
}
