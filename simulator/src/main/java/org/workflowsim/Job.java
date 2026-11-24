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

import java.util.ArrayList;
import java.util.List;

public class Job extends Task {

    private List<Task> taskList;

    private Double tierMips;

    private Long workBeforeTierScalingMi;

    private double failureInstantSeconds = Double.NaN;

    private long crchCreditedWorkMi;

    private long crchAttemptWorkMi;

    private long crchPlannedCheckpoints;

    public Job(final int jobId, final long jobLength) {

        super(jobId, jobLength);
        this.taskList = new ArrayList<>();
    }

    public List<Task> getTaskList() {
        return this.taskList;
    }

    public Double getTierMips() {
        return this.tierMips;
    }

    public void setTierMips(final Double tierMips) {
        if (tierMips == null || !(tierMips.doubleValue() > 0.0) || tierMips.isNaN() || tierMips.isInfinite()) {
            throw new IllegalArgumentException("Frequency tier mips must be positive and finite: " + tierMips);
        }
        this.tierMips = tierMips;
    }

    /**
     * Records the attempt's declared work before a frequency tier rescales its length.
     *
     * @param value positive MI
     */
    public void setWorkBeforeTierScalingMi(long value) {
        if (value <= 0L) {
            throw new IllegalArgumentException("Work before tier scaling must be positive MI: " + value);
        }
        this.workBeforeTierScalingMi = value;
    }

    /**
     * Returns the work a full restart of this attempt must execute.
     *
     * <p>FGCS-R4 (2026-11-24): root cause - a retry was built from the tier-scaled effective length
     * and scaled again at dispatch, so each retry's duration grew by {@code vmMips / tierMips}.
     *
     * @return declared MI of this attempt, independent of its frequency tier
     */
    public long getRestartWorkMi() {
        return workBeforeTierScalingMi == null ? getEffectiveExecutionLengthMi() : workBeforeTierScalingMi.longValue();
    }

    /**
     * Records the failure arrival that ends this attempt inside its compute window.
     *
     * <p>FGCS-R5 (2026-11-24): root cause - the failure model was evaluated only when the attempt had
     * already run its whole length, so a failed attempt always executed (and was charged) in full. The
     * datacenter now fixes the failure instant when the compute window is known and ends the attempt there.
     *
     * @param value finite failure instant in simulation seconds
     */
    public void setFailureInstantSeconds(double value) {
        if (!Double.isFinite(value) || value < 0.0 || hasFailureInstant()) {
            throw new IllegalStateException("Job " + getCloudletId() + " failure instant must be set once, finite");
        }
        this.failureInstantSeconds = value;
    }

    /** @return whether a failure arrival ends this attempt before its modeled compute finish */
    public boolean hasFailureInstant() {
        return !Double.isNaN(failureInstantSeconds);
    }

    /** @return the failure instant, or NaN when the attempt is not failed by an arrival */
    public double getFailureInstantSeconds() {
        return failureInstantSeconds;
    }

    /**
     * Records the CRCH checkpoint plan of this attempt (FGCS-R5, 2026-11-24).
     *
     * @param creditedWorkMi saved work this attempt skips on its VM
     * @param attemptWorkMi work this attempt computes, excluding checkpoint overhead
     * @param plannedCheckpoints checkpoints this attempt takes if it does not fail
     */
    public void setCrchAttemptPlan(long creditedWorkMi, long attemptWorkMi, long plannedCheckpoints) {
        if (creditedWorkMi < 0L || attemptWorkMi <= 0L || plannedCheckpoints < 0L) {
            throw new IllegalArgumentException("Invalid CRCH attempt plan for Job " + getCloudletId());
        }
        this.crchCreditedWorkMi = creditedWorkMi;
        this.crchAttemptWorkMi = attemptWorkMi;
        this.crchPlannedCheckpoints = plannedCheckpoints;
    }

    public long getCrchCreditedWorkMi() {
        return crchCreditedWorkMi;
    }

    public long getCrchAttemptWorkMi() {
        if (crchAttemptWorkMi <= 0L) {
            throw new IllegalStateException("Job " + getCloudletId() + " has no CRCH attempt plan");
        }
        return crchAttemptWorkMi;
    }

    public long getCrchPlannedCheckpoints() {
        return crchPlannedCheckpoints;
    }

    public void setTaskList(List list) {
        this.taskList = list;
    }

    public void addTaskList(List list) {
        this.taskList.addAll(list);
    }

    @Override
    public List getParentList() {
        return super.getParentList();
    }
}
