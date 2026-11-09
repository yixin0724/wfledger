package org.workflowsim.experiments.energy;

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

/** Compares energy-aware planners and tier-selection policies on real scientific workflows. */
public final class EnergyBaselineComparison {

    private static final String[] DATASETS = {
        "datasets/dax/montage/n50/Montage_50.dax",
        "datasets/dax/montage/n100/Montage_100.dax",
        "datasets/dax/cybershake/n50/CyberShake_50.dax",
        "datasets/dax/sipht/n60/Sipht_60.dax",
        "datasets/dax/inspiral/n50/Inspiral_50.dax"
    };

    private EnergyBaselineComparison() {}

    public static List<Result> run() throws SimulationExecutionException {
        Path root = repositoryRoot();
        PlatformProfile flat = platform(false);
        PlatformProfile tiered = platform(true);
        List<Result> results = new ArrayList<Result>();
        for (String dataset : DATASETS) {
            Path workflow = root.resolve(dataset);
            if (!Files.isRegularFile(workflow)) {
                throw new IllegalArgumentException("Missing energy workflow " + workflow);
            }
            for (String algorithm : Arrays.asList(
                    "SHARED_STORAGE_HEFT",
                    "GREEN_HEFT",
                    "MO_HEFT",
                    "HEFT_DEFAULT",
                    "HEFT_MIN_POWER",
                    "HEFT_EDP_HEURISTIC",
                    "DVFS_SLACK")) {
                boolean usesTiers = !"SHARED_STORAGE_HEFT".equals(algorithm)
                        && !"GREEN_HEFT".equals(algorithm)
                        && !"MO_HEFT".equals(algorithm);
                SimulationConfig config = config(workflow, algorithm, usesTiers);
                SimulationReport report = new SimulationRunner().run(config, usesTiers ? tiered : flat);
                results.add(new Result(dataset, usesTiers ? "TIERED" : "FLAT", algorithm, report));
            }
        }
        return Collections.unmodifiableList(results);
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("output", "energy-baseline", "results.json") : Paths.get(args[0]);
        List<Result> results = run();
        writeResults(output, results);
        System.out.println("Wrote " + results.size() + " energy results to " + output);
    }

    public static void writeResults(Path output, List<Result> results) throws java.io.IOException {
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
    }

