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

public class Task extends Cloudlet {

    private List<Task> parentList;

    private List<Task> childList;

    private List<FileItem> fileList;

    private int priority;

    private int depth;

    private double impact;

    private int cwsRank = 1;

    private String type;

    private double taskFinishTime;

    private double staticScheduleStartTime;

    private java.util.Map<Integer, Double> vmExecutionCostSeconds;

    private Long effectiveExecutionLengthMi;

    public Task(final int taskId, final long taskLength) {

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

    public void setType(String type) {
        this.type = type;
    }

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

    public Double getVmExecutionCostSeconds(int vmId) {
        if (vmExecutionCostSeconds == null) {
            return null;
        }
        return vmExecutionCostSeconds.get(Integer.valueOf(vmId));
    }

    public boolean hasVmExecutionCostSeconds() {
        return vmExecutionCostSeconds != null;
    }

    public java.util.Map<Integer, Double> getVmExecutionCostSeconds() {
        return vmExecutionCostSeconds == null
                ? java.util.Collections.<Integer, Double>emptyMap()
                : vmExecutionCostSeconds;
    }

    public long getEffectiveExecutionLengthMi() {
        return effectiveExecutionLengthMi == null ? getCloudletLength() : effectiveExecutionLengthMi.longValue();
    }

    public void setEffectiveExecutionLengthMi(long value) {
        effectiveExecutionLengthMi = Long.valueOf(
                org.workflowsim.utils.TaskExecutionModel.requireRepresentableLength(value, getNumberOfPes()));
    }

    public String getType() {
        return type;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getPriority() {
        return this.priority;
    }

    public int getDepth() {
        return this.depth;
    }

    public void setCwsRank(int rank) {
        if (rank < 1) {
            throw new IllegalArgumentException("CWS rank must be at least 1");
        }
        this.cwsRank = rank;
    }

    public int getCwsRank() {
        return cwsRank;
    }

    public List<Task> getChildList() {
        return this.childList;
    }

    public void setChildList(List<Task> list) {
        this.childList = list;
    }

    public void setParentList(List<Task> list) {
        this.parentList = list;
    }

    public void addChildList(List<Task> list) {
        this.childList.addAll(list);
    }

    public void addParentList(List<Task> list) {
        this.parentList.addAll(list);
    }

    public List<Task> getParentList() {
        return this.parentList;
    }

    public void addChild(Task task) {
        this.childList.add(task);
    }

    public void addParent(Task task) {
        this.parentList.add(task);
    }

    public List<FileItem> getFileList() {
        return this.fileList;
    }

    public void addFile(FileItem file) {
        this.fileList.add(file);
    }

    public void setFileList(List<FileItem> list) {
        this.fileList = list;
    }

    public void setImpact(double impact) {
        this.impact = impact;
    }

    public double getImpact() {
        return this.impact;
    }

    public void setStaticScheduleStartTime(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Static schedule start time must be finite and non-negative");
        }
        this.staticScheduleStartTime = value;
    }

    public double getStaticScheduleStartTime() {
        return this.staticScheduleStartTime;
    }

    public void setTaskFinishTime(double time) {
        this.taskFinishTime = time;
    }

    public double getTaskFinishTime() {
        return this.taskFinishTime;
    }

    @Override
    public double getProcessingCost() {
        return getModeledCpuEnvelopeCost() + getModeledDeclaredFileBandwidthCost();
    }

    public double getModeledCpuEnvelopeCost() {
        return getCostPerSec() * getActualCPUTime();
    }

    public double getModeledDeclaredFileBytes() {
        double declaredFileBytes = 0.0;
        List<FileItem> files = getFileList();
        for (FileItem file : files) {
            if (file.isRealInputFile(files)) {
                declaredFileBytes += file.getSize();
            }
        }
        return declaredFileBytes;
    }

    public double getModeledDeclaredFileBandwidthCost() {
        return costPerBw * getModeledDeclaredFileBytes() / (double) Consts.MILLION;
    }
}
