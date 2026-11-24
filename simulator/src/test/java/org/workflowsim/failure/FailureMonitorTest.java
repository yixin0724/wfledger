package org.workflowsim.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.ClassType;

class FailureMonitorTest {

    @AfterEach
    void resetFailureState() {
        FailureMonitor.reset();
        FailureParameters.reset();
    }

    @Test
    void vmJobMonitorUsesTheVmAndDepthCompositeKey() {
        FailureParameters.init(
                FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_VM_JOB,
                FailureParameters.FTCFailure.FAILURE_NONE,
                null);
        FailureMonitor.init();
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 2, 3, 4, 7, 10, 0));
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 0, 4, 2, 7, 11, 0));
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 0, 3, 3, 8, 12, 0));

        assertEquals(0.5, FailureMonitor.analyzeVmDepth(7, 3), 0.0);
        assertEquals(0.0, FailureMonitor.analyzeVmDepth(7, 4), 0.0);
        assertEquals(0.0, FailureMonitor.analyzeVmDepth(8, 3), 0.0);
        assertEquals(0.0, FailureMonitor.analyzeVmDepth(9, 3), 0.0);
    }

    private static Job computeJob(int id, int vmId, Task... tasks) {
        Job job = new Job(id, 1L);
        assertTrue(job.setClassType(ClassType.COMPUTE.value));
        job.setUserId(0);
        job.setVmId(vmId);
        job.setDepth(1);
        job.addTaskList(Arrays.asList(tasks));
        return job;
    }

    private static Task task(int id, long length) throws Exception {
        Task task = new Task(id, length);
        task.setUserId(0);
        task.setDepth(1);
        task.setCloudletStatus(Cloudlet.FAILED);
        return task;
    }

    private static List<Integer> taskIds(Job job) {
        List<Integer> ids = new java.util.ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            ids.add(task.getCloudletId());
        }
        return ids;
    }
}
