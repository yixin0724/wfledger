package org.workflowsim.rl;

import java.util.List;

import org.workflowsim.experiment.SimulationReport;

public final class RlEpisodeResult {

    private final SimulationReport report;
    private final List<RlDecision> decisions;
    private final int stepCount;

    RlEpisodeResult(SimulationReport report, List<RlDecision> decisions, int stepCount) {
        this.report = report;
        this.decisions = decisions;
        this.stepCount = stepCount;
    }

    public SimulationReport getReport() {
        return report;
    }

    public double getMakespan() {
        return report.getMakespan();
    }

    public double getReward() {
        return -report.getMakespan();
    }

    public int getStepCount() {
        return stepCount;
    }

    public List<RlDecision> getDecisions() {
        return decisions;
    }
}
