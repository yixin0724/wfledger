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

/**
 * End-to-end episode acceptance for the R4 RL track: environment closed loop,
 * determinism, reward contract, decision-trace identity, and explicit failure on error
 * paths.
 *
 * <p>The fixture shares its source with the HEFT paper example
 * ({@code /dax/heft-paper-example.dax}, 3 VMs with mips=1.0) but runs on the online
 * scheduling track (INVALID planning layer, default SHARED file system, legacy data
 * movement model): RL_POLICY belongs to the ready-batch online layer together with
 * FCFS/MINMIN and does not depend on any static mapping premise.</p>
 */
class RlEnvironmentEpisodeIntegrationTest {

    /**
     * Episode makespan of the greedy baseline on the paper-example fixture (a deterministic
     * golden value).
     *
     * <p>This value is not comparable with the 190.1 of the static HEFT reproduction: this
     * episode runs on the online scheduling track (INVALID planning, legacy data movement
     * model, default SHARED file system), where the DAX runtime maps directly to MI with no
     * cost-matrix guidance -- it pins down the closed-loop behavior of the RL environment,
     * not the paper result.</p>
     */
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
        // Every Job is dispatched exactly once, and the decision trace agrees element by
        // element with the final VM assignment in the report.
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
        // Running RL_POLICY directly through SimulationRunner, bypassing RlEnvironment:
        // the registry has no policy, so this must fail explicitly and point at the correct
        // entry point. After the R8 audit fix (P1-7), configuration-class exceptions are no
        // longer wrapped into an IllegalStateException about "scheduling algorithm failure"
        // but reach the caller as SimulationConfigurationException.
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
        // Wrong action-array length: a contract violation must abort (the scheduler wraps it
        // into a uniform ISE, and the real cause lives in the cause chain).
        IllegalStateException wrongLength = assertThrows(IllegalStateException.class, () -> new RlEnvironment()
                .runEpisode(rlConfig(), paperPlatform(), observation -> new int[0]));
        assertTrue(chainMentions(wrongLength, IllegalStateException.class, "action(s)"), wrongLength.toString());
        // Action out of range: explicit failure.
        RlPolicy outOfRange = observation -> {
            int[] actions = new int[observation.getReadyJobs().size()];
            java.util.Arrays.fill(actions, observation.getVms().size());
            return actions;
        };
        IllegalStateException range = assertThrows(IllegalStateException.class, () -> new RlEnvironment()
                .runEpisode(rlConfig(), paperPlatform(), outOfRange));
        assertTrue(chainMentions(range, IllegalStateException.class, "out-of-range"), range.toString());
        // Never assigning: the stall is intercepted explicitly by the platform watchdog
        // rather than producing a silently incomplete report.
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

    /**
     * Whether the cause chain contains an exception of the given type whose message contains
     * the keyword.
     */
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

    /** Platform isomorphic to the HEFT paper reproduction: 3 VMs with mips=1.0. */
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
