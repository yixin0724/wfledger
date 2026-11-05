package org.workflowsim.planning.cawosched;

import org.workflowsim.planning.timed.PostPlanningOptimizer;

/** The sixteen declared CaWoSched score/weight/refinement/local-search combinations. */
public enum CaWoSchedVariant {
    SLACK(CaWoSchedPlanner.Score.SLACK, false, false, false),
    SLACK_R(CaWoSchedPlanner.Score.SLACK, false, true, false),
    SLACK_W(CaWoSchedPlanner.Score.SLACK, true, false, false),
    SLACK_WR(CaWoSchedPlanner.Score.SLACK, true, true, false),
    SLACK_LS(CaWoSchedPlanner.Score.SLACK, false, false, true),
    SLACK_R_LS(CaWoSchedPlanner.Score.SLACK, false, true, true),
    SLACK_W_LS(CaWoSchedPlanner.Score.SLACK, true, false, true),
    SLACK_WR_LS(CaWoSchedPlanner.Score.SLACK, true, true, true),
    PRESSURE(CaWoSchedPlanner.Score.PRESSURE, false, false, false),
    PRESSURE_R(CaWoSchedPlanner.Score.PRESSURE, false, true, false),
    PRESSURE_W(CaWoSchedPlanner.Score.PRESSURE, true, false, false),
    PRESSURE_WR(CaWoSchedPlanner.Score.PRESSURE, true, true, false),
    PRESSURE_LS(CaWoSchedPlanner.Score.PRESSURE, false, false, true),
    PRESSURE_R_LS(CaWoSchedPlanner.Score.PRESSURE, false, true, true),
    PRESSURE_W_LS(CaWoSchedPlanner.Score.PRESSURE, true, false, true),
    PRESSURE_WR_LS(CaWoSchedPlanner.Score.PRESSURE, true, true, true);

    private final CaWoSchedPlanner.Score score;
    private final boolean weighted;
    private final boolean refined;
    private final boolean localSearch;

    CaWoSchedVariant(CaWoSchedPlanner.Score score, boolean weighted, boolean refined, boolean localSearch) {
        this.score = score;
        this.weighted = weighted;
        this.refined = refined;
        this.localSearch = localSearch;
    }

    public CaWoSchedPlanner.Score getScore() {
        return score;
    }

    public boolean isWeighted() {
        return weighted;
    }

    public boolean isRefined() {
        return refined;
    }

    public boolean isLocalSearch() {
        return localSearch;
    }

    public PostPlanningOptimizer optimizer() {
        return new CaWoSchedOptimizer(this);
    }
}
