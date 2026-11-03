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
package org.workflowsim.failure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects failure records and estimates the task failure rate that later re-clustering uses, at
 * the configured monitoring granularity.
 *
 * <p>Records belong to the current simulation session only; callers must initialize the monitor
 * before writing or analyzing records.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FailureMonitor {

    /**
     * Failure records indexed by VM id.
     */
    protected static Map<Integer, List<FailureRecord>> vm2record;
    /**
     * Failure records indexed by task depth (also called type in the historical code).
     */
    protected static Map<Integer, List<FailureRecord>> type2record;
    /** Failure records indexed by the composite key of VM id and task depth. */
    protected static Map<String, List<FailureRecord>> vmDepth2record;
    /**
     * Failure records indexed by Job id.
     */
    protected static Map<Integer, FailureRecord> jobid2record;
    /**
     * All failure records of the current session.
     */
    protected static List<FailureRecord> recordList;
    /**
     * Job index mapping, used by the legacy failure recovery path.
     */
    public static Map index2job;

    /**
     * Initializes the failure record containers of the current simulation run.
     */
    public static void init() {
        vm2record = new HashMap<>();
        type2record = new HashMap<>();
        vmDepth2record = new HashMap<>();
        jobid2record = new HashMap<>();
        recordList = new ArrayList<>();
    }

    /** Clears all monitoring records held by the finished simulation run. */
    public static void reset() {
        vm2record = null;
        type2record = null;
        vmDepth2record = null;
        jobid2record = null;
        recordList = null;
        index2job = null;
    }

    private static void requireInitialized() {
        if (vm2record == null
                || type2record == null
                || vmDepth2record == null
                || jobid2record == null
                || recordList == null) {
            throw new IllegalStateException("FailureMonitor is not initialized for this simulation run");
        }
    }

    /**
     * Computes the suggested clustering factor from an observed failure rate.
     *
     * @param d delay overhead
     * @param a observed task failure rate
     * @param t task runtime
     * @return suggested clustering factor
     */
    protected static double getK(double d, double a, double t) {
        double k = (-d + Math.sqrt(d * d - 4 * d / Math.log(1 - a))) / (2 * t);
        return k;
    }

    /**
     * Returns the suggested clustering factor for the given failure record under the current
     * monitoring mode.
     *
     * @param record failure record of the current recovery request
     * @return suggested clustering factor
     */
    public static int getClusteringFactor(FailureRecord record) {

        double d = record.delayLength;

        double t = record.length;
        double a = 0.0;
        switch (FailureParameters.getMonitorMode()) {
            case MONITOR_JOB:
                /**
                 * In this legacy implementation MONITOR_JOB is handled through the global records.
                 */
            case MONITOR_ALL:
                a = analyze(0, record.depth);
                break;
            case MONITOR_VM:
                a = analyze(0, record.vmId);
                break;
            case MONITOR_VM_JOB:
                a = analyzeVmDepth(record.vmId, record.depth);
                break;
                // MONITOR_NONE analyzes no clustering factor and keeps a=0.0, so the all-task-count
                // fallback applies (historical semantics of the legacy failure path).
            default:
                break;
        }

        if (a <= 0.0) {
            return record.allTaskNum;
        } else {
            double k = getK(d, a, t);

            if (k <= 1) {
                k = 1; // The clustering factor is at least 1.
            }

            return (int) k;
        }
    }

    /**
     * Accepts a failure record posted by the scheduling agent and indexes it under the current
     * monitoring mode.
     *
     * @param record failure record to be stored
     */
    public static void postFailureRecord(FailureRecord record) {
        requireInitialized();

        if (record.workflowId < 0 || record.jobId < 0 || record.vmId < 0) {
            throw new IllegalArgumentException("Failure record must identify a non-negative workflow, job, and VM");
        }

        switch (FailureParameters.getMonitorMode()) {
            case MONITOR_VM:
                if (!vm2record.containsKey(record.vmId)) {
                    vm2record.put(record.vmId, new ArrayList<>());
                }
                vm2record.get(record.vmId).add(record);

                break;
            case MONITOR_JOB:
                if (!type2record.containsKey(record.depth)) {
                    type2record.put(record.depth, new ArrayList<>());
                }
                type2record.get(record.depth).add(record);

                break;
            case MONITOR_VM_JOB:
                String key = vmDepthKey(record.vmId, record.depth);
                if (!vmDepth2record.containsKey(key)) {
                    vmDepth2record.put(key, new ArrayList<FailureRecord>());
                }
                vmDepth2record.get(key).add(record);

                break;
            case MONITOR_NONE:
                break;
                // MONITOR_ALL is the only mode that reaches default: it builds no grouped index and
                // analyze() aggregates the whole recordList.
            default:
                break;
        }

        recordList.add(record);
    }

    /**
     * Computes the observed task failure rate from the current record set.
     *
     * @param workflowId unused in the current version; kept for compatibility with the old interface
     * @param type VM id or task depth, depending on the monitoring mode
     * @return task failure rate
     */
    public static double analyze(int workflowId, int type) {
        requireInitialized();

        /**
         * MONITOR_ALL aggregates all Jobs of the whole workflow.
         */
        int sumFailures = 0;
        int sumJobs = 0;
        switch (FailureParameters.getMonitorMode()) {
            case MONITOR_ALL:
                for (FailureRecord record : recordList) {
                    sumFailures += record.failedTasksNum;
                    sumJobs += record.allTaskNum;
                }

                break;

            case MONITOR_JOB:
                if (type2record.containsKey(type)) {
                    for (FailureRecord record : type2record.get(type)) {

                        sumFailures += record.failedTasksNum;
                        sumJobs += record.allTaskNum;
                    }
                }

                break;
            case MONITOR_VM:
                if (vm2record.containsKey(type)) {
                    for (FailureRecord record : vm2record.get(type)) {

                        sumFailures += record.failedTasksNum;
                        sumJobs += record.allTaskNum;
                    }
                }

                break;
            case MONITOR_VM_JOB:
                throw new IllegalStateException("MONITOR_VM_JOB requires analyzeVmDepth(vmId, depth)");
                // MONITOR_NONE is the only mode that reaches default: it holds no monitoring records,
                // so both counters stay 0 and the result is 0.
            default:
                break;
        }

        if (sumFailures == 0) {
            return 0;
        }
        double alpha = (double) ((double) sumFailures / (double) sumJobs);
        return alpha;
    }

    /**
     * Computes the observed failure rate for a VM id and a DAG depth.
     *
     * @param vmId VM id
     * @param depth DAG depth
     * @return failed-task share of the matching composite group; 0 when no record exists yet
     */
    public static double analyzeVmDepth(int vmId, int depth) {
        requireInitialized();
        if (vmId < 0 || depth < 0) {
            throw new IllegalArgumentException("VM ID and task depth must be non-negative");
        }
        if (FailureParameters.getMonitorMode() != FailureParameters.FTCMonitor.MONITOR_VM_JOB) {
            throw new IllegalStateException("analyzeVmDepth requires MONITOR_VM_JOB");
        }

        int failures = 0;
        int tasks = 0;
        List<FailureRecord> records = vmDepth2record.get(vmDepthKey(vmId, depth));
        if (records == null) {
            return 0.0;
        }
        for (FailureRecord record : records) {
            failures += record.failedTasksNum;
            tasks += record.allTaskNum;
        }
        return failures == 0 ? 0.0 : (double) failures / (double) tasks;
    }

    private static String vmDepthKey(int vmId, int depth) {
        return vmId + ":" + depth;
    }
}
