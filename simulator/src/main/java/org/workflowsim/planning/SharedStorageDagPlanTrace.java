package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public List<Integer> getCriticalPathTaskIds() {
        return criticalPathTaskIds;
    }

    public Integer getCriticalProcessorVmId() {
        return criticalProcessorVmId;
    }

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

        public Double getCpopDownwardRankSeconds() {
            return cpopDownwardRankSeconds;
        }

        public Double getCpopPrioritySeconds() {
            return cpopPrioritySeconds;
        }

        public Double getDlsDynamicLevelAtSelection() {
            return dlsDynamicLevelAtSelection;
        }

        public Integer getDlsSelectionOrder() {
            return dlsSelectionOrder;
        }

        public Double getEtfEarliestStartAtSelection() {
            return etfEarliestStartAtSelection;
        }

        public Integer getEtfSelectionOrder() {
            return etfSelectionOrder;
        }

        public Double getPeftRankOctSeconds() {
            return peftRankOctSeconds;
        }

        public Double getPeftOptimisticCostAtSelectedVmSeconds() {
            return peftOptimisticCostAtSelectedVmSeconds;
        }

        public Double getPeftEftPlusOptimisticCostSeconds() {
            return peftEftPlusOptimisticCostSeconds;
        }

        public Integer getPeftSelectionOrder() {
            return peftSelectionOrder;
        }

        public double getPlannedStartSeconds() {
            return plannedStartSeconds;
        }

        public double getPlannedFinishSeconds() {
            return plannedFinishSeconds;
        }

        public double getPlannedDataStageInSeconds() {
            return plannedDataStageInSeconds;
        }

        public double getPlannedComputeStartSeconds() {
            return plannedStartSeconds + plannedDataStageInSeconds;
        }
    }
}
