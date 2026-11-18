package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.SimulationConfig;

class MetamorphicGapIntegrationTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void explicitZeroArrivalsAreBitwiseIdenticalToOmittedArrivals() throws Exception {
        Path workflow = chainDax();
        SimulationReport explicit = run(
                workflow,
                SimulationConfig.builder(workflow.toString(), 2)
                        .workflowArrivalSeconds(java.util.Arrays.asList(0.0))
                        .build());
        SimulationReport omitted =
                run(workflow, SimulationConfig.builder(workflow.toString(), 2).build());

        assertEventStreamsIdentical(explicit, omitted);
        assertTaskWindowsIdentical(explicit, omitted);
        assertEquals(
                explicit.getMetrics().getMakespanSeconds(),
                omitted.getMetrics().getMakespanSeconds(),
                0.0,
                "R5 contract: all-zero arrivals and omitted arrivals must agree bitwise on makespan");
        assertEquals(
                explicit.getActualVmHostAssignments(),
                omitted.getActualVmHostAssignments(),
                "VM placement must be identical");
    }

    @Test
    void doublingScaleAndMipsKeepsEveryTaskComputeDurationExact() throws Exception {
        Path workflow = chainDax();
        SimulationReport base = run(
                workflow,
                SimulationConfig.builder(workflow.toString(), 2)
                        .runtimeScale(1.0)
                        .runtimeReferenceMips(1000.0)
                        .build());
        SimulationReport scaled = run(
                workflow,
                SimulationConfig.builder(workflow.toString(), 2)
                        .runtimeScale(2.0)
                        .runtimeReferenceMips(1000.0)
                        .build(),
                2.0);

        List<SimulationReport.TaskOutcome> baseTasks = base.getTasks();
        List<SimulationReport.TaskOutcome> scaledTasks = scaled.getTasks();
        assertEquals(baseTasks.size(), scaledTasks.size(), "task count must agree");
        for (int i = 0; i < baseTasks.size(); i++) {
            SimulationReport.TaskOutcome left = baseTasks.get(i);
            SimulationReport.TaskOutcome right = scaledTasks.get(i);
            assertEquals(left.getTaskId(), right.getTaskId());
            double leftDuration = left.getFinishTime() - left.getStartTime();
            double rightDuration = right.getFinishTime() - right.getStartTime();
            long leftMi = left.getLengthMi();
            long rightMi = right.getLengthMi();
            assertEquals(
                    leftMi * 2, rightMi, "task " + left.getTaskId() + " MI must double with scale (100 s x 1000 x 2)");
            assertEquals(
                    leftDuration,
                    rightDuration,
                    0.0,
                    "task " + left.getTaskId() + " compute duration must stay exactly unchanged"
                            + " (MI x 2 and MIPS x 2 cancel)");
        }
    }

    private SimulationReport run(Path workflow, SimulationConfig config) throws Exception {
        return run(workflow, config, 1.0);
    }

    private SimulationReport run(Path workflow, SimulationConfig config, double mipsFactor) throws Exception {
        PlatformProfile platform;
        if (mipsFactor == 1.0) {
            platform = PlatformProfiles.homogeneousLocal("metamorphic-base", 2);
        } else {
            platform = PlatformProfiles.heterogeneousLocal(
                    "metamorphic-scaled", java.util.Arrays.asList(1000.0 * mipsFactor, 1000.0 * mipsFactor));
        }
        return new SimulationRunner().run(config, platform);
    }

    private static void assertEventStreamsIdentical(SimulationReport left, SimulationReport right) {
        List<SimulationEvent> a = left.getEvents();
        List<SimulationEvent> b = right.getEvents();
        assertEquals(a.size(), b.size(), "total event count must agree");
        for (int i = 0; i < a.size(); i++) {
            SimulationEvent first = a.get(i);
            SimulationEvent second = b.get(i);
            assertEquals(first.getSequence(), second.getSequence(), "event sequence number");
            assertEquals(first.getType(), second.getType(), "event type@" + i);
            assertEquals(first.getSimulationTime(), second.getSimulationTime(), 0.0, "event time@" + i);
            assertEquals(first.getJobId(), second.getJobId(), "event job@" + i);
        }
    }

    private static void assertTaskWindowsIdentical(SimulationReport left, SimulationReport right) {
        List<SimulationReport.TaskOutcome> a = left.getTasks();
        List<SimulationReport.TaskOutcome> b = right.getTasks();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).getTaskId(), b.get(i).getTaskId());
            assertEquals(
                    a.get(i).getStartTime(), b.get(i).getStartTime(), 0.0, "task start time must be bitwise identical");
            assertEquals(
                    a.get(i).getFinishTime(),
                    b.get(i).getFinishTime(),
                    0.0,
                    "task finish time must be bitwise identical");
            assertEquals(a.get(i).getVmId(), b.get(i).getVmId(), "task VM dispatch must agree");
        }
    }

    private Path chainDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        for (int id = 1; id <= 4; id++) {
            dax.append("  <job id=\"t").append(id).append("\" name=\"chain\" runtime=\"100\"/>\n");
        }
        for (int id = 2; id <= 4; id++) {
            dax.append("  <child ref=\"t").append(id).append("\">\n");
            dax.append("    <parent ref=\"t").append(id - 1).append("\"/>\n");
            dax.append("  </child>\n");
        }
        dax.append("</adag>\n");
        Path path = tempDir.resolve("chain.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
