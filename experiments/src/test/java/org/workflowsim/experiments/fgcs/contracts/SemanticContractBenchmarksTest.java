package org.workflowsim.experiments.fgcs.contracts;

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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class SemanticContractBenchmarksTest {

    private static final double EPSILON = 1.0e-9;
    private static final Gson JSON = new Gson();

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
    void realRunnersMeetAbsoluteSemanticOraclesAndEmitResolvableEvidence() throws Exception {
        SemanticContractBenchmarks.Suite suite = SemanticContractBenchmarks.run(directory);
        assertControlReady(suite.getReport("control-ready-assignment"));
        assertTieredEnergy(suite.getReport("tiered-energy-drain"));
        assertTwoCheckpoints(suite.getReport("crch-two-checkpoints"));
        assertCompactJsonAndEvidence(suite.getDocument());
    }

    private static void assertControlReady(SimulationReport report) {
        SimulationEvent parent = assignment(report, 1);
        SimulationEvent filler = assignment(report, 2);
        SimulationEvent child = assignment(report, 3);
        assertClose(100.0, parent.getSimulationTime());
        assertClose(100.0, filler.getSimulationTime());
        assertClose(110.0, child.getSimulationTime());
        assertEquals(9, selectedVm(parent));
        assertEquals(9, selectedVm(filler));
        assertEquals(3, selectedVm(child));

        // All six scores come from max(now+input,cpuAvailable)+compute, not a rerun golden.
        assertCandidate(parent, 3, 0.0, 100.0, 20.0, 120.0);
        assertCandidate(parent, 9, 0.0, 100.0, 10.0, 110.0);
        assertCandidate(filler, 3, 0.0, 100.0, 40.0, 140.0);
        assertCandidate(filler, 9, 0.0, 110.0, 20.0, 130.0);
        assertCandidate(child, 3, 1.0, 110.0, 2.0, 113.0);
        assertCandidate(child, 9, 0.0, 130.0, 1.0, 131.0);
        assertTrue(score(child, 9) > score(child, 3), "negative fast-VM candidate must lose");

        assertEquals(9, successfulJob(report, 1).getVmId());
        assertEquals(9, successfulTask(report, 1).getVmId());
        assertEquals(9, successfulJob(report, 2).getVmId());
        assertEquals(9, successfulTask(report, 2).getVmId());
        assertEquals(3, successfulJob(report, 3).getVmId());
        assertEquals(3, successfulTask(report, 3).getVmId());

        FileLifecycleEvent input = oneLifecycle(report, FileLifecycleEvent.Type.COPY_ADMITTED, null);
        FileLifecycleEvent visible = oneLifecycle(report, FileLifecycleEvent.Type.COPY_SETTLED, null);
        int childJob = successfulJob(report, 3).getJobId();
        FileLifecycleEvent ready = oneLifecycle(report, FileLifecycleEvent.Type.JOB_DATA_READY, childJob);
        FileLifecycleEvent cpu = oneLifecycle(report, FileLifecycleEvent.Type.JOB_CPU_STARTED, childJob);
        assertClose(110.0, input.getObservedTime());
        assertClose(111.0, visible.getPayload().get("effectiveTime").getAsDouble());
        assertClose(111.0, ready.getObservedTime());
        assertClose(111.0, cpu.getObservedTime());
        assertClose(111.0, successfulTask(report, 3).getStartTime());
        assertClose(113.0, successfulTask(report, 3).getFinishTime());
        SimulationEvent jobReady = oneMain(report, SimulationEventType.JOB_READY, childJob, null);
        assertTrue(child.getSequence() < jobReady.getSequence());
        assertClose(child.getSimulationTime(), jobReady.getSimulationTime());
        assertTrue(ready.getSequence() <= cpu.getSequence());
        assertClose(130.0, report.getMakespan());
    }

    private static void assertTieredEnergy(SimulationReport report) {
        SimulationReport.JobOutcome job = successfulJob(report, 1);
        int used = job.getVmId();
        int unused = used == 4 ? 9 : 4;
        assertTrue(used == 4 || used == 9);
        assertNotNull(job.getTierMips());
        assertClose(500.0, job.getTierMips());
        assertClose(100.0, job.getStartTime());
        assertClose(104.0, job.getFinishTime());
        assertClose(106.0, report.getMakespan());
        assertClose(2.0, report.getTerminalLifecycleTailSeconds());
        assertClose(0.0, report.getMetrics().getVmMetrics().get(unused).getModeledBusyIntervalSeconds());
        assertClose(
                4.0, report.getMetrics().getPerTierBusySecondsByVm().get(used).get(500.0));
        assertClose(
                0.11, report.getMetrics().getPerTierBusySecondsByVm().get(used).get(1000.0));

        // Default idle is 10*106 on each VM. The used VM adds low-tier dynamic
        // (30-10)*4 plus 110 MI / 1000 MI/s of default-tier stage-in dynamic work.
        assertClose(1149.9, report.getEnergyAccounting().getVmEnergyJoules().get(used));
        assertClose(1060.0, report.getEnergyAccounting().getVmEnergyJoules().get(unused));
        assertClose(2209.9, report.getEnergyAccounting().getTotalEnergyJoules());
        assertClose(1104.95, report.getEnergyAccounting().getMeanVmEnergyJoules());
        assertClose(200.0, report.getEnergyAccounting().getPeakPowerWatts());
        assertClose(234249.4, report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds());
        FileLifecycleEvent settled = oneLifecycle(report, FileLifecycleEvent.Type.COPY_SETTLED, null);
        assertClose(106.0, settled.getPayload().get("effectiveTime").getAsDouble());
    }

    private static void assertTwoCheckpoints(SimulationReport report) {
        assertEquals(1, report.getFailedJobs());
        assertEquals(1, report.getMetrics().getRetryJobCreatedCount());
        SimulationEvent retry = oneMain(report, SimulationEventType.RETRY_JOB_CREATED, null, null);
        int failedJob = ((Number) retry.getAttributes().get("failedJobId")).intValue();
        SimulationReport.TaskOutcome failed = task(report, 1, failedJob);
        SimulationReport.TaskOutcome recovered = task(report, 1, retry.getJobId());
        assertEquals(Cloudlet.FAILED, failed.getTaskStatus());
        assertEquals(Cloudlet.SUCCESS, recovered.getTaskStatus());
        // FGCS-R5 (2026-11-24), hand-derived: 3000 MI at 1000 MI/s with lambda=1, gamma=0.1 takes
        // checkpoints after 1 s and 2 s of progress, so the attempt executes 3200 MI over [100, 103.2).
        // The arrival at 102.5 ends it after 2.5 s; checkpoints completed at 1.1 and 2.2 s save 2000 MI on
        // VM 3. Resuming there costs 1000 MI (1.0 s, no checkpoint) versus 3.2 s on VM 8.
        assertClose(100.0, failed.getStartTime());
        assertClose(102.5, failed.getFinishTime());
        assertEquals(3200L, failed.getEffectiveExecutionLengthMi());
        assertEquals(1000L, recovered.getEffectiveExecutionLengthMi());
        assertClose(102.5, recovered.getStartTime());
        assertClose(1.0, recovered.getFinishTime() - recovered.getStartTime());
        assertClose(103.5, recovered.getFinishTime());
        assertEquals(3, failed.getVmId());
        assertEquals(3, recovered.getVmId());
        SimulationEvent failure = oneMain(report, SimulationEventType.JOB_FAILED, failedJob, null);
        SimulationEvent success = oneMain(report, SimulationEventType.JOB_RETURNED, retry.getJobId(), null);
        SimulationEvent childReady = oneMain(report, SimulationEventType.JOB_READY, null, 2);
        assertTrue(failure.getSequence() < retry.getSequence());
        assertTrue(retry.getSequence() < success.getSequence());
        assertTrue(success.getSequence() < childReady.getSequence());
        assertClose(103.5, childReady.getSimulationTime());
        assertTrue(successfulTask(report, 2).getStartTime() + EPSILON >= recovered.getFinishTime());
    }

    private void assertCompactJsonAndEvidence(JsonObject document) throws Exception {
        assertEquals("fgcs-round2-semantic-contracts-v1", document.get("schema").getAsString());
        assertEquals(3, document.getAsJsonArray("cases").size());
        for (JsonElement element : document.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            assertTrue(testCase.get("passed").getAsBoolean());
            assertFalse(testCase.getAsJsonObject("expected").size() == 0);
            assertFalse(testCase.getAsJsonObject("observed").size() == 0);
            for (Map.Entry<String, JsonElement> entry :
                    testCase.getAsJsonObject("evidence").entrySet()) {
                String reference = entry.getValue().getAsString();
                JsonElement resolved = resolve(reference);
                assertNotNull(resolved, reference);
            }
        }
        assertClose(
                113.0,
                resolve("control-ready.events.json#/events/childAssignment/attributes/candidates/0/scoreFinishSeconds")
                        .getAsDouble());
        assertClose(
                2209.9,
                resolve("tiered-energy.events.json#/accounting/totalEnergyJoules")
                        .getAsDouble());
        assertEquals(
                1000,
                resolve("crch-two-checkpoints.events.json#/events/retryTaskWindow/attributes/"
                                + "effectiveExecutionLengthMi")
                        .getAsLong());
        JsonObject retained = JsonParser.parseString(
                        new String(Files.readAllBytes(directory.resolve("results.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(document, retained);
    }

    private JsonElement resolve(String reference) throws Exception {
        int hash = reference.indexOf("#/");
        assertTrue(hash > 0, reference);
        Path file = directory.resolve(reference.substring(0, hash));
        assertTrue(Files.isRegularFile(file), reference);
        JsonElement target = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        for (String token : reference.substring(hash + 2).split("/")) {
            String decoded = token.replace("~1", "/").replace("~0", "~");
            if (target.isJsonArray()) {
                target = target.getAsJsonArray().get(Integer.parseInt(decoded));
            } else {
                target = target.getAsJsonObject().get(decoded);
            }
            assertNotNull(target, reference);
        }
        return target;
    }

    private static void assertCandidate(
            SimulationEvent action, int vmId, double input, double cpu, double compute, double finish) {
        JsonObject candidate = candidate(action, vmId);
        assertClose(input, candidate.get("inputSeconds").getAsDouble());
        assertClose(cpu, candidate.get("cpuAvailableAt").getAsDouble());
        assertClose(compute, candidate.get("computeSeconds").getAsDouble());
        assertClose(finish, candidate.get("scoreFinishSeconds").getAsDouble());
    }

    private static double score(SimulationEvent action, int vmId) {
        return candidate(action, vmId).get("scoreFinishSeconds").getAsDouble();
    }

    private static JsonObject candidate(SimulationEvent action, int vmId) {
        JsonArray candidates =
                JSON.toJsonTree(action.getAttributes()).getAsJsonObject().getAsJsonArray("candidates");
        for (JsonElement element : candidates) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get("vmId").getAsInt() == vmId) {
                return candidate;
            }
        }
        throw new AssertionError("Missing candidate " + vmId);
    }

    private static int selectedVm(SimulationEvent action) {
        return ((Number) action.getAttributes().get("selectedVmId")).intValue();
    }

    private static SimulationEvent assignment(SimulationReport report, int taskId) {
        return oneMain(report, SimulationEventType.DATAFLOW_VM_ASSIGNED, null, taskId);
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
            assertTrue(match == null, "duplicate " + type + " for Task " + taskId);
            match = event;
        }
        assertNotNull(match, "missing " + type + " for Task " + taskId);
        return match;
    }

    private static FileLifecycleEvent oneLifecycle(
            SimulationReport report, FileLifecycleEvent.Type type, Integer jobId) {
        List<FileLifecycleEvent> source = report.getStorageLifecycleEvidence() == null
                ? report.getFileLifecycleEvidence().getEvents()
                : report.getStorageLifecycleEvidence().getCapture().getEvents();
        FileLifecycleEvent match = null;
        for (FileLifecycleEvent event : source) {
            if (event.getType() != type
                    || (jobId != null && event.getPayload().get("jobId").getAsInt() != jobId.intValue())) {
                continue;
            }
            assertTrue(match == null, "duplicate lifecycle " + type);
            match = event;
        }
        assertNotNull(match, "missing lifecycle " + type);
        return match;
    }

    private static SimulationReport.JobOutcome successfulJob(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getTaskIds().contains(taskId) && job.getStatus() == Cloudlet.SUCCESS) {
                return job;
            }
        }
        throw new AssertionError("Missing successful Job for Task " + taskId);
    }

    private static SimulationReport.TaskOutcome successfulTask(SimulationReport report, int taskId) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId && task.getTaskStatus() == Cloudlet.SUCCESS) {
                return task;
            }
        }
        throw new AssertionError("Missing successful Task " + taskId);
    }

    private static SimulationReport.TaskOutcome task(SimulationReport report, int taskId, int jobId) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId && task.getJobId() == jobId) {
                return task;
            }
        }
        throw new AssertionError("Missing Task " + taskId + " on Job " + jobId);
    }

    private static void assertClose(double expected, double actual) {
        assertEquals(
                expected,
                actual,
                EPSILON * Math.max(1.0, Math.abs(expected)),
                "expected=" + expected + " actual=" + actual);
    }
}
