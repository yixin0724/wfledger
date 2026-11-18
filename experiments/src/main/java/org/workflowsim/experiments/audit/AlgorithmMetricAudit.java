package org.workflowsim.experiments.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiment.SimulationEvent;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

public final class AlgorithmMetricAudit {

    private AlgorithmMetricAudit() {}

    public static AuditResult run(SimulationConfig config, PlatformProfile platform, String label)
            throws SimulationExecutionException {
        SimulationReport report = new SimulationRunner().run(config, platform);
        Map<Integer, Integer> mapping = new LinkedHashMap<Integer, Integer>();
        Map<Integer, SimulationReport.TaskOutcome> successfulTasks =
                new LinkedHashMap<Integer, SimulationReport.TaskOutcome>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            mapping.put(task.getTaskId(), task.getVmId());
            if (task.getTaskStatus() == org.cloudbus.cloudsim.Cloudlet.SUCCESS) {
                SimulationReport.TaskOutcome previous = successfulTasks.get(task.getTaskId());
                if (previous == null || task.getFinishTime() < previous.getFinishTime()) {
                    successfulTasks.put(task.getTaskId(), task);
                }
            }
        }
        double dependencyResidual = 0.0;
        for (SimulationReport.TaskNode node : report.getWorkflowGraph()) {
            SimulationReport.TaskOutcome child = successfulTasks.get(node.getTaskId());
            for (Integer parentId : node.getParentIds()) {
                SimulationReport.TaskOutcome parent = successfulTasks.get(parentId);
                if (child != null && parent != null) {
                    double residual = parent.getFinishTime() - child.getStartTime();
                    dependencyResidual = Math.max(dependencyResidual, residual);
                }
            }
        }
        double independentEnergy = 0.0;
        double expectedPeak = 0.0;
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            double maximum = spec.getMaxPowerWatts();
            for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
                maximum = Math.max(maximum, tier.getMaxPowerWatts());
            }
            expectedPeak += maximum;
        }
        double peakResidual =
                Math.abs(expectedPeak - report.getEnergyAccounting().getPeakPowerWatts());
        for (SimulationReport.VmSummary vm : report.getVmSummaries().values()) {
            PlatformProfile.VmSpec spec = findVm(platform, vm.getVmId());
            if (spec.hasFrequencyTiers()) {
                independentEnergy += spec.defaultFrequencyTier().getIdlePowerWatts() * report.getMakespan();
            } else {
                independentEnergy += spec.getIdlePowerWatts() * report.getMakespan()
                        + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts()) * vm.getTotalCpuTime();
            }
        }
        double modeledCost = 0.0;
        double independentCost = 0.0;
        double costResidual = 0.0;
        double lastJobFinish = 0.0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            PlatformProfile.VmSpec spec = findVm(platform, job.getVmId());
            if (spec.hasFrequencyTiers()) {
                PlatformProfile.FrequencyTier tier = tierFor(spec, job.getTierMips());
                independentEnergy +=
                        (tier.getMaxPowerWatts() - spec.defaultFrequencyTier().getIdlePowerWatts()) * job.getCpuTime();
            }
            modeledCost += job.getModeledProcessingCost();
            PlatformProfile.CostSpec costs = config.getCostModel() == org.workflowsim.utils.Parameters.CostModel.VM
                    ? spec.getCosts()
                    : platform.getCosts();
            if (costs != null) {
                independentCost += costs.getCpuPerSecond() * job.getCpuTime()
                        + costs.getBandwidth() * job.getModeledDeclaredFileBytes() / 1000000.0;
            }
            lastJobFinish = Math.max(lastJobFinish, job.getFinishTime());
        }
        costResidual = Math.abs(modeledCost - independentCost);
        double energyResidual =
                Math.abs(independentEnergy - report.getEnergyAccounting().getTotalEnergyJoules());
        double lastEvent = 0.0;
        for (SimulationEvent event : report.getEvents()) {
            lastEvent = Math.max(lastEvent, event.getSimulationTime());
        }
        double makespanResidual = Math.max(
                Math.abs(lastEvent - report.getMakespan()), Math.max(0.0, lastJobFinish - report.getMakespan()));
        double tailResidual = 0.0;
        if (report.getLogicalTaskCompletionSeconds() != null && report.getTerminalLifecycleTailSeconds() != null) {
            tailResidual = Math.abs((report.getMakespan() - report.getLogicalTaskCompletionSeconds())
                    - report.getTerminalLifecycleTailSeconds());
        }
        return new AuditResult(
                label,
                report.getMakespan(),
                report.getEnergyAccounting().getTotalEnergyJoules(),
                modeledCost,
                energyResidual,
                peakResidual,
                costResidual,
                makespanResidual,
                tailResidual,
                Math.max(0.0, dependencyResidual),
                mapping,
                report.isWorkflowCompletedSuccessfully());
    }

    private static PlatformProfile.FrequencyTier tierFor(PlatformProfile.VmSpec spec, Double tierMips) {
        if (tierMips == null) {
            return spec.defaultFrequencyTier();
        }
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (Double.compare(tier.getMips(), tierMips) == 0) {
                return tier;
            }
        }
        throw new IllegalArgumentException("No declared tier for metric recomputation " + tierMips);
    }

    private static PlatformProfile.VmSpec findVm(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalArgumentException("No platform VM for metric recomputation " + vmId);
    }

    public static final class AuditResult {
        private final String label;
        private final double makespanSeconds;
        private final double energyJoules;
        private final double modeledProcessingCost;
        private final double energyResidualJoules;
        private final double peakResidualWatts;
        private final double costResidual;
        private final double makespanResidualSeconds;
        private final double tailResidualSeconds;
        private final double dependencyResidualSeconds;
        private final Map<Integer, Integer> mapping;
        private final boolean completed;

        private AuditResult(
                String label,
                double makespanSeconds,
                double energyJoules,
                double modeledProcessingCost,
                double energyResidualJoules,
                double peakResidualWatts,
                double costResidual,
                double makespanResidualSeconds,
                double tailResidualSeconds,
                double dependencyResidualSeconds,
                Map<Integer, Integer> mapping,
                boolean completed) {
            this.label = label;
            this.makespanSeconds = makespanSeconds;
            this.energyJoules = energyJoules;
            this.modeledProcessingCost = modeledProcessingCost;
            this.energyResidualJoules = energyResidualJoules;
            this.peakResidualWatts = peakResidualWatts;
            this.costResidual = costResidual;
            this.makespanResidualSeconds = makespanResidualSeconds;
            this.tailResidualSeconds = tailResidualSeconds;
            this.dependencyResidualSeconds = dependencyResidualSeconds;
            this.mapping = Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(mapping));
            this.completed = completed;
        }

        public String getLabel() {
            return label;
        }

        public double getMakespanSeconds() {
            return makespanSeconds;
        }

        public double getEnergyJoules() {
            return energyJoules;
        }

        public double getModeledProcessingCost() {
            return modeledProcessingCost;
        }

        public double getEnergyResidualJoules() {
            return energyResidualJoules;
        }

        public double getPeakResidualWatts() {
            return peakResidualWatts;
        }

        public double getCostResidual() {
            return costResidual;
        }

        public double getMakespanResidualSeconds() {
            return makespanResidualSeconds;
        }

        public double getTailResidualSeconds() {
            return tailResidualSeconds;
        }

        public double getDependencyResidualSeconds() {
            return dependencyResidualSeconds;
        }

        public Map<Integer, Integer> getMapping() {
            return mapping;
        }

        public boolean isCompleted() {
            return completed;
        }
    }
}