    private static SimulationConfig config(Path workflow, String algorithm, boolean tiers) {
        SimulationConfig.Builder builder = SimulationConfig.builder(workflow.toString(), 4)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .costModel(Parameters.CostModel.VM)
                .runtimeReferenceMips(1000.0)
                .eventRecorderCap(200000)
                .randomSeed(7L);
        if ("DVFS_SLACK".equals(algorithm)) {
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.DVFS_SLACK)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                    .deadline(deadline(workflow));
        } else if ("HEFT_MIN_POWER".equals(algorithm)) {
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER);
        } else if ("HEFT_EDP_HEURISTIC".equals(algorithm)) {
            builder.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .frequencyPolicy(SimulationConfig.FrequencyPolicy.EDP_HEURISTIC);
        } else {
            Parameters.PlanningAlgorithm planner = "HEFT_DEFAULT".equals(algorithm)
                    ? Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT
                    : Parameters.PlanningAlgorithm.valueOf(algorithm);
            builder.planningAlgorithm(planner)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT);
        }
        return builder.build();
    }

    private static long deadline(Path workflow) {
        String name = workflow.getFileName().toString();
        if (name.contains("CyberShake") || name.contains("Sipht")) {
            return 3500L;
        }
        if (name.contains("Inspiral")) {
            return 3000L;
        }
        if (name.contains("Montage_50")) {
            return 150L;
        }
        if (name.contains("Montage_100")) {
            return 300L;
        }
        return 1000L;
    }

    private static PlatformProfile platform(boolean tiers) {
        PlatformProfile.Builder builder = PlatformProfile.builder(tiers ? "energy-tiered" : "energy-flat")
                .addHost(new PlatformProfile.HostSpec(0, 1, 500.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(2, 1, 1500.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(3, 1, 2000.0, 4096, 10000L, 100000L));
        addVm(builder, 3, 500.0, 5.0, 50.0, 1.0, tiers);
        addVm(builder, 8, 1000.0, 10.0, 100.0, 2.0, tiers);
        addVm(builder, 11, 1500.0, 15.0, 150.0, 4.0, tiers);
        addVm(builder, 17, 2000.0, 20.0, 200.0, 8.0, tiers);
        return builder.pinVmToHost(3, 0)
                .pinVmToHost(8, 1)
                .pinVmToHost(11, 2)
                .pinVmToHost(17, 3)
                .build();
    }

    private static void addVm(
            PlatformProfile.Builder builder, int id, double mips, double idle, double max, double cost, boolean tiers) {
        List<PlatformProfile.FrequencyTier> frequencyTiers = tiers
                ? Arrays.asList(
                        new PlatformProfile.FrequencyTier(mips, idle, max),
                        new PlatformProfile.FrequencyTier(mips / 2.0, idle / 2.0, max / 5.0))
                : Collections.<PlatformProfile.FrequencyTier>emptyList();
        builder.addVm(new PlatformProfile.VmSpec(
                id,
                mips,
                1,
                4096,
                100L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                new PlatformProfile.CostSpec(cost, 0.0, 0.0, 0.0),
                tiers ? 0.0 : idle,
                tiers ? 0.0 : max,
                null,
                frequencyTiers));
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

    public static final class Result {
        private final String dataset;
        private final String group;
        private final String algorithm;
        private final double makespanSeconds;
        private final double energyJoules;
        private final double energyDelayProduct;
        private final double meanWaitingSeconds;
        private final double meanTrueSlowdown;
        private final double throughputJobsPerSecond;
        private final double meanVmUtilization;
        private final double modeledProcessingCost;
        private final int logicalTasks;
        private final int completedTasks;

        private Result(String dataset, String group, String algorithm, SimulationReport report) {
            SimulationMetrics metrics = report.getMetrics();
            this.dataset = dataset;
            this.group = group;
            this.algorithm = algorithm;
            this.makespanSeconds = report.getMakespan();
            this.energyJoules = report.getEnergyAccounting().getTotalEnergyJoules();
            this.energyDelayProduct = report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds();
            this.meanWaitingSeconds = metrics.getMeanComputeTotalWaitingTimeSeconds();
            this.meanTrueSlowdown = metrics.getMeanComputeTrueSlowdown();
            this.throughputJobsPerSecond = metrics.getComputeJobOutcomeThroughputPerSecond();
            this.meanVmUtilization = metrics.getMeanVmModeledIntervalUtilization();
            this.modeledProcessingCost = metrics.getTotalModeledProcessingCost();
            this.logicalTasks = metrics.getLogicalTaskCount();
            this.completedTasks = metrics.getSuccessfullyCompletedLogicalTaskCount();
        }

        public JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("dataset", dataset);
            value.addProperty("group", group);
            value.addProperty("algorithm", algorithm);
            value.addProperty("makespanSeconds", makespanSeconds);
            value.addProperty("energyJoules", energyJoules);
            value.addProperty("energyDelayProduct", energyDelayProduct);
            value.addProperty("meanWaitingSeconds", meanWaitingSeconds);
            value.addProperty("meanTrueSlowdown", meanTrueSlowdown);
            value.addProperty("throughputJobsPerSecond", throughputJobsPerSecond);
            value.addProperty("meanVmUtilization", meanVmUtilization);
            value.addProperty("modeledProcessingCost", modeledProcessingCost);
            value.addProperty("logicalTasks", logicalTasks);
            value.addProperty("completedTasks", completedTasks);
            return value;
        }

        public String getDataset() {
            return dataset;
        }

        public String getGroup() {
            return group;
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

        public double getEnergyDelayProduct() {
            return energyDelayProduct;
        }

        public double getMeanWaitingSeconds() {
            return meanWaitingSeconds;
        }

        public double getMeanTrueSlowdown() {
            return meanTrueSlowdown;
        }

        public double getThroughputJobsPerSecond() {
            return throughputJobsPerSecond;
        }

        public double getMeanVmUtilization() {
            return meanVmUtilization;
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
