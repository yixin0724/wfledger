package org.workflowsim.experiments.comparison;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        return new RunResult(
                label,
                report.getMakespan(),
                report.getEnergyAccounting().getTotalEnergyJoules(),
                modeledCost,
                report.isWorkflowCompletedSuccessfully());
    }

    public static final class RunResult {
        private final String algorithm;
        private final double makespanSeconds;
        private final double energyJoules;
        private final double modeledProcessingCost;
        private final boolean completed;

        private RunResult(
                String algorithm,
                double makespanSeconds,
                double energyJoules,
                double modeledProcessingCost,
                boolean completed) {
            this.algorithm = algorithm;
            this.makespanSeconds = makespanSeconds;
            this.energyJoules = energyJoules;
            this.modeledProcessingCost = modeledProcessingCost;
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

        public boolean isCompleted() {
            return completed;
        }
    }
}
