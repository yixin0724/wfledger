package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable audit record of one maintained shared-storage static DAG plan.
 *
 * <p>The trace records the planner's model-level decision quantities.
 * {@code plannedStartSeconds} is the start of the Job envelope before data
 * stage-in; {@code plannedComputeStartSeconds} is the start of the logical Task
 * compute window after the CloudSim integer-MI stage-in conversion. It is not a
 * production execution trace, nor a network-calibrated schedule.</p>
 */
public final class SharedStorageDagPlanTrace {

    private final String strategy;
    private final double stageInFinishSeconds;
    private final Map<Integer, TaskPlan> taskPlans;
    private final List<Integer> criticalPathTaskIds;
    private final Integer criticalProcessorVmId;

    SharedStorageDagPlanTrace(
            String strategy,
            double stageInFinishSeconds,
            Map<Integer, TaskPlan> taskPlans,
            List<Integer> criticalPathTaskIds,
            Integer criticalProcessorVmId) {
        this.strategy = strategy;
        this.stageInFinishSeconds = stageInFinishSeconds;
        this.taskPlans = Collections.unmodifiableMap(new LinkedHashMap<Integer, TaskPlan>(taskPlans));
        this.criticalPathTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(criticalPathTaskIds));
        this.criticalProcessorVmId = criticalProcessorVmId;
    }

    public String getStrategy() {
        return strategy;
    }

    public double getStageInFinishSeconds() {
        return stageInFinishSeconds;
    }

    public Map<Integer, TaskPlan> getTaskPlans() {
        return taskPlans;
    }

    public TaskPlan getTaskPlan(int taskId) {
        return taskPlans.get(taskId);
    }

    /**
     * Empty for HEFT, DLS, ETF, and PEFT; for CPOP the critical path is
     * recorded in entry-to-exit order.
     */
    public List<Integer> getCriticalPathTaskIds() {
        return criticalPathTaskIds;
    }

    /**
     * HEFT, DLS, ETF, and PEFT make no CPOP critical processor decision, so
     * this returns {@code null}.
     */
    public Integer getCriticalProcessorVmId() {
        return criticalProcessorVmId;
    }

    /** Immutable model-level decision record for a single Task. */
    public static final class TaskPlan {

        private final int taskId;
        private final int vmId;
        private final double upwardRankSeconds;
        private final Double cpopDownwardRankSeconds;
        private final Double cpopPrioritySeconds;
        private final Double dlsDynamicLevelAtSelection;
        private final Integer dlsSelectionOrder;
        private final Double etfEarliestStartAtSelection;
        private final Integer etfSelectionOrder;
        private final Double peftRankOctSeconds;
        private final Double peftOptimisticCostAtSelectedVmSeconds;
        private final Double peftEftPlusOptimisticCostSeconds;
        private final Integer peftSelectionOrder;
        private final double plannedStartSeconds;
        private final double plannedFinishSeconds;
        private final double plannedDataStageInSeconds;

        TaskPlan(
                int taskId,
                int vmId,
                double upwardRankSeconds,
                Double cpopDownwardRankSeconds,
                Double cpopPrioritySeconds,
                Double dlsDynamicLevelAtSelection,
                Integer dlsSelectionOrder,
                Double etfEarliestStartAtSelection,
                Integer etfSelectionOrder,
                Double peftRankOctSeconds,
                Double peftOptimisticCostAtSelectedVmSeconds,
                Double peftEftPlusOptimisticCostSeconds,
                Integer peftSelectionOrder,
                double plannedStartSeconds,
                double plannedFinishSeconds,
                double plannedDataStageInSeconds) {
            this.taskId = taskId;
            this.vmId = vmId;
            this.upwardRankSeconds = upwardRankSeconds;
            this.cpopDownwardRankSeconds = cpopDownwardRankSeconds;
            this.cpopPrioritySeconds = cpopPrioritySeconds;
            this.dlsDynamicLevelAtSelection = dlsDynamicLevelAtSelection;
            this.dlsSelectionOrder = dlsSelectionOrder;
            this.etfEarliestStartAtSelection = etfEarliestStartAtSelection;
            this.etfSelectionOrder = etfSelectionOrder;
            this.peftRankOctSeconds = peftRankOctSeconds;
            this.peftOptimisticCostAtSelectedVmSeconds = peftOptimisticCostAtSelectedVmSeconds;
            this.peftEftPlusOptimisticCostSeconds = peftEftPlusOptimisticCostSeconds;
            this.peftSelectionOrder = peftSelectionOrder;
            this.plannedStartSeconds = plannedStartSeconds;
            this.plannedFinishSeconds = plannedFinishSeconds;
            this.plannedDataStageInSeconds = plannedDataStageInSeconds;
        }

        public int getTaskId() {
            return taskId;
        }

        public int getVmId() {
            return vmId;
        }

        public double getUpwardRankSeconds() {
            return upwardRankSeconds;
        }

        /** Provided only by CPOP traces; other strategies return {@code null}. */
        public Double getCpopDownwardRankSeconds() {
            return cpopDownwardRankSeconds;
        }

        /** Provided only by CPOP traces; other strategies return {@code null}. */
        public Double getCpopPrioritySeconds() {
            return cpopPrioritySeconds;
        }

        /** Provided only by DLS traces; other strategies return {@code null}. */
        public Double getDlsDynamicLevelAtSelection() {
            return dlsDynamicLevelAtSelection;
        }

        /** 1-based DLS selection order; non-DLS strategies return {@code null}. */
        public Integer getDlsSelectionOrder() {
            return dlsSelectionOrder;
        }

        /** Provided only by ETF traces; other strategies return {@code null}. */
        public Double getEtfEarliestStartAtSelection() {
            return etfEarliestStartAtSelection;
        }

        /** 1-based ETF selection order; non-ETF strategies return {@code null}. */
        public Integer getEtfSelectionOrder() {
            return etfSelectionOrder;
        }

        /** VM-averaged optimistic-cost rank for compatibility; provided only by PEFT traces. */
        public Double getPeftRankOctSeconds() {
            return peftRankOctSeconds;
        }

        /** OCT on the selected VM; provided only by PEFT traces. */
        public Double getPeftOptimisticCostAtSelectedVmSeconds() {
            return peftOptimisticCostAtSelectedVmSeconds;
        }

        /** Selected PEFT objective value, that is, inserted EFT plus OCT; provided only by PEFT traces. */
        public Double getPeftEftPlusOptimisticCostSeconds() {
            return peftEftPlusOptimisticCostSeconds;
        }

        /** 1-based PEFT selection order; other strategies return {@code null}. */
        public Integer getPeftSelectionOrder() {
            return peftSelectionOrder;
        }

        /** Planned start of the Job envelope, before the modeled data stage-in. */
        public double getPlannedStartSeconds() {
            return plannedStartSeconds;
        }

        /** Planned finish of the Job envelope, including the modeled data stage-in and computation. */
        public double getPlannedFinishSeconds() {
            return plannedFinishSeconds;
        }

        /** Effective stage-in duration after the CloudSim integer-MI conversion. */
        public double getPlannedDataStageInSeconds() {
            return plannedDataStageInSeconds;
        }

        /** Start of the logical Task compute window after the effective stage-in completes. */
        public double getPlannedComputeStartSeconds() {
            return plannedStartSeconds + plannedDataStageInSeconds;
        }
    }
}
