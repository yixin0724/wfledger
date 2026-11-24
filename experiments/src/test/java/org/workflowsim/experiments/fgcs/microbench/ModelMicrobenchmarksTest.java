package org.workflowsim.experiments.fgcs.microbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.v2.FileLifecycleEvent;
import org.workflowsim.experiment.SimulationEvent;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.experiment.SimulationReport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ModelMicrobenchmarksTest {

    private static final double EPSILON = 1.0e-9;

    @TempDir
    Path directory;

    private boolean logging;

    @BeforeEach
    void quiet() {
        logging = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logging);
    }

    @Test
    void actualSimulationsMeetAnalyticContractsAndEmitResolvableEvidence() throws Exception {
        ModelMicrobenchmarks.Suite suite = ModelMicrobenchmarks.run(directory);

        assertProgressiveFilling(suite.getReport("bottleneck-shared"), suite.getReport("bottleneck-independent"));
        assertReplicaVisibilityAndCpuGate(suite.getReport("replica-visibility"));
        assertWritebackDrain(suite.getReport("writeback-drain"), suite.getReport("writeback-control"));
        assertDeterministicRecovery(suite.getReport("crch-retry"));
        assertCompactJsonAndEvidencePaths(suite.getDocument());
    }

    private static void assertProgressiveFilling(SimulationReport shared, SimulationReport independent) {
        FileLifecycleEvent sharedSmall = admission(shared, "small", "INPUT");
        FileLifecycleEvent sharedLarge = admission(shared, "large", "INPUT");
        double start = sharedSmall.getObservedTime();
        assertEquals(start, sharedLarge.getObservedTime(), 0.0);
        assertEquals(1.0e6, payload(sharedSmall).get("standaloneRate").getAsDouble(), 0.0);
        assertEquals(1.0e6, payload(sharedLarge).get("standaloneRate").getAsDouble(), 0.0);
        assertTrue(payload(sharedSmall).getAsJsonArray("resources").toString().contains("STORE:source:READ"));
        assertTrue(payload(sharedLarge).getAsJsonArray("resources").toString().contains("STORE:source:READ"));

        // Max-min gives each flow 0.5 MB/s. The 2 MB flow ends at 4 s, releasing
        // capacity; the 6 MB flow then needs four more seconds at 1 MB/s.
        assertClose(4.0, effective(shared, sharedSmall) - start);
        assertClose(8.0, effective(shared, sharedLarge) - start);

        FileLifecycleEvent independentSmall = admission(independent, "small", "INPUT");
        FileLifecycleEvent independentLarge = admission(independent, "large", "INPUT");
        double isolatedStart = independentSmall.getObservedTime();
        assertEquals(isolatedStart, independentLarge.getObservedTime(), 0.0);
        assertClose(2.0, effective(independent, independentSmall) - isolatedStart);
        assertClose(6.0, effective(independent, independentLarge) - isolatedStart);
    }

    private static void assertReplicaVisibilityAndCpuGate(SimulationReport report) {
        FileLifecycleEvent fast = admission(report, "fast", null);
        FileLifecycleEvent slow = admission(report, "slow", null);
        double start = fast.getObservedTime();
        assertEquals(start, slow.getObservedTime(), 0.0);
        assertClose(1.0, effective(report, fast) - start);
        assertClose(4.0, effective(report, slow) - start);

        FileLifecycleEvent fastSettled = settlement(report, fast);
        FileLifecycleEvent slowSettled = settlement(report, slow);
        assertTrue(fastSettled.getObservedTime() < slowSettled.getObservedTime());
        SimulationReport.JobOutcome child = successfulJob(report, 2);
        FileLifecycleEvent ready = oneLifecycle(report, FileLifecycleEvent.Type.JOB_DATA_READY, child.getJobId());
        FileLifecycleEvent cpu = oneLifecycle(report, FileLifecycleEvent.Type.JOB_CPU_STARTED, child.getJobId());
        assertEquals(slowSettled.getObservedTime(), ready.getObservedTime(), 0.0);
        assertTrue(cpu.getObservedTime() >= slowSettled.getObservedTime());
        assertTrue(ready.getSequence() > fastSettled.getSequence());

        int parentVm = successfulJob(report, 1).getVmId();
        assertNotNull(parentVm);
        for (FileLifecycleEvent copy : new FileLifecycleEvent[] {fast, slow}) {
            JsonObject source = payload(copy).getAsJsonObject("sourceReplica").getAsJsonObject("location");
            assertEquals(parentVm, source.get("vmId").getAsInt());
            assertEquals(child.getVmId(), payload(copy).get("destinationVmId").getAsInt());
            assertTrue(parentVm != child.getVmId(), "The gating fixture requires a remote visible replica");
        }
    }

    private static void assertWritebackDrain(SimulationReport writeback, SimulationReport control) {
        FileLifecycleEvent output = admission(writeback, "sink", "OUTPUT");
        FileLifecycleEvent finished = oneLifecycle(writeback, FileLifecycleEvent.Type.TASK_FINISHED, null);
        assertEquals(finished.getObservedTime(), output.getObservedTime(), 0.0);
        assertClose(2.0, effective(writeback, output) - output.getObservedTime());
        FileLifecycleEvent settled = settlement(writeback, output);
        assertTrue(settled.getObservedTime() > finished.getObservedTime());
        assertTrue(writeback.getMakespan() >= settled.getObservedTime());
        assertNotNull(writeback.getTerminalLifecycleTailSeconds());
        assertTrue(writeback.getTerminalLifecycleTailSeconds() > 0.0);
        assertTrue(successfulJob(writeback, 1).getFinishTime() <= finished.getObservedTime());

        assertEquals(
                0, lifecycle(control, FileLifecycleEvent.Type.COPY_ADMITTED).size());
        assertNotNull(control.getTerminalLifecycleTailSeconds());
        assertClose(0.0, control.getTerminalLifecycleTailSeconds());
    }

    private static void assertDeterministicRecovery(SimulationReport report) {
        assertTrue(report.isWorkflowCompletedSuccessfully());
        assertEquals(1, report.getMetrics().getRetryJobCreatedCount());
        assertEquals(1, report.getFailedJobs());
        SimulationEvent retryCreated = oneMain(report, SimulationEventType.RETRY_JOB_CREATED, null, null);
        int failedJobId = ((Number) retryCreated.getAttributes().get("failedJobId")).intValue();
        int retryJobId = retryCreated.getJobId();
        SimulationReport.TaskOutcome failedParent = task(report, 2, failedJobId);
        SimulationReport.TaskOutcome retryParent = task(report, 2, retryJobId);
        assertEquals(Cloudlet.FAILED, failedParent.getTaskStatus());
        assertEquals(Cloudlet.SUCCESS, retryParent.getTaskStatus());
        // FGCS-R5 (2026-11-24), hand-derived: 2000 MI at 1000 MI/s, lambda=1, gamma=0.1 -> one checkpoint,
        // 2100 MI over [0.21, 2.31). The arrival 1.5 ends it after 1.29 s, past the checkpoint at 1.1 s:
        // 1000 MI saved on VM 3, where resuming costs 1.0 s versus a 2.1 s re-execution on VM 8.
        assertEquals(3, failedParent.getVmId());
        assertEquals(3, retryParent.getVmId());
        assertClose(0.21, failedParent.getStartTime());
        assertClose(1.5, failedParent.getFinishTime());
        assertEquals(2100L, failedParent.getEffectiveExecutionLengthMi());
        assertEquals(1000L, retryParent.getEffectiveExecutionLengthMi());
        assertClose(1.5, retryParent.getStartTime());
        assertClose(2.5, retryParent.getFinishTime());

        SimulationEvent failed = oneMain(report, SimulationEventType.JOB_FAILED, failedJobId, null);
        SimulationEvent returned = oneMain(report, SimulationEventType.JOB_RETURNED, retryJobId, null);
        assertEquals(Cloudlet.SUCCESS, ((Number) returned.getAttributes().get("jobStatus")).intValue());
        SimulationEvent childReady = oneMain(report, SimulationEventType.JOB_READY, null, 1);
        assertTrue(failed.getSequence() < retryCreated.getSequence());
        assertTrue(retryCreated.getSequence() < returned.getSequence());
        assertTrue(
                returned.getSequence() < childReady.getSequence(),
                "A failed parent must not release its child before the successful retry returns");
        SimulationReport.TaskOutcome child = null;
        for (SimulationReport.TaskOutcome candidate : report.getTasks()) {
            if (candidate.getTaskId() == 1 && candidate.getTaskStatus() == Cloudlet.SUCCESS) {
                child = candidate;
            }
        }
        assertNotNull(child);
        assertTrue(child.getStartTime() + EPSILON >= retryParent.getFinishTime());
    }

    private void assertCompactJsonAndEvidencePaths(JsonObject document) throws Exception {
        assertEquals("fgcs-model-microbench-v1", document.get("schema").getAsString());
        assertEquals(4, document.getAsJsonArray("cases").size());
        for (JsonElement element : document.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            assertTrue(testCase.get("passed").getAsBoolean());
            assertFalse(testCase.getAsJsonObject("expected").size() == 0);
            assertFalse(testCase.getAsJsonObject("observed").size() == 0);
            for (Map.Entry<String, JsonElement> entry :
                    testCase.getAsJsonObject("evidence").entrySet()) {
                String reference = entry.getValue().getAsString();
                int hash = reference.indexOf('#');
                assertTrue(hash > 0, reference);
                Path file = directory.resolve(reference.substring(0, hash));
                assertTrue(Files.isRegularFile(file), reference);
                JsonElement target =
                        JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                for (String token : reference.substring(hash + 2).split("/")) {
                    target = target.getAsJsonObject()
                            .get(token.replace("~1", "/").replace("~0", "~"));
                    assertNotNull(target, reference);
                }
            }
        }
        JsonObject retained = JsonParser.parseString(
                        new String(Files.readAllBytes(directory.resolve("results.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(document, retained);
    }

    private static FileLifecycleEvent admission(SimulationReport report, String file, String purpose) {
        FileLifecycleEvent match = null;
        for (FileLifecycleEvent event : lifecycle(report, FileLifecycleEvent.Type.COPY_ADMITTED)) {
            JsonObject payload = payload(event);
            if (file.equals(payload.getAsJsonObject("fileId").get("name").getAsString())
                    && (purpose == null || purpose.equals(payload.get("purpose").getAsString()))) {
                assertTrue(match == null, "Duplicate copy for " + file);
                match = event;
            }
        }
        assertNotNull(match, "Missing copy for " + file);
        return match;
    }

    private static FileLifecycleEvent settlement(SimulationReport report, FileLifecycleEvent admission) {
        long ordinal = payload(admission).get("copyOrdinal").getAsLong();
        for (FileLifecycleEvent event : lifecycle(report, FileLifecycleEvent.Type.COPY_SETTLED)) {
            if (payload(event).get("copyOrdinal").getAsLong() == ordinal) {
                return event;
            }
        }
        throw new AssertionError("Missing settlement for copy " + ordinal);
    }

    private static double effective(SimulationReport report, FileLifecycleEvent admission) {
        return payload(settlement(report, admission)).get("effectiveTime").getAsDouble();
    }

    private static FileLifecycleEvent oneLifecycle(
            SimulationReport report, FileLifecycleEvent.Type type, Integer jobId) {
        FileLifecycleEvent match = null;
        for (FileLifecycleEvent event : lifecycle(report, type)) {
            if (jobId == null || payload(event).get("jobId").getAsInt() == jobId) {
                assertTrue(match == null, "Duplicate lifecycle event " + type);
                match = event;
            }
        }
        assertNotNull(match, "Missing lifecycle event " + type);
        return match;
    }

    private static List<FileLifecycleEvent> lifecycle(SimulationReport report, FileLifecycleEvent.Type type) {
        List<FileLifecycleEvent> source = report.getStorageLifecycleEvidence() == null
                ? report.getFileLifecycleEvidence().getEvents()
                : report.getStorageLifecycleEvidence().getCapture().getEvents();
        return source.stream().filter(event -> event.getType() == type).collect(java.util.stream.Collectors.toList());
    }

    private static JsonObject payload(FileLifecycleEvent event) {
        return event.getPayload();
    }

    private static SimulationReport.JobOutcome successfulJob(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getTaskIds().contains(taskId) && job.getStatus() == Cloudlet.SUCCESS) {
                return job;
            }
        }
        throw new AssertionError("Missing successful job for task " + taskId);
    }

    private static SimulationReport.TaskOutcome task(SimulationReport report, int taskId, int jobId) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId && task.getJobId() == jobId) {
                return task;
            }
        }
        throw new AssertionError("Missing task " + taskId + " on job " + jobId);
    }

    private static SimulationEvent oneMain(
            SimulationReport report, SimulationEventType type, Integer jobId, Integer taskId) {
        SimulationEvent match = null;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() != type
                    || (jobId != null && !jobId.equals(event.getJobId()))
                    || (taskId != null && !event.getTaskIds().contains(taskId))) {
                continue;
            }
            if (type == SimulationEventType.JOB_RETURNED && jobId != null) {
                // The job ID already identifies the required terminal attempt.
            }
            assertTrue(match == null, "Duplicate main event " + type);
            match = event;
        }
        assertNotNull(match, "Missing main event " + type);
        return match;
    }

    private static void assertClose(double expected, double actual) {
        assertEquals(expected, actual, EPSILON, "expected=" + expected + " actual=" + actual);
    }
}
