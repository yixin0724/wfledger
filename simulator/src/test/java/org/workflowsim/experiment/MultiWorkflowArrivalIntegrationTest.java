package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

class MultiWorkflowArrivalIntegrationTest {

    private static final double SIMULTANEOUS_GOLDEN_MAKESPAN = 8118.1;

    private static final double STAGGERED_GOLDEN_MAKESPAN = 8118.1;

    private static final double STAGGERED_GOLDEN_FLOW_W1 = 8068.1;

    @Test
    void simultaneousArrivalsShareVmsAndReportPerWorkflowOutcomes() throws Exception {
        SimulationReport report = run(Arrays.asList(0.0, 0.0));
        assertEquals(SIMULTANEOUS_GOLDEN_MAKESPAN, report.getMakespan(), 0.0, "golden makespan for all-zero arrival");
        List<SimulationReport.WorkflowOutcome> outcomes = report.getWorkflowOutcomes();
        assertEquals(2, outcomes.size(), "each of the two inputs yields one per-workflow outcome");
        assertEquals(10, outcomes.get(0).getTaskCount());
        assertEquals(10, outcomes.get(1).getTaskCount());
        assertEquals(1, outcomes.get(0).getFirstTaskId());
        assertEquals(10, outcomes.get(0).getLastTaskId());
        assertEquals(11, outcomes.get(1).getFirstTaskId());
        assertEquals(20, outcomes.get(1).getLastTaskId());
        assertEquals(0.0, outcomes.get(0).getArrivalSecond(), 0.0);
        assertEquals(0.0, outcomes.get(1).getArrivalSecond(), 0.0);

        assertEquals(
                outcomes.get(0).getLastSuccessFinishSecond(), outcomes.get(0).getFlowTimeSeconds(), 0.0);
        assertTrue(outcomes.get(0).getFlowTimeSeconds() > 0.0);
        assertTrue(outcomes.get(1).getFlowTimeSeconds() > 0.0);
    }

    @Test
    void staggeredArrivalGatesSecondWorkflowAndReportsFlowTime() throws Exception {
        SimulationReport report = run(Arrays.asList(0.0, 50.0));
        List<SimulationReport.WorkflowOutcome> outcomes = report.getWorkflowOutcomes();
        assertEquals(STAGGERED_GOLDEN_MAKESPAN, report.getMakespan(), 0.0, "golden makespan for staggered arrival");
        assertEquals(50.0, outcomes.get(1).getArrivalSecond(), 0.0);
        assertEquals(
                STAGGERED_GOLDEN_FLOW_W1,
                outcomes.get(1).getFlowTimeSeconds(),
                0.0,
                "golden flow time of" + " workflow 1");
        assertEquals(
                outcomes.get(1).getLastSuccessFinishSecond() - 50.0,
                outcomes.get(1).getFlowTimeSeconds(),
                0.0,
                "flow time = latest finish - submission instant");

        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() >= 11 && task.getTaskId() <= 20) {
                assertTrue(
                        task.getStartTime() >= 50.0,
                        "task " + task.getTaskId() + " started before its arrival instant: " + task.getStartTime());
            }
        }

        SimulationReport simultaneous = run(Arrays.asList(0.0, 0.0));
        assertTrue(
                report.getMakespan() >= simultaneous.getMakespan(),
                "staggered arrival must not complete" + " earlier than simultaneous submission");

        assertEquals(
                simultaneous.getWorkflowOutcomes().get(0).getLastSuccessFinishSecond(),
                outcomes.get(0).getLastSuccessFinishSecond(),
                0.0,
                "an early workflow must not be deferred by the arrival instant of a later workflow");
    }

    @Test
    void arrivalIsDeterministicAndEmitsArrivalEvidence() throws Exception {
        SimulationReport first = run(Arrays.asList(0.0, 50.0));
        SimulationReport second = run(Arrays.asList(0.0, 50.0));
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0, "determinism");
        assertEquals(
                first.getWorkflowOutcomes().get(1).getFlowTimeSeconds(),
                second.getWorkflowOutcomes().get(1).getFlowTimeSeconds(),
                0.0);

        int arrived0 = 0;
        int arrived1 = 0;
        for (SimulationEvent event : first.getEvents()) {
            if (event.getType() == SimulationEventType.WORKFLOW_ARRIVED) {
                Object index = event.getAttributes().get("workflowIndex");
                Object arrival = event.getAttributes().get("arrivalSecond");
                if (Integer.valueOf(0).equals(index)) {
                    arrived0++;
                    assertEquals(0.0, ((Number) arrival).doubleValue(), 0.0);
                    assertTrue(event.getSimulationTime() >= 0.0);
                } else if (Integer.valueOf(1).equals(index)) {
                    arrived1++;
                    assertEquals(50.0, ((Number) arrival).doubleValue(), 0.0);
                    assertTrue(
                            event.getSimulationTime() >= 50.0,
                            "arrival evidence must not be timestamped" + " before the submission instant");
                }
            }
        }
        assertEquals(1, arrived0, "exactly one arrival evidence event for workflow 0");
        assertEquals(1, arrived1, "exactly one arrival evidence event for workflow 1");
    }

    @Test
    void singleInputZeroArrivalKeepsHistoricalTraceWithoutArrivalEvents() throws Exception {
        SimulationConfig config = SimulationConfig.builder(workflowPath(), 3).build();
        SimulationReport report = new SimulationRunner().run(config, paperPlatform());
        for (SimulationEvent event : report.getEvents()) {
            assertFalse(
                    event.getType() == SimulationEventType.WORKFLOW_ARRIVED,
                    "a single-input all-zero arrival"
                            + " must not emit new events (the historical trace stays bit-identical)");
        }
        List<SimulationReport.WorkflowOutcome> outcomes = report.getWorkflowOutcomes();
        assertEquals(1, outcomes.size());
        assertEquals(0.0, outcomes.get(0).getArrivalSecond(), 0.0);
        assertEquals(10, outcomes.get(0).getTaskCount());
        assertEquals(
                outcomes.get(0).getLastSuccessFinishSecond(), outcomes.get(0).getFlowTimeSeconds(), 0.0);
    }

    private static SimulationReport run(List<Double> arrivals) throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(Arrays.asList(workflowPath(), workflowPath()), 3)
                .workflowArrivalSeconds(arrivals)
                .build();
        return new SimulationRunner().run(config, paperPlatform());
    }

    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("multi-arrival-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static String workflowPath() throws Exception {
        java.net.URL url = MultiWorkflowArrivalIntegrationTest.class.getResource("/dax/heft-paper-example.dax");
        if (url == null) {
            throw new IllegalStateException("Missing test resource /dax/heft-paper-example.dax");
        }
        return Paths.get(url.toURI()).toString();
    }
}
