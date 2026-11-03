package org.workflowsim.rl;

import java.util.List;

import org.workflowsim.experiment.SimulationReport;

/**
 * Complete result of one RL episode: the simulation evidence report plus the episode statistics
 * (reward, step count, decision trace).
 *
 * <p>Reward contract: {@code reward = -makespan} in seconds. An episode is one complete
 * workflow simulation, so the reward is emitted only at the terminal state with no per-step
 * shaping -- makespan is the only objective quantity covered by regression on this track.</p>
 */
public final class RlEpisodeResult {

    private final SimulationReport report;
    private final List<RlDecision> decisions;
    private final int stepCount;

    RlEpisodeResult(SimulationReport report, List<RlDecision> decisions, int stepCount) {
        this.report = report;
        this.decisions = decisions;
        this.stepCount = stepCount;
    }

    /** @return the complete simulation evidence report of this episode */
    public SimulationReport getReport() {
        return report;
    }

    /** @return episode makespan in seconds */
    public double getMakespan() {
        return report.getMakespan();
    }

    /** @return episode reward = -makespan */
    public double getReward() {
        return -report.getMakespan();
    }

    /** @return number of policy queries (one step per ready-batch scheduling update) */
    public int getStepCount() {
        return stepCount;
    }

    /** @return decision trace of successful dispatches (insertion order, immutable) */
    public List<RlDecision> getDecisions() {
        return decisions;
    }
}
