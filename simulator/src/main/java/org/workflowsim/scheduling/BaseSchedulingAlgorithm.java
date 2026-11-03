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

/**
 * Scheduler base class; it holds the ready Jobs, the VMs, and the Jobs dispatched in the
 * current round.
 *
 * <p>A concrete policy should extend this class, but it is not a directly usable scheduling
 * algorithm. The maintained track rejects Jobs without enough PEs through
 * {@link #validateCloudletCompatibility()} and provides a stable VM-ID order for equal-cost
 * VM selection through {@link #sortedVmsById()}.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public abstract class BaseSchedulingAlgorithm implements SchedulingAlgorithmInterface {

    /** Jobs that are ready at the current event and wait to be handled by this policy. */
    private List<? extends Cloudlet> cloudletList;
    /** Currently visible VM list. */
    private List<? extends Vm> vmList;
    /** Jobs dispatched by the current {@link #run()}. */
    private List<Cloudlet> scheduledList;

    /** Initializes an empty dispatch result for the current round. */
    public BaseSchedulingAlgorithm() {
        this.scheduledList = new ArrayList();
    }

    /**
     * Sets the current ready Job list; the list is copied internally to stabilize the
     * candidate set.
     *
     * <p><strong>Defensive copy:</strong> the caller (the scheduler) passes the real reference
     * of its internal ready queue. Some historical algorithms (for example
     * {@link FirstFitIdleSchedulingAlgorithm}) sort {@link #getCloudletList()} inside
     * {@link #run()}; without the copy, that sorting residue would leak back into the
     * scheduler queue and break the arrival order across scheduling cycles (FCFS semantics).
     * The copy semantics match those of {@link #setVmList(List)}.
     */
    @Override
    public void setCloudletList(List list) {
        this.cloudletList = new ArrayList(list);
    }

    /** Sets the current VM list; the list is copied internally to stabilize the candidate set. */
    @Override
    public void setVmList(List list) {
        this.vmList = new ArrayList(list);
    }

    /** Returns the current ready Job list. */
    @Override
    public List getCloudletList() {
        return this.cloudletList;
    }

    /** Returns the current VM list. */
    @Override
    public List getVmList() {
        return this.vmList;
    }

    /** Performs one online scheduling update. */
    @Override
    public abstract void run() throws Exception;

    /** Returns the Jobs dispatched in this round. */
    @Override
    public List getScheduledList() {
        return this.scheduledList;
    }

    /** Rejects a ready Job when no declared VM has enough PEs, so it could never execute. */
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

    /** Provides a stable candidate order for equal-cost VM selection: ascending VM ID. */
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
