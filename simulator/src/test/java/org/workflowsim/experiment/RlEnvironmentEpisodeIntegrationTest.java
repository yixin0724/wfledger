package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.rl.EarliestFinishGreedyPolicy;
import org.workflowsim.rl.RlDecision;
import org.workflowsim.rl.RlEnvironment;
import org.workflowsim.rl.RlEpisodeResult;
import org.workflowsim.rl.RlPolicy;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class RlEnvironmentEpisodeIntegrationTest {

    private static final double GREEDY_EPISODE_GOLDEN_MAKESPAN = 5116.1;

    @Test
    void rlEpisodeIsDeterministicAndRewardIsNegativeMakespan() throws Exception {
        RlEpisodeResult first = runEpisode(new EarliestFinishGreedyPolicy());
        RlEpisodeResult second = runEpisode(new EarliestFinishGreedyPolicy());
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0, "deterministic episode must be bit-identical");
        assertEquals(GREEDY_EPISODE_GOLDEN_MAKESPAN, first.getMakespan(), 0.0, "greedy-episode golden makespan");
        assertEquals(-first.getMakespan(), first.getReward(), 0.0, "reward contract: reward = -makespan");
        assertTrue(first.getStepCount() >= 1, "at least one policy query");
        assertNotNull(first.getReport(), "the episode must carry a complete evidence report");
    }

    @Test
    void decisionTraceMatchesFinalReportAssignments() throws Exception {
        RlEpisodeResult episode = runEpisode(new EarliestFinishGreedyPolicy());
        SimulationReport report = episode.getReport();

        assertEquals(report.getTotalJobs(), episode.getDecisions().size(), "decisions cover each Job exactly once");
        Set<Integer> seen = new HashSet<Integer>();
        for (RlDecision decision : episode.getDecisions()) {
            assertTrue(seen.add(decision.getJobId()), "Job " + decision.getJobId() + " is dispatched twice");
            SimulationReport.JobOutcome outcome = jobById(report, decision.getJobId());
            assertEquals(decision.getVmId(), outcome.getVmId(), "decision VM must equal the report assignment");
            assertTrue(decision.getTime() >= 0.0, "decision time is non-negative");
        }
    }

    @Test
    void rlPolicyWithoutEnvironmentFailsExplicitly() throws Exception {

        SimulationConfigurationException failure = assertThrows(
                SimulationConfigurationException.class, () -> new SimulationRunner().run(rlConfig(), paperPlatform()));
        assertTrue(
                chainMentions(failure, SimulationConfigurationException.class, "RlEnvironment"),
                "failure chain must point at RlEnvironment.runEpisode, actual: " + failure);
    }

    @Test
    void environmentRejectsNonRlSchedulingConfiguration() throws Exception {
        SimulationConfig fcfs = SimulationConfig.builder(workflowPath(), 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();
        SimulationConfigurationException rejected =
                assertThrows(SimulationConfigurationException.class, () -> new RlEnvironment()
                        .runEpisode(fcfs, paperPlatform(), new EarliestFinishGreedyPolicy()));
        assertTrue(rejected.getMessage().contains("RL_POLICY"), rejected.getMessage());
    }

    @Test
    void policyContractViolationsAbortTheEpisode() {

        IllegalStateException wrongLength = assertThrows(IllegalStateException.class, () -> new RlEnvironment()
                .runEpisode(rlConfig(), paperPlatform(), observation -> new int[0]));
        assertTrue(chainMentions(wrongLength, IllegalStateException.class, "action(s)"), wrongLength.toString());

        RlPolicy outOfRange = observation -> {
            int[] actions = new int[observation.getReadyJobs().size()];
            java.util.Arrays.fill(actions, observation.getVms().size());
            return actions;
        };
        IllegalStateException range = assertThrows(IllegalStateException.class, () -> new RlEnvironment()
                .runEpisode(rlConfig(), paperPlatform(), outOfRange));
        assertTrue(chainMentions(range, IllegalStateException.class, "out-of-range"), range.toString());

        SimulationExecutionException stalled =
                assertThrows(SimulationExecutionException.class, () -> new RlEnvironment()
                        .runEpisode(rlConfig(), paperPlatform(), observation -> {
                            int[] actions = new int[observation.getReadyJobs().size()];
                            java.util.Arrays.fill(actions, RlPolicy.NO_ASSIGNMENT);
                            return actions;
                        }));
        assertTrue(
                stalled.getMessage().contains("terminated")
                        || stalled.getMessage().contains("stalled"),
                stalled.getMessage());
    }

    private static boolean chainMentions(Throwable top, Class<? extends Throwable> type, String keyword) {
        Throwable current = top;
        while (current != null) {
            if (type.isInstance(current)
                    && current.getMessage() != null
                    && current.getMessage().contains(keyword)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RlEpisodeResult runEpisode(RlPolicy policy) throws Exception {
        Log.disable();
        return new RlEnvironment().runEpisode(rlConfig(), paperPlatform(), policy);
    }

    private static SimulationConfig rlConfig() throws Exception {
        return SimulationConfig.builder(workflowPath(), 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.RL_POLICY)
                .build();
    }

    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("rl-episode-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static SimulationReport.JobOutcome jobById(SimulationReport report, int jobId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getJobId() == jobId) {
                return job;
            }
        }
        throw new AssertionError("Report has no job " + jobId);
    }

    private static String workflowPath() throws Exception {
        java.net.URL url = RlEnvironmentEpisodeIntegrationTest.class.getResource("/dax/heft-paper-example.dax");
        if (url == null) {
            throw new IllegalStateException("Missing test resource /dax/heft-paper-example.dax");
        }
        return Paths.get(url.toURI()).toString();
    }
}
