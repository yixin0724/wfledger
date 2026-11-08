package org.workflowsim.experiments.comparison;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Executes compatible paper algorithms in controlled scenes without mixing physical models. */
public final class CrossPaperComparison {

    private CrossPaperComparison() {}

    public static List<RunResult> runSharedScene(Path workflow, PlatformProfile platform)
            throws SimulationExecutionException {
        Parameters.PlanningAlgorithm[] algorithms = {
            Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT,
            Parameters.PlanningAlgorithm.NETWORK_HEFT,
            Parameters.PlanningAlgorithm.GREEN_HEFT,
            Parameters.PlanningAlgorithm.MO_HEFT,
            Parameters.PlanningAlgorithm.IC_PCP
        };
        List<RunResult> results = new ArrayList<RunResult>();
        for (Parameters.PlanningAlgorithm algorithm : algorithms) {
            SimulationConfig config = SimulationConfig.builder(
                            workflow.toString(), platform.getVms().size())
                    .planningAlgorithm(algorithm)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .costModel(Parameters.CostModel.VM)
                    .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                    .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                    .deadline(10L)
                    .runtimeReferenceMips(1.0)
                    .randomSeed(7L)
                    .build();
            results.add(run(config, platform, algorithm.name()));
        }
        return Collections.unmodifiableList(results);
    }

    public static List<RunResult> runLocalScene(Path workflow, PlatformProfile platform)
            throws SimulationExecutionException {
        Parameters.PlanningAlgorithm[] algorithms = {
            Parameters.PlanningAlgorithm.LOCAL_HEFT, Parameters.PlanningAlgorithm.DHEFT
        };
        List<RunResult> results = new ArrayList<RunResult>();
        for (Parameters.PlanningAlgorithm algorithm : algorithms) {
            SimulationConfig config = SimulationConfig.builder(
                            workflow.toString(), platform.getVms().size())
                    .planningAlgorithm(algorithm)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .costModel(Parameters.CostModel.VM)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                    .runtimeReferenceMips(1.0)
                    .randomSeed(7L)
                    .build();
            results.add(run(config, platform, algorithm.name()));
        }
        return Collections.unmodifiableList(results);
    }

    private static RunResult run(SimulationConfig config, PlatformProfile platform, String label)
            throws SimulationExecutionException {
        SimulationReport report = new SimulationRunner().run(config, platform);
        double modeledCost = 0.0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            modeledCost += job.getModeledProcessingCost();
        }
        Map<Integer, Integer> mapping = new LinkedHashMap<Integer, Integer>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            mapping.put(task.getTaskId(), task.getVmId());
        }
        double energyResidual = 0.0;
        for (SimulationReport.VmSummary vm : report.getVmSummaries().values()) {
            PlatformProfile.VmSpec spec = findVm(platform, vm.getVmId());
            energyResidual += spec.getIdlePowerWatts() * report.getMakespan()
                    + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts()) * vm.getTotalCpuTime();
        }
        energyResidual = Math.abs(energyResidual - report.getEnergyAccounting().getTotalEnergyJoules());
        return new RunResult(
                label,
                report.getMakespan(),
                report.getEnergyAccounting().getTotalEnergyJoules(),
                modeledCost,
                energyResidual,
                null,
                mapping,
                report.isWorkflowCompletedSuccessfully());
    }

    private static PlatformProfile.VmSpec findVm(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalArgumentException("No VM declaration for energy recomputation " + vmId);
    }

    public static final class RunResult {
        private final String algorithm;
        private final double makespanSeconds;
        private final double energyJoules;
        private final double modeledProcessingCost;
        private final double energyResidualJoules;
        private final Double plannerCost;
        private final Map<Integer, Integer> mapping;
        private final boolean completed;

        private RunResult(
                String algorithm,
                double makespanSeconds,
                double energyJoules,
                double modeledProcessingCost,
                double energyResidualJoules,
                Double plannerCost,
                Map<Integer, Integer> mapping,
                boolean completed) {
            this.algorithm = algorithm;
            this.makespanSeconds = makespanSeconds;
            this.energyJoules = energyJoules;
            this.modeledProcessingCost = modeledProcessingCost;
            this.energyResidualJoules = energyResidualJoules;
            this.plannerCost = plannerCost;
            this.mapping = Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(mapping));
            this.completed = completed;
        }

        public String getAlgorithm() {
            return algorithm;
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

        public Double getPlannerCost() {
            return plannerCost;
        }

        /** Attaches a cost computed directly by the paper planner, not by runtime accounting. */
        public RunResult withPlannerCost(double value) {
            if (!(value >= 0.0) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Planner cost must be finite and non-negative");
            }
            return new RunResult(
                    algorithm,
                    makespanSeconds,
                    energyJoules,
                    modeledProcessingCost,
                    energyResidualJoules,
                    value,
                    mapping,
                    completed);
        }

        public Map<Integer, Integer> getMapping() {
            return mapping;
        }

        public boolean isCompleted() {
            return completed;
        }
    }
}
