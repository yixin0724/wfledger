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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.Parameters.ClassType;

/**
 * Runtime dispatcher for a static mapping result.
 *
 * <p>It does not reselect the Task-to-VM mapping; it relies on the VM ID that the planner set
 * in advance and checks whether the compute Job is already mapped. Only when
 * {@link StaticSchedulePlan} is non-empty are compute Jobs released in the per-VM order that
 * the planner produced; mapping-only planners and historical planners keep an empty plan and
 * are still dispatched through the engine's ordinary ready/idle lifecycle.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 17, 2013
 */
public class StaticSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private StaticSchedulePlan staticSchedulePlan = StaticSchedulePlan.empty();
    private boolean strictDataflowBindings;
    /** Enable fail-fast bound-compute validation for the explicit online-assignment contract. */
    public void requireDataflowBindings() {
        strictDataflowBindings = true;
    }

    public StaticSchedulingAlgorithm() {
        super();
    }

    public void setStaticSchedulePlan(StaticSchedulePlan value) {
        if (value == null) {
            throw new IllegalArgumentException("Static schedule plan cannot be null");
        }
        this.staticSchedulePlan = value;
    }

    @Override
    public void run() throws Exception {

        Map<Integer, CondorVM> mId2Vm = new HashMap<>();

        for (int i = 0; i < getVmList().size(); i++) {
            CondorVM vm = (CondorVM) getVmList().get(i);
            if (vm != null) {
                mId2Vm.put(vm.getId(), vm);
            }
        }

        List<Integer> vmIds = new ArrayList<Integer>(mId2Vm.keySet());
        Collections.sort(vmIds);
        if (vmIds.isEmpty()) {
            throw new IllegalStateException("Static scheduling requires at least one created VM");
        }
        int fallbackVmId = vmIds.get(0);

        List<Integer> fallbackRewrittenJobIds = applyBindingRules(mId2Vm, fallbackVmId);

        if (!staticSchedulePlan.isEnforcingOrder()) {
            for (Object item : getCloudletList()) {
                scheduleIfIdle((Cloudlet) item, mId2Vm);
            }
            return;
        }

        // Model-generated non-compute Jobs (currently stage-in) are not part of the Task
        // planner's plan and must be dispatched first.
        dispatchInPlannedOrder(vmIds, mId2Vm, fallbackRewrittenJobIds, fallbackVmId);
    }

    /** Binding validation and fallback stage of run(); a verbatim port of the PLAT-15 recording semantics. */
    private List<Integer> applyBindingRules(Map<Integer, CondorVM> mId2Vm, int fallbackVmId) {
        // PLAT-15: record the Jobs whose VM mapping was rewritten by the fallback. When such a
        // Job later fails the strict-order plan check, the error message can point at the true
        // root cause (a missing planner mapping) instead of reporting only the symptom.
        List<Integer> fallbackRewrittenJobIds = new ArrayList<Integer>();
        for (Object item : getCloudletList()) {
            Cloudlet cloudlet = (Cloudlet) item;
            if (strictDataflowBindings) {
                if (!(cloudlet instanceof Job)) {
                    throw new IllegalStateException("Dataflow dispatch requires Job identity");
                }
                Job job = (Job) cloudlet;
                if (job.getClassType() == ClassType.COMPUTE.value) {
                    CondorVM assigned = mId2Vm.get(job.getVmId());
                    if (assigned == null
                            || job.getTaskList().size() != 1
                            || job.getNumberOfPes() > assigned.getNumberOfPes()
                            || job.getTaskList().get(0).getNumberOfPes() > assigned.getNumberOfPes()
                            || job.getTaskList().get(0).getVmId() != job.getVmId()) {
                        throw new IllegalStateException(
                                "Online dataflow compute binding is invalid; STATIC must not remap after transfer");
                    }
                }
            }
            if (cloudlet.getVmId() < 0 || !mId2Vm.containsKey(cloudlet.getVmId())) {
                Log.printLine("Cloudlet " + cloudlet.getCloudletId() + " is not matched."
                        + " It is possible a model-generated stage-in job.");
                cloudlet.setVmId(fallbackVmId);
                fallbackRewrittenJobIds.add(cloudlet.getCloudletId());
            }
        }
        return fallbackRewrittenJobIds;
    }

    /**
     * Strict-order planned dispatch stage of run(); a verbatim port of the PLAT-15 root-cause
     * hint and of the per-VM expected-Job dispatch.
     */
    private void dispatchInPlannedOrder(
            List<Integer> vmIds,
            Map<Integer, CondorVM> mId2Vm,
            List<Integer> fallbackRewrittenJobIds,
            int fallbackVmId) {
        // Model-generated non-compute Jobs (currently stage-in) are not part of the Task
        // planner's plan and must be dispatched first.
        for (Object item : getCloudletList()) {
            Job job = (Job) item;
            if (job.getClassType() != ClassType.COMPUTE.value) {
                scheduleIfIdle(job, mId2Vm);
            } else if (!staticSchedulePlan.isPlanned(job)) {
                // PLAT-15: separate the two root causes -- a planner omission (the Job was
                // first rewritten by the fallback) versus a missing entry in the plan itself --
                // so that the error message does not mislead.
                String rootCauseHint = fallbackRewrittenJobIds.contains(job.getCloudletId())
                        ? " Root cause: the job arrived without a planner VM mapping and was"
                                + " rewritten to fallback VM " + fallbackVmId + "; the planner must"
                                + " map every compute task (or the failure/retry path must preserve"
                                + " the original mapping)."
                        : "";
                throw new IllegalStateException(
                        "Static plan has no entry for compute job " + job.getCloudletId() + "." + rootCauseHint);
            }
        }

        for (Integer vmId : vmIds) {
            CondorVM vm = mId2Vm.get(vmId);
            if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE) {
                continue;
            }
            Integer expectedJobId = staticSchedulePlan.nextExpectedJobId(vmId);
            if (expectedJobId == null) {
                continue;
            }
            Job expected = findReadyJob(expectedJobId.intValue());
            if (expected != null) {
                scheduleIfIdle(expected, mId2Vm);
                staticSchedulePlan.markDispatched(expected);
            }
        }
    }

    private Job findReadyJob(int jobId) {
        for (Object item : getCloudletList()) {
            Job job = (Job) item;
            if (job.getCloudletId() == jobId) {
                return job;
            }
        }
        return null;
    }

    private void scheduleIfIdle(Cloudlet cloudlet, Map<Integer, CondorVM> vms) {
        CondorVM vm = vms.get(cloudlet.getVmId());
        if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
            vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            getScheduledList().add(cloudlet);
            Log.printLine("Schedules " + cloudlet.getCloudletId() + " with " + cloudlet.getCloudletLength() + " to VM "
                    + cloudlet.getVmId());
        }
    }
}
