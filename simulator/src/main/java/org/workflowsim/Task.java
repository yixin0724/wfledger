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

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.UtilizationModelFull;

/**
 * Workflow task model of WorkflowSim.
 *
 * <p>This class extends the CloudSim {@link Cloudlet} and stores parent and child tasks, data
 * files, depth, and the priority/impact information required by research algorithms. The workflow
 * engine releases a task to the scheduler only after all of its parent tasks have completed
 * successfully.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Task extends Cloudlet {

    /** Parent task list. */
    private List<Task> parentList;
    /** Child task list. */
    private List<Task> childList;
    /** List of associated input, output, and intermediate files. */
    private List<FileItem> fileList;
    /**
     * Priority for research algorithms; the core execution path does not interpret this value.
     *
     * <p><strong>Current status</strong>: none of the built-in scheduling algorithms (FCFS, the
     * ReadyBatch family, DATA, STATIC, and the legacy variants) <strong>read</strong> this field;
     * the clustering layer (ClusteringEngine) sets it uniformly to 0. It exists only for external
     * research algorithm extensions to interpret on their own, and setting it does not affect the
     * dispatch order of the built-in algorithms.</p>
     */
    private int priority;
    /**
     * DAG depth, that is, the number of nodes on the longest path from any root task to this task;
     * written uniformly by {@link WorkflowDagValidator} after input parsing.
     */
    private int depth;
    /** Task impact for research algorithms. */
    private double impact;

    /** Task type or name declared by the input workflow. */
    private String type;

    /**
     * Task completion time maintained by WorkflowSim; Cloudlet itself does not allow this project
     * to update its completion time field directly.
     */
    private double taskFinishTime;

    /**
     * Optional static planned start time. Only planners that generate a complete per-VM execution
     * order write this value; plans that provide a VM mapping only leave it at
     * {@link Double#NaN}.
     */
    private double staticScheduleStartTime;

    /**
     * Optional heterogeneous task-by-VM execution cost projection (vmId -> execution seconds).
     *
     * <p>After parsing, {@code WorkflowPlanner} projects it from the configured
     * {@link org.workflowsim.utils.TaskCostMatrix} onto every task; when no cost matrix is
     * configured the value stays {@code null} and the platform keeps the MI/mips scaling behavior.
     * The planner reads this projection for cost estimation; STATIC dispatch converts the Job
     * length as {@code MI = round(seconds * vm.mips)} before job submission.</p>
     */
    private java.util.Map<Integer, Double> vmExecutionCostSeconds;

    /** Per-attempt compute MI, before stage-in; null until execution work is resolved. */
    private Long effectiveExecutionLengthMi;

    /**
     * Creates a workflow task.
     *
     * @param taskId unique task identifier
     * @param taskLength length to execute, in million instructions (MI)
     */
    public Task(final int taskId, final long taskLength) {
        /*
         * File dependencies are expressed separately through fileList, so the single input/output
         * file size fields of Cloudlet are not used. The CPU, memory, and bandwidth utilization
         * models use the CloudSim default full-utilization implementation.
         */
        super(
                taskId,
                taskLength,
                1,
                0,
                0,
                new UtilizationModelFull(),
                new UtilizationModelFull(),
                new UtilizationModelFull());

        this.childList = new ArrayList<>();
        this.parentList = new ArrayList<>();
        this.fileList = new ArrayList<>();
        this.impact = 0.0;
        this.taskFinishTime = -1.0;
        this.staticScheduleStartTime = Double.NaN;
    }

    /**
     * Sets the task type declared by the input workflow.
     *
     * @param type task type or name
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Sets the heterogeneous execution cost projection of this task on each VM.
     *
     * <p>The caller must pass a mapping that covers every candidate VM, and each entry must be a
     * positive finite number of seconds. The mapping is deep-copied into an immutable map so that
     * it cannot be modified externally during planning.</p>
     *
     * @param value vmId -> execution seconds mapping; must not be null or empty
     * @throws IllegalArgumentException when the mapping is null/empty or contains a non-positive or
     *         non-finite number of seconds
     */
    public void setVmExecutionCostSeconds(java.util.Map<Integer, Double> value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("VM execution cost projection must be non-empty");
        }
        java.util.Map<Integer, Double> copy = new java.util.LinkedHashMap<Integer, Double>();
        for (java.util.Map.Entry<Integer, Double> entry : value.entrySet()) {
            Double seconds = entry.getValue();
            if (seconds == null || !(seconds.doubleValue() > 0.0) || seconds.isNaN() || seconds.isInfinite()) {
                throw new IllegalArgumentException("VM execution cost seconds must be positive finite: " + "task "
                        + getCloudletId() + " on VM " + entry.getKey() + " got " + seconds);
            }
            copy.put(entry.getKey(), seconds);
        }
        this.vmExecutionCostSeconds = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the heterogeneous execution cost of this task on one VM, in seconds.
     *
     * @param vmId VM ID
     * @return execution seconds; null when no cost projection is configured
     */
    public Double getVmExecutionCostSeconds(int vmId) {
        if (vmExecutionCostSeconds == null) {
            return null;
        }
        return vmExecutionCostSeconds.get(Integer.valueOf(vmId));
    }

    /**
     * Tells whether this task carries a heterogeneous execution cost projection.
     *
     * @return true when it carries one
     */
    public boolean hasVmExecutionCostSeconds() {
        return vmExecutionCostSeconds != null;
    }

    /** @return immutable matrix projection; empty when no matrix was declared */
    public java.util.Map<Integer, Double> getVmExecutionCostSeconds() {
        return vmExecutionCostSeconds == null
                ? java.util.Collections.<Integer, Double>emptyMap()
                : vmExecutionCostSeconds;
    }

    /**
     * Return the current attempt's compute work, excluding any stage-in injected into its Job.
     * Before dispatch the declared input length is the fallback; matrix resolution occurs at dispatch.
     *
     * @return per-PE compute MI for this attempt
     */
    public long getEffectiveExecutionLengthMi() {
        return effectiveExecutionLengthMi == null ? getCloudletLength() : effectiveExecutionLengthMi.longValue();
    }

    /**
     * Record effective compute work without modifying the original logical task length.
     *
     * @param value positive representable per-PE MI for the current attempt
     */
    public void setEffectiveExecutionLengthMi(long value) {
        effectiveExecutionLengthMi = Long.valueOf(
                org.workflowsim.utils.TaskExecutionModel.requireRepresentableLength(value, getNumberOfPes()));
    }

    /** @return task type or name declared by the input workflow */
    public String getType() {
        return type;
    }

    /**
     * Sets the priority used by research algorithms.
     *
     * @param priority priority value
     */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * Sets the DAG depth.
     *
     * @param depth longest-path depth counted from the root task
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /** @return priority used by research algorithms */
    public int getPriority() {
        return this.priority;
    }

    /** @return DAG depth */
    public int getDepth() {
        return this.depth;
    }

    /** @return child task list */
    public List<Task> getChildList() {
        return this.childList;
    }

    /**
     * Replaces the child task list.
     *
     * @param list child task list
     */
    public void setChildList(List<Task> list) {
        this.childList = list;
    }

    /**
     * Replaces the parent task list.
     *
     * @param list parent task list
     */
    public void setParentList(List<Task> list) {
        this.parentList = list;
    }

    /**
     * Appends tasks to the existing child task list.
     *
     * @param list child tasks to append
     */
    public void addChildList(List<Task> list) {
        this.childList.addAll(list);
    }

    /**
     * Appends tasks to the existing parent task list.
     *
     * @param list parent tasks to append
     */
    public void addParentList(List<Task> list) {
        this.parentList.addAll(list);
    }

    /** @return parent task list */
    public List<Task> getParentList() {
        return this.parentList;
    }

    /**
     * Adds one child task.
     *
     * @param task child task
     */
    public void addChild(Task task) {
        this.childList.add(task);
    }

    /**
     * Adds one parent task.
     *
     * @param task parent task
     */
    public void addParent(Task task) {
        this.parentList.add(task);
    }

    /** @return list of associated input, output, and intermediate files */
    public List<FileItem> getFileList() {
        return this.fileList;
    }

    /**
     * Adds one associated file.
     *
     * @param file input, output, or intermediate file
     */
    public void addFile(FileItem file) {
        this.fileList.add(file);
    }

    /**
     * Replaces the associated file list.
     *
     * @param list input, output, and intermediate file list
     */
    public void setFileList(List<FileItem> list) {
        this.fileList = list;
    }

    /**
     * Sets the task impact.
     *
     * @param impact impact used by research algorithms
     */
    public void setImpact(double impact) {
        this.impact = impact;
    }

    /** @return task impact */
    public double getImpact() {
        return this.impact;
    }

    /**
     * Stores the non-negative abstract start time of the static plan.
     *
     * @param value non-negative and finite start time
     */
    public void setStaticScheduleStartTime(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Static schedule start time must be finite and non-negative");
        }
        this.staticScheduleStartTime = value;
    }

    /**
     * Returns the static plan start time.
     *
     * @return start time in the complete static plan; {@link Double#NaN} when only a VM mapping is
     *         available
     */
    public double getStaticScheduleStartTime() {
        return this.staticScheduleStartTime;
    }

    /**
     * Sets the task completion time maintained by WorkflowSim.
     *
     * @param time completion time in simulation time units
     */
    public void setTaskFinishTime(double time) {
        this.taskFinishTime = time;
    }

    /** @return task completion time maintained by WorkflowSim */
    public double getTaskFinishTime() {
        return this.taskFinishTime;
    }

    /**
     * Computes the task processing cost.
     *
     * <p>The cost approximates the CPU execution cost plus the bandwidth price of the associated
     * files. The declared byte count of every associated file is converted continuously at decimal
     * MB ({@code 1,000,000} bytes) and then multiplied by the bandwidth unit price; no per-file
     * truncation to whole MB is applied. This method does not represent the complete billing rules
     * of a real cloud provider, nor does it equal data movement transactions that have taken place
     * or a cloud provider invoice.</p>
     *
     * @return total task processing cost
     */
    @Override
    public double getProcessingCost() {
        return getModeledCpuEnvelopeCost() + getModeledDeclaredFileBandwidthCost();
    }

    /**
     * Returns the CPU price component of the Job envelope.
     *
     * <p>CloudSim may already have converted the modeled stage-in seconds into additional MI, so
     * this value is not the real billing of pure original workflow CPU work.</p>
     *
     * @return abstract cost-unit value of {@code costPerSecond * actualCpuTime}
     */
    public double getModeledCpuEnvelopeCost() {
        return getCostPerSec() * getActualCPUTime();
    }

    /**
     * Returns the byte count of all declared files in the current Task/Job file table.
     *
     * <p>The value is not filtered by input/output, real external input, cache hits, or replica
     * location, so it is a declared file requirement rather than an actual number of bytes moved
     * over the network or storage.</p>
     *
     * @return continuous sum of declared file byte equivalents
     */
    public double getModeledDeclaredFileBytes() {
        double declaredFileBytes = 0.0;
        for (FileItem file : getFileList()) {
            declaredFileBytes += file.getSize();
        }
        return declaredFileBytes;
    }

    /**
     * Returns the bandwidth price component computed from the declared file byte count.
     *
     * <p>The metering unit is one decimal MB per {@code 1,000,000} bytes; no rounding per file, per
     * Job, or to currency display precision is performed internally.</p>
     *
     * @return abstract cost-unit value of
     *         {@code bandwidthPrice * declaredFileBytes / 1_000_000}
     */
    public double getModeledDeclaredFileBandwidthCost() {
        return costPerBw * getModeledDeclaredFileBytes() / (double) Consts.MILLION;
    }
}
