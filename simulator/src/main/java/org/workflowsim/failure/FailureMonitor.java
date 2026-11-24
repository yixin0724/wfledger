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

public class FailureMonitor {

    protected static Map<Integer, List<FailureRecord>> vm2record;

    protected static Map<Integer, List<FailureRecord>> type2record;

    protected static Map<String, List<FailureRecord>> vmDepth2record;

    protected static Map<Integer, FailureRecord> jobid2record;

    protected static List<FailureRecord> recordList;

    public static Map index2job;

    public static void init() {
        vm2record = new HashMap<>();
        type2record = new HashMap<>();
        vmDepth2record = new HashMap<>();
        jobid2record = new HashMap<>();
        recordList = new ArrayList<>();
    }

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

            default:
                break;
        }

        recordList.add(record);
    }

    public static double analyze(int workflowId, int type) {
        requireInitialized();

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

            default:
                break;
        }

        if (sumFailures == 0) {
            return 0;
        }
        double alpha = (double) ((double) sumFailures / (double) sumJobs);
        return alpha;
    }

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
