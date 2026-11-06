package org.workflowsim.utils;

/** Explicit Reshi rank table and historical task-runtime inputs for one experiment. */
public final class ReshiConfig {

    private final ReshiRankTable rankTable;
    private final ReshiTaskHistory taskHistory;
    private final ReshiPredictionErrorModel predictionErrorModel;
    private final double predictionErrorFraction;

    public ReshiConfig(ReshiRankTable rankTable, ReshiTaskHistory taskHistory) {
        this(rankTable, taskHistory, null, 0.0);
    }

    public ReshiConfig(
            ReshiRankTable rankTable,
            ReshiTaskHistory taskHistory,
            ReshiPredictionErrorModel predictionErrorModel,
            double predictionErrorFraction) {
        if (rankTable == null || taskHistory == null) {
            throw new IllegalArgumentException("Reshi requires both rank table and task history");
        }
        if (predictionErrorFraction < 0.0
                || !Double.isFinite(predictionErrorFraction)
                || (predictionErrorFraction > 0.0 && predictionErrorModel == null)) {
            throw new IllegalArgumentException("Reshi prediction error requires a declared distribution and fraction");
        }
        this.rankTable = rankTable;
        this.taskHistory = taskHistory;
        this.predictionErrorModel = predictionErrorModel;
        this.predictionErrorFraction = predictionErrorFraction;
    }

    public ReshiRankTable getRankTable() {
        return rankTable;
    }

    public ReshiTaskHistory getTaskHistory() {
        return taskHistory;
    }

    public ReshiPredictionErrorModel getPredictionErrorModel() {
        return predictionErrorModel;
    }

    public double getPredictionErrorFraction() {
        return predictionErrorFraction;
    }
}
