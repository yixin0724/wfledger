package org.workflowsim.experiments.makespan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiment.SimulationMetrics;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Runs traditional makespan-oriented algorithms on real scientific DAX workflows. */
public final class MakespanBaselineComparison {

    private static final String[] DATASETS = {
        "datasets/dax/montage/n50/Montage_50.dax",
        "datasets/dax/montage/n100/Montage_100.dax",
        "datasets/dax/cybershake/n50/CyberShake_50.dax",
        "datasets/dax/cybershake/n100/CyberShake_100.dax",
        "datasets/dax/sipht/n60/Sipht_60.dax",
        "datasets/dax/inspiral/n50/Inspiral_50.dax",
        "datasets/dax/epigenomics/n46/Epigenomics_46.dax"
    };

    private MakespanBaselineComparison() {}

    public static List<Result> run() throws SimulationExecutionException {
        return run(repositoryRoot());
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("datasets"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Cannot locate the WorkflowSim datasets directory");
    }

    public static List<Result> run(Path repositoryRoot) throws SimulationExecutionException {
        PlatformProfile platform = platform();
        List<Result> results = new ArrayList<Result>();
        for (String dataset : DATASETS) {
            Path workflow = repositoryRoot.resolve(dataset);
            if (!Files.isRegularFile(workflow)) {
                throw new IllegalArgumentException("Missing scientific workflow " + workflow);
            }
            for (Parameters.SchedulingAlgorithm algorithm : Arrays.asList(
                    Parameters.SchedulingAlgorithm.STATIC,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MCT,
                    Parameters.SchedulingAlgorithm.FCFS,
                    Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN)) {
                Parameters.PlanningAlgorithm planner = algorithm == Parameters.SchedulingAlgorithm.STATIC
                        ? Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT
                        : Parameters.PlanningAlgorithm.INVALID;
                SimulationConfig config = SimulationConfig.builder(workflow.toString(), 4)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(algorithm)
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                        .costModel(Parameters.CostModel.VM)
                        .runtimeReferenceMips(1000.0)
                        .randomSeed(7L)
                        .build();
                SimulationReport report = new SimulationRunner().run(config, platform);
                results.add(Result.from(
                        dataset,
                        planner == Parameters.PlanningAlgorithm.INVALID ? algorithm.name() : planner.name(),
                        report));
            }
        }
        return Collections.unmodifiableList(results);
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("output", "makespan-baseline", "results.json") : Paths.get(args[0]);
        List<Result> results = run();
        JsonArray array = new JsonArray();
        for (Result result : results) {
            array.add(result.toJson());
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
                output,
                (new GsonBuilder().setPrettyPrinting().create().toJson(array) + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("Wrote " + results.size() + " baseline results to " + output);
    }

    private static PlatformProfile platform() {
        return PlatformProfile.builder("makespan-baseline")
                .addHost(new PlatformProfile.HostSpec(0, 1, 500.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(2, 1, 1500.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(3, 1, 2000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        3,
                        500.0,
                        1,
                        4096,
                        100L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(1.0, 0.0, 0.0, 0.0),
                        5.0,
                        50.0,
                        null,
                        Collections.emptyList()))
                .addVm(new PlatformProfile.VmSpec(
                        8,
                        1000.0,
                        1,
                        4096,
                        100L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(2.0, 0.0, 0.0, 0.0),
                        10.0,
                        100.0,
                        null,
                        Collections.emptyList()))
                .addVm(new PlatformProfile.VmSpec(
                        11,
                        1500.0,
                        1,
                        4096,
                        100L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(4.0, 0.0, 0.0, 0.0),
                        15.0,
                        150.0,
                        null,
                        Collections.emptyList()))
                .addVm(new PlatformProfile.VmSpec(
                        17,
                        2000.0,
                        1,
                        4096,
                        100L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(8.0, 0.0, 0.0, 0.0),
                        20.0,
                        200.0,
                        null,
                        Collections.emptyList()))
                .pinVmToHost(3, 0)
                .pinVmToHost(8, 1)
                .pinVmToHost(11, 2)
                .pinVmToHost(17, 3)
                .build();
    }

    public static final class Result {
        private final String dataset;
        private final String algorithm;
        private final double makespanSeconds;
        private final double meanWaitingSeconds;
        private final double meanTrueSlowdown;
        private final double meanReadyToDecisionSeconds;
        private final double meanDecisionToStartSeconds;
        private final double meanRunTimeSeconds;
        private final double meanResponseSeconds;
        private final double throughputJobsPerSecond;
        private final double busyIntervalSeconds;
        private final double meanVmUtilization;
        private final double energyJoules;
        private final double modeledProcessingCost;
        private final int logicalTasks;
        private final int completedTasks;

        private Result(String dataset, String algorithm, SimulationReport report) {
            SimulationMetrics metrics = report.getMetrics();
            this.dataset = dataset;
            this.algorithm = algorithm;
            this.makespanSeconds = report.getMakespan();
            this.meanWaitingSeconds = metrics.getMeanComputeTotalWaitingTimeSeconds();
            this.meanTrueSlowdown = metrics.getMeanComputeTrueSlowdown();
            this.meanReadyToDecisionSeconds = metrics.getMeanComputeReadyToDecisionDelaySeconds();
            this.meanDecisionToStartSeconds = metrics.getMeanComputeDecisionToStartDelaySeconds();
            this.meanRunTimeSeconds = metrics.getMeanComputeJobRunTimeSeconds();
            this.meanResponseSeconds = metrics.getMeanJobResponseTimeSeconds();
            this.throughputJobsPerSecond = metrics.getComputeJobOutcomeThroughputPerSecond();
            this.busyIntervalSeconds = metrics.getTotalVmModeledBusyIntervalSeconds();
            this.meanVmUtilization = metrics.getMeanVmModeledIntervalUtilization();
            this.energyJoules = report.getEnergyAccounting().getTotalEnergyJoules();
            this.modeledProcessingCost = metrics.getTotalModeledProcessingCost();
            this.logicalTasks = metrics.getLogicalTaskCount();
            this.completedTasks = metrics.getSuccessfullyCompletedLogicalTaskCount();
        }

        private static Result from(String dataset, String algorithm, SimulationReport report) {
            return new Result(dataset, algorithm, report);
        }

        private JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("dataset", dataset);
            value.addProperty("algorithm", algorithm);
            value.addProperty("makespanSeconds", makespanSeconds);
            value.addProperty("meanWaitingSeconds", meanWaitingSeconds);
            value.addProperty("meanTrueSlowdown", meanTrueSlowdown);
            value.addProperty("meanReadyToDecisionSeconds", meanReadyToDecisionSeconds);
            value.addProperty("meanDecisionToStartSeconds", meanDecisionToStartSeconds);
            value.addProperty("meanRunTimeSeconds", meanRunTimeSeconds);
            value.addProperty("meanResponseSeconds", meanResponseSeconds);
            value.addProperty("throughputJobsPerSecond", throughputJobsPerSecond);
            value.addProperty("busyIntervalSeconds", busyIntervalSeconds);
            value.addProperty("meanVmUtilization", meanVmUtilization);
            value.addProperty("energyJoules", energyJoules);
            value.addProperty("modeledProcessingCost", modeledProcessingCost);
            value.addProperty("logicalTasks", logicalTasks);
            value.addProperty("completedTasks", completedTasks);
            return value;
        }

        public String getDataset() {
            return dataset;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public double getMakespanSeconds() {
            return makespanSeconds;
        }

        public double getMeanWaitingSeconds() {
            return meanWaitingSeconds;
        }

        public double getMeanTrueSlowdown() {
            return meanTrueSlowdown;
        }

        public double getMeanReadyToDecisionSeconds() {
            return meanReadyToDecisionSeconds;
        }

        public double getMeanDecisionToStartSeconds() {
            return meanDecisionToStartSeconds;
        }

        public double getMeanRunTimeSeconds() {
            return meanRunTimeSeconds;
        }

        public double getMeanResponseSeconds() {
            return meanResponseSeconds;
        }

        public double getThroughputJobsPerSecond() {
            return throughputJobsPerSecond;
        }

        public double getBusyIntervalSeconds() {
            return busyIntervalSeconds;
        }

        public double getMeanVmUtilization() {
            return meanVmUtilization;
        }

        public double getEnergyJoules() {
            return energyJoules;
        }

        public double getModeledProcessingCost() {
            return modeledProcessingCost;
        }

        public int getLogicalTasks() {
            return logicalTasks;
        }

        public int getCompletedTasks() {
            return completedTasks;
        }
    }
}
