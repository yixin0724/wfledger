package org.workflowsim.experiments.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiments.network.NetworkStudyPlan;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class BenchmarkExecutor {

    static final String INDEX = "benchmark-carbon.json";

    static final String PROTOCOL = "benchmark-standard-carbon-v1";

    static final double POWER_IDLE_WATTS = 250.0;

    static final double POWER_MAX_WATTS = 400.0;

    public static final double CARBON_INTENSITY_GRAMS_PER_KWH = 380.0;

    static final double CARBON_WINDOW_1_END_SECONDS = 3600.0;

    static final double CARBON_WINDOW_2_END_SECONDS = 7200.0;

    static final double CARBON_WINDOW_1_GREEN_WATTS = 300.0;

    static final double CARBON_WINDOW_2_GREEN_WATTS = 600.0;

    static final PlanningAlgorithm BUDGET_PLANNER = PlanningAlgorithm.SHARED_STORAGE_HEFT;

    static final String DVFS_INDEX = "benchmark-dvfs.json";

    static final String DVFS_PROTOCOL = "benchmark-standard-dvfs-v1";

    static final List<PlatformProfile.FrequencyTier> DVFS_TIERS = Collections.unmodifiableList(Arrays.asList(
            new PlatformProfile.FrequencyTier(2000.0, 250.0, 4000.0),
            new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0),
            new PlatformProfile.FrequencyTier(500.0, 100.0, 100.0 + 150.0 / 8.0),
            new PlatformProfile.FrequencyTier(250.0, 50.0, 50.0 + 150.0 / 64.0),
            new PlatformProfile.FrequencyTier(125.0, 25.0, 25.0 + 150.0 / 512.0)));

    static final List<SimulationConfig.FrequencyPolicy> DVFS_POLICIES = Collections.unmodifiableList(Arrays.asList(
            SimulationConfig.FrequencyPolicy.DEFAULT,
            SimulationConfig.FrequencyPolicy.MIN_POWER,
            SimulationConfig.FrequencyPolicy.EDP_HEURISTIC));

    static final PlanningAlgorithm DVFS_SHARED_PLANNER = PlanningAlgorithm.SHARED_STORAGE_HEFT;

    static final PlanningAlgorithm DVFS_LOCAL_PLANNER = PlanningAlgorithm.LOCAL_HEFT;

    static final SchedulingAlgorithm DVFS_ONLINE_LABEL = SchedulingAlgorithm.FCFS;

    static final List<String> INPUT_PATHS = Collections.unmodifiableList(Arrays.asList(
            "dax/epigenomics/n100/Epigenomics_100.dax",
            "dax/epigenomics/n997/Epigenomics_997.dax",
            "dax/cybershake/n100/CyberShake_100.dax",
            "dax/cybershake/n1000/CyberShake_1000.dax",
            "dax/inspiral/n100/Inspiral_100.dax"));

    static final List<PlanningAlgorithm> SHARED_PLANNERS = Collections.unmodifiableList(Arrays.asList(
            PlanningAlgorithm.SHARED_STORAGE_HEFT,
            PlanningAlgorithm.SHARED_STORAGE_CPOP,
            PlanningAlgorithm.SHARED_STORAGE_DLS,
            PlanningAlgorithm.SHARED_STORAGE_ETF,
            PlanningAlgorithm.SHARED_STORAGE_PEFT));

    static final List<PlanningAlgorithm> LOCAL_PLANNERS = Collections.unmodifiableList(
            Arrays.asList(PlanningAlgorithm.LOCAL_HEFT, PlanningAlgorithm.LOCAL_CPOP, PlanningAlgorithm.LOCAL_PEFT));

    static final List<SchedulingAlgorithm> ONLINE_LABELS = Collections.unmodifiableList(Arrays.asList(
            SchedulingAlgorithm.FCFS,
            SchedulingAlgorithm.READY_BATCH_MINMIN,
            SchedulingAlgorithm.READY_BATCH_MAXMIN,
            SchedulingAlgorithm.READY_BATCH_MCT,
            SchedulingAlgorithm.READY_BATCH_ROUNDROBIN));

    static final List<String> LOCAL_NETWORKS =
            Collections.unmodifiableList(Arrays.asList("endpoint", "fat-tree-constrained"));

    static final List<Integer> VM_TIERS = Collections.unmodifiableList(Arrays.asList(8, 32));

    private BenchmarkExecutor() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "dvfs".equals(args[0])) {
            Path result = executeDvfs(Paths.get(args[1]), Paths.get(args[2]));
            System.out.println("BENCHMARK PASSED " + result);
            return;
        }
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: BenchmarkExecutor [dvfs] <datasets-root>" + " <new-output-directory>");
        }
        Path result = execute(Paths.get(args[0]), Paths.get(args[1]));
        System.out.println("BENCHMARK PASSED " + result);
    }

    static Path execute(Path datasetsRoot, Path outputDirectory) throws Exception {
        Path datasets = datasetsRoot.toAbsolutePath().normalize();
        Path root = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IOException("Benchmark output already exists: " + root);
        }
        boolean prior = Log.isDisabled();
        Log.disable();
        try {
            Files.createDirectories(root);
            writePlan(root);
            List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
            executeShared(datasets, root, runs);
            executeLocal(datasets, root, runs);
            executeOnline(datasets, root, runs);
            executeBudget(datasets, root, runs);
            writeIndex(root, runs);
            return root.resolve(INDEX);
        } finally {
            Log.setDisabled(prior);
        }
    }

    private static void executeShared(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                for (PlanningAlgorithm planner : SHARED_PLANNERS) {
                    cell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            planner,
                            null,
                            ReplicaCatalog.FileSystem.SHARED,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint",
                            false);
                }
            }
            System.out.println("[benchmark] SHARED-" + inputId(input) + ": " + runs.size());
        }
    }

    private static void executeLocal(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                for (PlanningAlgorithm planner : LOCAL_PLANNERS) {
                    for (String network : LOCAL_NETWORKS) {
                        cell(
                                runs,
                                root,
                                path,
                                inputId(input),
                                vm,
                                planner,
                                null,
                                ReplicaCatalog.FileSystem.LOCAL,
                                NetworkStudyPlan.movement(network),
                                network,
                                false);
                    }
                }
            }
            System.out.println("[benchmark] LOCAL-" + inputId(input) + ": " + runs.size());
        }
    }

    private static void executeOnline(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                for (SchedulingAlgorithm label : ONLINE_LABELS) {
                    cell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            null,
                            label,
                            ReplicaCatalog.FileSystem.LOCAL,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint",
                            false);
                }
            }
            System.out.println("[benchmark] ONLINE-" + inputId(input) + ": " + runs.size());
        }
    }

    private static void executeBudget(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                cell(
                        runs,
                        root,
                        path,
                        inputId(input),
                        vm,
                        BUDGET_PLANNER,
                        null,
                        ReplicaCatalog.FileSystem.SHARED,
                        DataMovementModel.legacyWorkflowsimV1(),
                        "endpoint",
                        true);
            }
            System.out.println("[benchmark] BUDGET-" + inputId(input) + ": " + runs.size());
        }
    }

    static Path executeDvfs(Path datasetsRoot, Path outputDirectory) throws Exception {
        Path datasets = datasetsRoot.toAbsolutePath().normalize();
        Path root = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IOException("Benchmark output already exists: " + root);
        }
        boolean prior = Log.isDisabled();
        Log.disable();
        try {
            Files.createDirectories(root);
            writeDvfsPlan(root);
            List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
            for (String input : INPUT_PATHS) {
                Path path = resolveInput(datasets, input);
                for (int vm : VM_TIERS) {

                    dvfsCell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            DVFS_SHARED_PLANNER,
                            null,
                            ReplicaCatalog.FileSystem.SHARED,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint");

                    dvfsCell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            DVFS_LOCAL_PLANNER,
                            null,
                            ReplicaCatalog.FileSystem.LOCAL,
                            NetworkStudyPlan.movement("endpoint"),
                            "endpoint");

                    dvfsCell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            null,
                            DVFS_ONLINE_LABEL,
                            ReplicaCatalog.FileSystem.LOCAL,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint");
                }
                System.out.println("[benchmark-dvfs] " + inputId(input) + ": " + runs.size());
            }
            writeDvfsIndex(root, runs);
            return root.resolve(DVFS_INDEX);
        } finally {
            Log.setDisabled(prior);
        }
    }

    public static List<PlatformProfile.FrequencyTier> benchmarkDvfsTiers() {
        return DVFS_TIERS;
    }

    static PlatformProfile dvfsSetup(PlatformProfile unpowered) {
        for (PlatformProfile.VmSpec vm : unpowered.getVms()) {
            if (vm.hasPowerModel()) {
                throw new IllegalStateException("dvfsSetup expects the unpowered registered-study platform, VM "
                        + vm.getId() + " already declares a power model");
            }
            if (vm.hasFrequencyTiers()) {
                throw new IllegalStateException("dvfsSetup expects the tierless registered-study platform, VM "
                        + vm.getId() + " already declares a frequency tier table");
            }
            if (vm.getCarbonIntensityGramsPerKWh() != null) {
                throw new IllegalStateException("dvfsSetup expects the carbon-free registered-study" + " platform, VM "
                        + vm.getId() + " already declares a carbon intensity");
            }
        }
        PlatformProfile.Builder builder = PlatformProfile.builder(unpowered.getName())
                .storage(unpowered.getStorage())
                .costs(unpowered.getCosts());
        for (PlatformProfile.HostSpec host : unpowered.getHosts()) {
            builder.addHost(new PlatformProfile.HostSpec(
                    host.getId(),
                    host.getPes(),
                    host.getMipsPerPe(),
                    host.getRamMb(),
                    host.getBandwidth(),
                    host.getStorageMb()));
        }
        for (PlatformProfile.VmSpec vm : unpowered.getVms()) {
            builder.addVm(new PlatformProfile.VmSpec(
                    vm.getId(),
                    vm.getMips(),
                    vm.getPes(),
                    vm.getRamMb(),
                    vm.getBandwidth(),
                    vm.getImageSizeMb(),
                    vm.getVmm(),
                    vm.getSchedulerMode(),
                    vm.getCosts(),
                    0.0,
                    0.0,
                    Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH),
                    DVFS_TIERS));
        }
        for (Map.Entry<Integer, Integer> pin : unpowered.getPinnedVmHostIds().entrySet()) {
            builder.pinVmToHost(pin.getKey().intValue(), pin.getValue().intValue());
        }
        if (unpowered.getNetworkTopology() != null) {
            builder.networkTopology(unpowered.getNetworkTopology());
        }
        if (unpowered.getSourceStorage() != null) {
            builder.sourceStorage(unpowered.getSourceStorage());
        }
        return builder.build();
    }

    static String dvfsRunId(String inputId2, int vm, String algorithm, String policy) {
        return inputId2 + "-v" + vm + "-DVFS-" + algorithm + "-" + policy + "-s11";
    }

    static String dvfsAlgorithmLabel(PlanningAlgorithm planner, SchedulingAlgorithm scheduler) {
        return planner != null ? planner.name() : scheduler.name();
    }

    private static void dvfsCell(
            List<Map<String, Object>> runs,
            Path root,
            Path inputPath,
            String inputId2,
            int vmCount,
            PlanningAlgorithm planner,
            SchedulingAlgorithm scheduler,
            ReplicaCatalog.FileSystem fileSystem,
            DataMovementModel model,
            String platformNetwork)
            throws IOException {
        PlatformProfile platform = dvfsSetup(NetworkStudyPlan.platform(vmCount, platformNetwork));
        String algorithm = dvfsAlgorithmLabel(planner, scheduler);
        String modelKind = model.getKind().name();
        for (SimulationConfig.FrequencyPolicy policy : DVFS_POLICIES) {
            String runId = dvfsRunId(inputId2, vmCount, algorithm, policy.name());
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            record.put("runId", runId);
            record.put("inputId", inputId2);
            record.put("vmCount", Integer.valueOf(vmCount));
            record.put(
                    "layer",
                    planner != null
                            ? (fileSystem == ReplicaCatalog.FileSystem.SHARED ? "SHARED_DAG" : "LOCAL_DAG")
                            : "ONLINE");
            record.put("planner", planner != null ? planner.name() : null);
            record.put("scheduler", scheduler != null ? scheduler.name() : null);
            record.put("model", modelKind);
            record.put("frequencyPolicy", policy.name());
            record.put("carbonIntensityGramsPerKWh", Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH));
            try {
                SimulationConfig config = SimulationConfig.builder(inputPath.toString(), vmCount)
                        .planningAlgorithm(planner != null ? planner : PlanningAlgorithm.INVALID)
                        .schedulingAlgorithm(
                                scheduler != null
                                        ? scheduler
                                        : (planner != null ? SchedulingAlgorithm.STATIC : SchedulingAlgorithm.INVALID))
                        .fileSystem(fileSystem)
                        .dataMovementModel(model)
                        .randomSeed(11L)
                        .frequencyPolicy(policy)
                        .build();
                SimulationReport report = new SimulationRunner().run(config, platform);
                if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
                    throw new IllegalStateException("Incomplete workflow");
                }

                if (report.getCarbonAccounting() == null) {
                    throw new IllegalStateException("DVFS benchmark cell declared no carbon accounting");
                }
                if (report.getEnergyAccounting().getTotalEnergyJoules() <= 0.0) {
                    throw new IllegalStateException(
                            "DVFS benchmark cell conserved no energy (tiered fleet is powered)");
                }
                ExperimentEvidenceContext context = ExperimentEvidenceContext.builder(DVFS_PROTOCOL)
                        .artifact("org.workflowsim", "benchmark", "1.0")
                        .driver(BenchmarkExecutor.class, "org/workflowsim/experiments/benchmark")
                        .protocol(DVFS_PROTOCOL, root.resolve("benchmark-dvfs-plan.json"))
                        .datasetRoot(inputPath.getParent())
                        .build();
                ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(
                        report, root.resolve("runs").resolve(runId), "result", context);
                ExperimentArtifactValidator.validate(artifacts.getManifest());
                record.put("status", "COMPLETED_SUCCESSFULLY");
                record.put(
                        "manifest",
                        root.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
                record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                record.put(
                        "totalEnergyJoules",
                        Double.valueOf(report.getEnergyAccounting().getTotalEnergyJoules()));
                record.put(
                        "energyMakespanProductJouleSeconds",
                        Double.valueOf(report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds()));
                record.put(
                        "totalCarbonGrams",
                        Double.valueOf(report.getCarbonAccounting().getTotalCarbonGrams()));
                record.put(
                        "meanVmUtilization", Double.valueOf(report.getMetrics().getMeanVmModeledIntervalUtilization()));

                record.put("fleetPerTierBusySeconds", report.getMetrics().getFleetPerTierBusySeconds());
            } catch (Exception failure) {
                record.put("status", "FAILED");
                record.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            runs.add(record);
            writeDvfsIndex(root, runs);
        }
    }

    private static void writeDvfsPlan(Path root) throws IOException {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("schema", "workflowsim-benchmark-plan-v1");
        plan.put("protocol", DVFS_PROTOCOL);
        plan.put("javaClass", BenchmarkExecutor.class.getName());
        plan.put(
                "fleet",
                "tri-dimensional: 5-tier DVFS P-state table + per-VM power-free flat fields"
                        + " (tier table replaces them) + uniform carbon intensity 380 g/kWh riding"
                        + " the per-tier conserved energy");
        List<Map<String, Object>> tiers = new ArrayList<Map<String, Object>>();
        for (PlatformProfile.FrequencyTier tier : DVFS_TIERS) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("mips", Double.valueOf(tier.getMips()));
            row.put("idlePowerWatts", Double.valueOf(tier.getIdlePowerWatts()));
            row.put("maxPowerWatts", Double.valueOf(tier.getMaxPowerWatts()));
            tiers.add(row);
        }
        plan.put("frequencyTiers", tiers);
        plan.put(
                "frequencyTierLaws",
                Arrays.asList(
                        "dynamic = 150 W x (mips/1000)^3 at/below the nominal 1000 tier (the Beloglazov"
                                + " dynamic of benchmark-standard-power-v1); the 2000 turbo tier declares"
                                + " 3750 W dynamic (overvolted above the efficient point)",
                        "idle = 250 W at/above nominal; strictly below nominal linear leakage 0.2 W/MIPS"
                                + " (500->100, 250->50, 125->25)",
                        "declared values are frozen constants (a declared model), not generator output"));
        List<String> policies = new ArrayList<String>();
        for (SimulationConfig.FrequencyPolicy policy : DVFS_POLICIES) {
            policies.add(policy.name());
        }
        plan.put("frequencyPolicies", policies);
        plan.put(
                "algorithms",
                Arrays.asList(
                        DVFS_SHARED_PLANNER.name() + " (SHARED_DAG layer representative)",
                        DVFS_LOCAL_PLANNER.name() + " (LOCAL_DAG layer representative, endpoint contention)",
                        DVFS_ONLINE_LABEL.name() + " (ONLINE layer representative)"));
        plan.put(
                "cellSizing",
                "90 cells = 5 inputs x 2 VM tiers x 3 policies x 3 layer-representative"
                        + " algorithms; the full grid would be 480 cells (16 algorithm combinations x 3"
                        + " policies) and would miss the frozen wall-clock budget of minutes — the"
                        + " 90-cell representative subset covers every input family, both VM tiers, and all"
                        + " three decision layers, and keeps the policy x policy comparison same-layer-only");
        plan.put("carbonIntensityGramsPerKWh", Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH));
        ExperimentArtifactWriter.writeJson(root.resolve("benchmark-dvfs-plan.json"), plan);
    }

    private static void writeDvfsIndex(Path root, List<Map<String, Object>> runs) throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", "workflowsim-benchmark-v1");
        index.put("protocol", DVFS_PROTOCOL);
        index.put("layers", "SHARED_DAG;LOCAL_DAG;ONLINE");
        index.put("frequencyPolicies", "DEFAULT;MIN_POWER;EDP_HEURISTIC");
        index.put("carbonModel", "CARBON_INTENSITY_V1");
        index.put("runCount", Integer.valueOf(runs.size()));
        index.put("runs", runs);
        ExperimentArtifactWriter.writeJson(root.resolve(DVFS_INDEX), index);
    }

    static Path resolveInput(Path datasets, String relative) {
        Path p = datasets.resolve(relative).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("Missing benchmark input: " + p);
        }
        return p;
    }

    static String inputId(String relative) {
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        return name.replace(".dax", "");
    }

    static String runId(String inputId2, int vm, String policy, String model) {
        return inputId2 + "-v" + vm + "-" + model + "-" + policy + "-s11";
    }

    static String budgetRunId(String inputId2, int vm, String policy) {
        return inputId2 + "-v" + vm + "-CARBON_BUDGET-" + policy + "-s11";
    }

    static PlatformProfile withPower(PlatformProfile unpowered) {
        PlatformProfile.Builder builder = PlatformProfile.builder(unpowered.getName())
                .storage(unpowered.getStorage())
                .costs(unpowered.getCosts());
        for (PlatformProfile.HostSpec host : unpowered.getHosts()) {
            builder.addHost(new PlatformProfile.HostSpec(
                    host.getId(),
                    host.getPes(),
                    host.getMipsPerPe(),
                    host.getRamMb(),
                    host.getBandwidth(),
                    host.getStorageMb()));
        }
        for (PlatformProfile.VmSpec vm : unpowered.getVms()) {
            if (vm.hasPowerModel()) {
                throw new IllegalStateException("withPower expects the unpowered registered-study platform, VM "
                        + vm.getId() + " already declares a power model");
            }
            builder.addVm(new PlatformProfile.VmSpec(
                    vm.getId(),
                    vm.getMips(),
                    vm.getPes(),
                    vm.getRamMb(),
                    vm.getBandwidth(),
                    vm.getImageSizeMb(),
                    vm.getVmm(),
                    vm.getSchedulerMode(),
                    vm.getCosts(),
                    POWER_IDLE_WATTS,
                    POWER_MAX_WATTS));
        }
        for (Map.Entry<Integer, Integer> pin : unpowered.getPinnedVmHostIds().entrySet()) {
            builder.pinVmToHost(pin.getKey().intValue(), pin.getValue().intValue());
        }
        if (unpowered.getNetworkTopology() != null) {
            builder.networkTopology(unpowered.getNetworkTopology());
        }
        if (unpowered.getSourceStorage() != null) {
            builder.sourceStorage(unpowered.getSourceStorage());
        }
        return builder.build();
    }

    static PlatformProfile withCarbon(PlatformProfile unpowered) {
        return rebuildCarbon(withPower(unpowered), Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH), false);
    }

    static PlatformProfile withCarbonBudget(PlatformProfile unpowered) {
        return rebuildCarbon(withPower(unpowered), null, true);
    }

    private static PlatformProfile rebuildCarbon(
            PlatformProfile powered, Double carbonIntensity, boolean carbonWindows) {
        if (carbonIntensity != null && carbonWindows) {
            throw new IllegalStateException("rebuildCarbon carries one carbon model per profile (BAT-C1)");
        }
        for (PlatformProfile.VmSpec vm : powered.getVms()) {
            if (vm.getCarbonIntensityGramsPerKWh() != null) {
                throw new IllegalStateException("rebuildCarbon expects the power-only benchmark platform, VM "
                        + vm.getId() + " already declares a carbon intensity");
            }
        }
        if (!powered.getCarbonWindows().isEmpty()) {
            throw new IllegalStateException("rebuildCarbon expects the power-only benchmark platform, carbon"
                    + " windows are already declared");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder(powered.getName())
                .storage(powered.getStorage())
                .costs(powered.getCosts());
        for (PlatformProfile.HostSpec host : powered.getHosts()) {
            builder.addHost(new PlatformProfile.HostSpec(
                    host.getId(),
                    host.getPes(),
                    host.getMipsPerPe(),
                    host.getRamMb(),
                    host.getBandwidth(),
                    host.getStorageMb()));
        }
        for (PlatformProfile.VmSpec vm : powered.getVms()) {
            if (!vm.hasPowerModel()) {
                throw new IllegalStateException("rebuildCarbon expects the chained withPower output, VM " + vm.getId()
                        + " declares no power model");
            }
            builder.addVm(new PlatformProfile.VmSpec(
                    vm.getId(),
                    vm.getMips(),
                    vm.getPes(),
                    vm.getRamMb(),
                    vm.getBandwidth(),
                    vm.getImageSizeMb(),
                    vm.getVmm(),
                    vm.getSchedulerMode(),
                    vm.getCosts(),
                    vm.getIdlePowerWatts(),
                    vm.getMaxPowerWatts(),
                    carbonIntensity));
        }
        for (Map.Entry<Integer, Integer> pin : powered.getPinnedVmHostIds().entrySet()) {
            builder.pinVmToHost(pin.getKey().intValue(), pin.getValue().intValue());
        }
        if (powered.getNetworkTopology() != null) {
            builder.networkTopology(powered.getNetworkTopology());
        }
        if (powered.getSourceStorage() != null) {
            builder.sourceStorage(powered.getSourceStorage());
        }
        if (carbonWindows) {
            builder.carbonWindow(0.0, CARBON_WINDOW_1_END_SECONDS, CARBON_WINDOW_1_GREEN_WATTS);
            builder.carbonWindow(CARBON_WINDOW_1_END_SECONDS, CARBON_WINDOW_2_END_SECONDS, CARBON_WINDOW_2_GREEN_WATTS);
        }
        return builder.build();
    }

    private static void cell(
            List<Map<String, Object>> runs,
            Path root,
            Path inputPath,
            String inputId2,
            int vmCount,
            PlanningAlgorithm planner,
            SchedulingAlgorithm scheduler,
            ReplicaCatalog.FileSystem fileSystem,
            DataMovementModel model,
            String platformNetwork,
            boolean carbonBudget)
            throws IOException {
        String policy = planner != null ? planner.name() : scheduler.name();
        String modelKind = model.getKind().name();
        String runId =
                carbonBudget ? budgetRunId(inputId2, vmCount, policy) : runId(inputId2, vmCount, policy, modelKind);
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("runId", runId);
        record.put("inputId", inputId2);
        record.put("vmCount", Integer.valueOf(vmCount));
        record.put("planner", planner != null ? planner.name() : null);
        record.put("scheduler", scheduler != null ? scheduler.name() : null);
        record.put("model", modelKind);
        record.put("carbonModel", carbonBudget ? "CARBON_BUDGET_V1" : "CARBON_INTENSITY_V1");
        try {
            SimulationConfig config = SimulationConfig.builder(inputPath.toString(), vmCount)
                    .planningAlgorithm(planner != null ? planner : PlanningAlgorithm.INVALID)
                    .schedulingAlgorithm(
                            scheduler != null
                                    ? scheduler
                                    : (planner != null ? SchedulingAlgorithm.STATIC : SchedulingAlgorithm.INVALID))
                    .fileSystem(fileSystem)
                    .dataMovementModel(model)
                    .randomSeed(11L)
                    .build();
            PlatformProfile platform =
                    NetworkStudyPlan.platform(vmCount, platformNetwork == null ? "endpoint" : platformNetwork);
            platform = carbonBudget ? withCarbonBudget(platform) : withCarbon(platform);
            SimulationReport report = new SimulationRunner().run(config, platform);
            if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
                throw new IllegalStateException("Incomplete workflow");
            }
            if (report.getCarbonAccounting() == null) {

                throw new IllegalStateException("Carbon benchmark cell declared no carbon accounting (BAT-C1)");
            }
            ExperimentEvidenceContext context = ExperimentEvidenceContext.builder(PROTOCOL)
                    .artifact("org.workflowsim", "benchmark", "1.0")
                    .driver(BenchmarkExecutor.class, "org/workflowsim/experiments/benchmark")
                    .protocol(PROTOCOL, root.resolve("benchmark-plan.json"))
                    .datasetRoot(inputPath.getParent())
                    .build();
            ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                    ExperimentArtifactWriter.write(report, root.resolve("runs").resolve(runId), "result", context);
            ExperimentArtifactValidator.validate(artifacts.getManifest());
            record.put("status", "COMPLETED_SUCCESSFULLY");
            record.put(
                    "manifest",
                    root.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
            record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
            record.put("logicalCompletionSeconds", Double.valueOf(report.getLogicalTaskCompletionSeconds()));
            record.put(
                    "meanWaitingSeconds", Double.valueOf(report.getMetrics().getMeanComputeTotalWaitingTimeSeconds()));
            record.put("p95WaitingSeconds", Double.valueOf(report.getMetrics().getP95ComputeTotalWaitingTimeSeconds()));
            record.put("meanVmUtilization", Double.valueOf(report.getMetrics().getMeanVmModeledIntervalUtilization()));
            if (carbonBudget) {
                record.put(
                        "totalCarbonExcessWattSeconds",
                        Double.valueOf(report.getCarbonAccounting().getTotalCarbonExcessWattSeconds()));
                record.put(
                        "windowExcessWattSeconds", report.getCarbonAccounting().getWindowExcessWattSeconds());
            } else {
                record.put(
                        "totalCarbonGrams",
                        Double.valueOf(report.getCarbonAccounting().getTotalCarbonGrams()));
                record.put(
                        "meanVmCarbonGrams",
                        Double.valueOf(report.getCarbonAccounting().getMeanVmCarbonGrams()));
                record.put(
                        "carbonMakespanProductGramSeconds",
                        Double.valueOf(report.getCarbonAccounting().getCarbonMakespanProductGramSeconds()));
            }
        } catch (Exception failure) {
            record.put("status", "FAILED");
            record.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        runs.add(record);
        writeIndex(root, runs);
    }

    private static void writePlan(Path root) throws IOException {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("schema", "workflowsim-benchmark-plan-v1");
        plan.put("protocol", PROTOCOL);
        plan.put("javaClass", BenchmarkExecutor.class.getName());

        plan.put("carbonIntensityGramsPerKWh", Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH));
        List<List<Double>> windows = new ArrayList<List<Double>>();
        windows.add(Arrays.asList(
                Double.valueOf(0.0),
                Double.valueOf(CARBON_WINDOW_1_END_SECONDS),
                Double.valueOf(CARBON_WINDOW_1_GREEN_WATTS)));
        windows.add(Arrays.asList(
                Double.valueOf(CARBON_WINDOW_1_END_SECONDS),
                Double.valueOf(CARBON_WINDOW_2_END_SECONDS),
                Double.valueOf(CARBON_WINDOW_2_GREEN_WATTS)));
        plan.put("budgetCarbonWindows", windows);
        plan.put("budgetPlanner", BUDGET_PLANNER.name());
        ExperimentArtifactWriter.writeJson(root.resolve("benchmark-plan.json"), plan);
    }

    private static void writeIndex(Path root, List<Map<String, Object>> runs) throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", "workflowsim-benchmark-v1");
        index.put("protocol", PROTOCOL);
        index.put("layers", "SHARED_DAG;LOCAL_DAG;ONLINE");
        index.put("carbonModels", "CARBON_INTENSITY_V1;CARBON_BUDGET_V1");
        index.put("runCount", Integer.valueOf(runs.size()));
        index.put("runs", runs);
        ExperimentArtifactWriter.writeJson(root.resolve(INDEX), index);
    }
}
