package org.workflowsim.experiments.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WorkflowParser;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.experiment.AlgorithmCatalog;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;
import org.workflowsim.utils.TaskCostMatrix;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class WorkbenchConfig {
    public static final String SCHEMA = "workflowsim-workbench-v1";
    private final String name;
    private final JsonObject source;
    private final PlatformProfile platform;
    private final List<Candidate> candidates;
    private final List<Long> seeds;

    private WorkbenchConfig(
            String name, JsonObject source, PlatformProfile platform, List<Candidate> candidates, List<Long> seeds) {
        this.name = name;
        this.source = source;
        this.platform = platform;
        this.candidates = Collections.unmodifiableList(candidates);
        this.seeds = Collections.unmodifiableList(seeds);
    }

    public static WorkbenchConfig read(Path path) throws IOException {
        JsonObject root = readJsonDocument(path);
        validateConfigurationFields(root);
        String name = text(root, "name", "Workflow Experiment");
        if (name.length() > 128) {
            throw new IllegalArgumentException("Experiment name is too long");
        }
        ExecutionMode executionMode = executionMode(root);
        List<String> paths = readWorkflowPaths(path, root);
        List<Double> arrivals = readArrivals(root, paths);
        JsonObject platformObject = object(root, "platform");
        PlatformProfile platform = platform(platformObject);

        SimulationConfig.FrequencyPolicy frequencyPolicy = frequencyPolicy(platformObject, platform);
        SimulationModels models = readSimulationModels(root, platform);
        List<Long> seeds = readSeeds(root);
        List<Candidate> candidates =
                readCandidates(root, paths, arrivals, platform, models, seeds, frequencyPolicy, executionMode);
        return new WorkbenchConfig(name, root.deepCopy(), platform, candidates, seeds);
    }

    private static JsonObject readJsonDocument(Path path) throws IOException {
        JsonObject root;
        try (com.google.gson.stream.JsonReader reader =
                new com.google.gson.stream.JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            reader.setLenient(false);
            root = readJson(reader, 0).getAsJsonObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing JSON content");
            }
        } catch (RuntimeException | com.google.gson.stream.MalformedJsonException | java.io.EOFException error) {
            throw new IllegalArgumentException("Configuration must be a strict JSON object with unique fields", error);
        }
        return root;
    }

    private static void validateConfigurationFields(JsonObject root) {
        fields(
                root,
                "configuration",
                "schema",
                "name",
                "executionMode",
                "workflowPaths",
                "workflowArrivalSeconds",
                "platform",
                "simulation",
                "algorithms",
                "seeds");
        if (!SCHEMA.equals(text(root, "schema", null))) {
            throw new IllegalArgumentException("Unsupported configuration schema");
        }
    }

    private enum ExecutionMode {
        COMBINED_WORKFLOWS,
        PER_WORKFLOW
    }

    private static ExecutionMode executionMode(JsonObject root) {
        String value = text(root, "executionMode", "COMBINED_WORKFLOWS");
        try {
            return ExecutionMode.valueOf(value);
        } catch (IllegalArgumentException unknownMode) {
            throw new IllegalArgumentException(
                    "configuration.executionMode must be COMBINED_WORKFLOWS or PER_WORKFLOW, got: " + value,
                    unknownMode);
        }
    }

    private static List<String> readWorkflowPaths(Path path, JsonObject root) {
        JsonArray inputs = array(root, "workflowPaths");
        if (inputs.size() < 1 || inputs.size() > 32) {
            throw new IllegalArgumentException("workflowPaths requires 1..32 inputs");
        }
        List<String> paths = new ArrayList<String>();
        for (JsonElement input : inputs) {
            if (!input.isJsonPrimitive() || !input.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("workflowPaths must contain strings");
            }
            Path resolved = path.toAbsolutePath()
                    .getParent()
                    .resolve(input.getAsString())
                    .normalize();
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("Workflow input does not exist: " + resolved);
            }
            paths.add(resolved.toString());
        }
        return paths;
    }

    private static List<Double> readArrivals(JsonObject root, List<String> paths) {
        List<Double> arrivals = new ArrayList<Double>();
        if (root.has("workflowArrivalSeconds")) {
            for (JsonElement value : array(root, "workflowArrivalSeconds")) {
                arrivals.add(number(value, "workflowArrivalSeconds", false));
            }
        } else {
            for (String ignored : paths) {
                arrivals.add(0.0);
            }
        }
        if (arrivals.size() != paths.size()) {
            throw new IllegalArgumentException("workflowArrivalSeconds must match workflowPaths");
        }
        return arrivals;
    }

    private static SimulationModels readSimulationModels(JsonObject root, PlatformProfile platform) {
        JsonObject sim = root.has("simulation") ? object(root, "simulation") : new JsonObject();
        fields(
                sim,
                "simulation",
                "fileSystem",
                "dataMovementModel",
                "runtimeScale",
                "runtimeReferenceMips",
                "minEventIntervalSeconds",
                "eventRecorderCap",
                "eventRecordingEnabled",
                "deadlineSeconds",
                "deadlineSecondsByWorkflow",
                "costModel",
                "taskCostMatrix",
                "fixedEndpoint",
                "networkEvidence");
        ReplicaCatalog.FileSystem fs = ReplicaCatalog.FileSystem.valueOf(text(sim, "fileSystem", "SHARED"));
        DataMovementModel movement = movement(sim);
        NetworkEvidenceConfig networkEvidence = sim.has("networkEvidence")
                ? org.workflowsim.data.NetworkEvidenceConfigCodec.decodeConfig(sim.get("networkEvidence"))
                : NetworkEvidenceConfig.off();
        boolean eventRecordingEnabled = !sim.has("eventRecordingEnabled")
                || booleanValue(sim.get("eventRecordingEnabled"), "simulation.eventRecordingEnabled");
        if (!eventRecordingEnabled && networkEvidence.isEnabled()) {
            throw new IllegalArgumentException("simulation.eventRecordingEnabled=false is incompatible with enabled"
                    + " simulation.networkEvidence; explicitly disable lifecycle evidence or enable event recording");
        }
        if (!movement.usesCoherentDataflowRuntime()
                && movement.isFatTreeContentionV1() != (platform.getNetworkTopology() != null)) {
            throw new IllegalArgumentException("Fat-tree data movement and networkTopology must be selected together");
        }
        if (movement.usesCoherentDataflowRuntime() && platform.getNetworkTopology() != null) {
            double capacity = platform.getNetworkTopology().getLinkBandwidthMbPerSecond() * 1_000_000.0;
            if (!Double.isFinite(capacity) || capacity < Double.MIN_NORMAL) {
                throw new IllegalArgumentException("V2 topology capacity conversion is unsupported");
            }
        }
        if (movement.isCoherentStorageDataflowV3() != (platform.getSourceStorage() != null)) {
            throw new IllegalArgumentException("sourceStorage must be declared exactly for storage V3 models");
        }
        return new SimulationModels(sim, fs, movement, networkEvidence, eventRecordingEnabled, costModel(sim));
    }

    private static Parameters.CostModel costModel(JsonObject sim) {
        String value = text(sim, "costModel", "DATACENTER");
        try {
            return Parameters.CostModel.valueOf(value);
        } catch (IllegalArgumentException unknownModel) {
            throw new IllegalArgumentException(
                    "simulation.costModel must be DATACENTER or VM, got: " + value, unknownModel);
        }
    }

    private static List<Long> readSeeds(JsonObject root) {
        List<Long> seeds = new ArrayList<Long>();
        if (root.has("seeds")) {
            for (JsonElement value : array(root, "seeds")) {
                seeds.add(longValue(value, "seeds"));
            }
        } else {
            seeds.add(42L);
        }
        if (seeds.isEmpty() || seeds.size() > 100 || new HashSet<Long>(seeds).size() != seeds.size()) {
            throw new IllegalArgumentException("seeds requires 1..100 distinct integers");
        }
        return seeds;
    }

    private static List<Candidate> readCandidates(
            JsonObject root,
            List<String> paths,
            List<Double> arrivals,
            PlatformProfile platform,
            SimulationModels models,
            List<Long> seeds,
            SimulationConfig.FrequencyPolicy globalFrequencyPolicy,
            ExecutionMode executionMode) {
        List<Candidate> candidates = new ArrayList<Candidate>();
        Set<String> algorithmIds = new HashSet<String>();
        Set<String> candidateIds = new HashSet<String>();
        Map<String, Long> deadlines = deadlineSecondsByWorkflow(models.sim, paths, executionMode);
        long defaultDeadline = models.sim.has("deadlineSeconds")
                ? longValue(models.sim.get("deadlineSeconds"), "deadlineSeconds")
                : 0L;
        JsonArray algorithms = array(root, "algorithms");
        String track = null;
        int expansionCount = executionMode == ExecutionMode.PER_WORKFLOW ? paths.size() : 1;
        for (int workflowIndex = 0; workflowIndex < expansionCount; workflowIndex++) {
            boolean perWorkflow = executionMode == ExecutionMode.PER_WORKFLOW;
            List<String> candidatePaths = perWorkflow ? Collections.singletonList(paths.get(workflowIndex)) : paths;
            List<Double> candidateArrivals =
                    perWorkflow ? Collections.singletonList(arrivals.get(workflowIndex)) : arrivals;
            String workflowPath = perWorkflow ? paths.get(workflowIndex) : null;
            long deadline = defaultDeadline;
            if (perWorkflow) {
                String basename = workflowBasename(workflowPath);
                if (deadlines.containsKey(basename)) {
                    deadline = deadlines.get(basename).longValue();
                }
            }
            for (JsonElement item : algorithms) {
                if (!item.isJsonObject()) {
                    throw new IllegalArgumentException("algorithms must contain objects");
                }
                JsonObject algorithm = item.getAsJsonObject();
                fields(algorithm, "algorithm", "id", "scheduler", "planner", "dataflowAssignment", "frequencyPolicy");
                org.workflowsim.data.v2.DataflowAssignmentConfig assignment = algorithm.has("dataflowAssignment")
                        ? org.workflowsim.data.v2.DataflowAssignmentConfig.decodeConfig(
                                algorithm.get("dataflowAssignment"))
                        : org.workflowsim.data.v2.DataflowAssignmentConfig.off();
                if (assignment.isEnabled()
                        && (!models.movement.usesCoherentDataflowRuntime() || !models.networkEvidence.isEnabled())) {
                    throw new IllegalArgumentException(
                            "Online destination binding in Workbench requires a coherent model and complete lifecycle "
                                    + "recording");
                }
                String algorithmId = text(algorithm, "id", null);
                if (!algorithmId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                        || (workflowIndex == 0 && !algorithmIds.add(algorithmId))) {
                    throw new IllegalArgumentException("Algorithm IDs must be unique safe identifiers");
                }
                String candidateId = perWorkflow ? perWorkflowCandidateId(workflowPath, algorithmId) : algorithmId;
                if (!candidateIds.add(candidateId)) {
                    throw new IllegalArgumentException("Candidate IDs must be unique safe identifiers");
                }
                Parameters.PlanningAlgorithm planner =
                        Parameters.PlanningAlgorithm.valueOf(text(algorithm, "planner", "INVALID"));
                ResolvedScheduler resolved = resolveSchedulerPolicy(algorithm, assignment, planner);
                rejectWorkbenchExcludedScheduler(resolved.scheduler);
                track = requireSameDecisionLayer(track, assignment, planner);
                SimulationConfig.FrequencyPolicy candidateFrequencyPolicy =
                        algorithmFrequencyPolicy(algorithm, algorithmId, globalFrequencyPolicy, platform);
                org.workflowsim.utils.SimulationConfig.Builder configBuilder = SimulationConfig.builder(
                                candidatePaths, platform.getVms().size())
                        .workflowArrivalSeconds(candidateArrivals)
                        .fileSystem(models.fileSystem)
                        .dataMovementModel(models.movement)
                        .networkEvidence(models.networkEvidence)
                        .eventRecordingEnabled(models.eventRecordingEnabled)
                        .dataflowAssignment(assignment)
                        .schedulingAlgorithm(resolved.scheduler)
                        .planningAlgorithm(planner)
                        .randomSeed(seeds.get(0))
                        .costModel(models.costModel)
                        .frequencyPolicy(candidateFrequencyPolicy);
                if (resolved.registeredProvider != null) {
                    configBuilder.registeredSchedulingAlgorithm(resolved.registeredProvider);
                }
                SimulationConfig config = configBuilder
                        .runtimeScale(decimal(models.sim, "runtimeScale", 1, true))
                        .runtimeReferenceMips(decimal(models.sim, "runtimeReferenceMips", 1000, true))
                        .cloudSimMinEventIntervalSeconds(decimal(models.sim, "minEventIntervalSeconds", 0.1, true))
                        .eventRecorderCap(integer(
                                models.sim,
                                "eventRecorderCap",
                                SimulationConfig.DEFAULT_EVENT_RECORDER_CAP,
                                1,
                                Integer.MAX_VALUE))
                        .deadline(deadline)
                        .taskCostMatrix(matrix(models.sim))
                        .build();
                candidates.add(new Candidate(candidateId, algorithmId, workflowPath, config));
            }
        }
        if (candidates.isEmpty() || candidates.size() * seeds.size() > 500) {
            throw new IllegalArgumentException("An experiment needs 1..500 runs");
        }
        return candidates;
    }

    private static String workflowBasename(String workflowPath) {
        return new java.io.File(workflowPath).getName();
    }

    private static String perWorkflowCandidateId(String workflowPath, String algorithmId) {
        String basename = workflowBasename(workflowPath);
        int extension = basename.lastIndexOf('.');
        String stem = extension < 0 ? basename : basename.substring(0, extension);
        String candidateId = stem + "__" + algorithmId;
        if (!candidateId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "PER_WORKFLOW candidate IDs must be unique safe identifiers; invalid workflow basename or ID: "
                            + candidateId);
        }
        return candidateId;
    }

    private static Map<String, Long> deadlineSecondsByWorkflow(
            JsonObject sim, List<String> paths, ExecutionMode executionMode) {
        if (!sim.has("deadlineSecondsByWorkflow")) {
            return Collections.emptyMap();
        }
        if (executionMode != ExecutionMode.PER_WORKFLOW) {
            throw new IllegalArgumentException(
                    "simulation.deadlineSecondsByWorkflow is only allowed with executionMode=PER_WORKFLOW");
        }
        Set<String> knownBasenames = new HashSet<String>();
        for (String path : paths) {
            knownBasenames.add(workflowBasename(path));
        }
        Map<String, Long> deadlines = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, JsonElement> entry :
                object(sim, "deadlineSecondsByWorkflow").entrySet()) {
            String basename = entry.getKey();
            if (!knownBasenames.contains(basename)) {
                throw new IllegalArgumentException(
                        "simulation.deadlineSecondsByWorkflow contains unknown workflow basename: " + basename);
            }
            long deadline = longValue(entry.getValue(), "simulation.deadlineSecondsByWorkflow." + basename);
            if (deadline < 0L) {
                throw new IllegalArgumentException(
                        "simulation.deadlineSecondsByWorkflow values must be nonnegative integers");
            }
            deadlines.put(basename, Long.valueOf(deadline));
        }
        return deadlines;
    }

    private static final class ResolvedScheduler {
        final Parameters.SchedulingAlgorithm scheduler;
        final org.workflowsim.experiment.registry.SchedulingAlgorithmProvider registeredProvider;

        ResolvedScheduler(
                Parameters.SchedulingAlgorithm scheduler,
                org.workflowsim.experiment.registry.SchedulingAlgorithmProvider registeredProvider) {
            this.scheduler = scheduler;
            this.registeredProvider = registeredProvider;
        }
    }

    private static void rejectWorkbenchExcludedScheduler(Parameters.SchedulingAlgorithm scheduler) {
        if (scheduler == Parameters.SchedulingAlgorithm.CARBON_WINDOW_DEFER) {
            throw new IllegalArgumentException("Workbench does not accept SchedulingAlgorithm.CARBON_WINDOW_DEFER:"
                    + " the deferral engine needs platform.carbonWindows, and budget windows are research-protocol"
                    + " declarations outside the v1 workbench surface (CARBON_DIMENSION_DESIGN.md 2.3). Run it"
                    + " through the SimulationRunner API instead.");
        }
    }

    private static ResolvedScheduler resolveSchedulerPolicy(
            JsonObject algorithm,
            org.workflowsim.data.v2.DataflowAssignmentConfig assignment,
            Parameters.PlanningAlgorithm planner) {
        String schedulerText = text(
                algorithm,
                "scheduler",
                assignment.isEnabled()
                        ? "STATIC"
                        : planner == Parameters.PlanningAlgorithm.INVALID ? "FCFS" : "STATIC");
        org.workflowsim.experiment.registry.SchedulingAlgorithmProvider registeredProvider = null;
        Parameters.SchedulingAlgorithm scheduler;
        try {
            scheduler = Parameters.SchedulingAlgorithm.valueOf(schedulerText);
        } catch (IllegalArgumentException unknownEnumLabel) {

            if (!assignment.isEnabled()
                    && planner == Parameters.PlanningAlgorithm.INVALID
                    && org.workflowsim.experiment.registry.AlgorithmRegistry.isRegistered(schedulerText)) {
                registeredProvider = org.workflowsim.experiment.registry.AlgorithmRegistry.find(schedulerText);
                scheduler = Parameters.SchedulingAlgorithm.INVALID;
            } else {
                throw unknownEnumLabel;
            }
        }
        if (registeredProvider == null
                && (!AlgorithmCatalog.isSupportedBySimulationRunner(planner)
                        || !AlgorithmCatalog.isSupportedBySimulationRunner(scheduler))) {
            throw new IllegalArgumentException("Workbench requires maintained built-in algorithms");
        }
        return new ResolvedScheduler(scheduler, registeredProvider);
    }

    private static String requireSameDecisionLayer(
            String track,
            org.workflowsim.data.v2.DataflowAssignmentConfig assignment,
            Parameters.PlanningAlgorithm planner) {
        String candidateTrack = assignment.isEnabled()
                ? "DATAFLOW_BINDING"
                : planner == Parameters.PlanningAlgorithm.INVALID
                        ? "ONLINE"
                        : planner.name().startsWith("STATIC_") ? "INDEPENDENT" : "DAG_STATIC";
        if (track != null && !track.equals(candidateTrack)) {
            throw new IllegalArgumentException("Cannot compare different decision layers in one experiment");
        }
        return candidateTrack;
    }

    private static final class SimulationModels {
        final JsonObject sim;
        final ReplicaCatalog.FileSystem fileSystem;
        final DataMovementModel movement;
        final NetworkEvidenceConfig networkEvidence;
        final boolean eventRecordingEnabled;
        final Parameters.CostModel costModel;

        SimulationModels(
                JsonObject sim,
                ReplicaCatalog.FileSystem fileSystem,
                DataMovementModel movement,
                NetworkEvidenceConfig networkEvidence,
                boolean eventRecordingEnabled,
                Parameters.CostModel costModel) {
            this.sim = sim;
            this.fileSystem = fileSystem;
            this.movement = movement;
            this.networkEvidence = networkEvidence;
            this.eventRecordingEnabled = eventRecordingEnabled;
            this.costModel = costModel;
        }
    }

    public void validateInputs() {
        for (Candidate candidate : candidates) {
            try (SimulationSession ignored = SimulationSession.open(candidate.config)) {
                WorkflowParser parser = new WorkflowParser(0);
                parser.parse();
                List<Integer> taskIds = new ArrayList<Integer>();
                List<Integer> vmIds = new ArrayList<Integer>();
                for (PlatformProfile.VmSpec vm : platform.getVms()) {
                    vmIds.add(vm.getId());
                }
                Map<String, Double> sizes = new LinkedHashMap<String, Double>();
                boolean localPlanner =
                        candidate.config.getPlanningAlgorithm().name().startsWith("LOCAL_");
                boolean independent =
                        candidate.config.getPlanningAlgorithm().name().startsWith("STATIC_");
                for (Task task : parser.getTaskList()) {
                    taskIds.add(task.getCloudletId());
                    if (independent
                            && (!task.getParentList().isEmpty()
                                    || !task.getChildList().isEmpty())) {
                        throw new IllegalArgumentException("Independent-task planner cannot accept DAG edges");
                    }
                    if (localPlanner) {
                        for (FileItem file : task.getFileList()) {
                            Double before = sizes.put(file.getName(), file.getSize());
                            if (before != null && Double.compare(before, file.getSize()) != 0) {
                                throw new IllegalArgumentException(
                                        "LOCAL planner requires unambiguous file sizes: " + file.getName());
                            }
                        }
                    }
                }
                if (candidate.config.getTaskCostMatrix() != null
                        && !candidate.config.getTaskCostMatrix().covers(taskIds, vmIds)) {
                    throw new IllegalArgumentException("taskCostMatrix must cover every parsed task and VM");
                }
            }
        }
    }

    private static TaskCostMatrix matrix(JsonObject sim) {
        if (!sim.has("taskCostMatrix") || sim.get("taskCostMatrix").isJsonNull()) {
            return null;
        }
        TaskCostMatrix.Builder builder = TaskCostMatrix.builder();
        for (JsonElement value : array(sim, "taskCostMatrix")) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("taskCostMatrix entries must be objects");
            }
            JsonObject entry = value.getAsJsonObject();
            fields(entry, "taskCostMatrix", "taskId", "vmId", "executionSeconds");
            builder.put(
                    integer(entry, "taskId", -1, 0, Integer.MAX_VALUE),
                    integer(entry, "vmId", -1, 0, Integer.MAX_VALUE),
                    decimal(entry, "executionSeconds", -1, true));
        }
        return builder.build();
    }

    private static PlatformProfile platform(JsonObject value) {
        fields(
                value,
                "platform",
                "vmMips",
                "bandwidthMbPerSecond",
                "cpuCostPerSecond",
                "storageTransferMbPerSecond",
                "networkTopology",
                "sourceStorage",
                "idlePowerWatts",
                "maxPowerWatts",
                "carbonIntensityGramsPerKWh",
                "frequencyTiers",
                "frequencyPolicy",
                "vmProfiles");
        JsonArray mips = array(value, "vmMips");
        if (mips.size() < 1 || mips.size() > 64) {
            throw new IllegalArgumentException("vmMips requires 1..64 VMs");
        }
        int bandwidth = integer(value, "bandwidthMbPerSecond", 1000, 1, 1000000000);

        double idlePowerWatts = optionalPlatformPower(value, "idlePowerWatts");
        double maxPowerWatts = optionalPlatformPower(value, "maxPowerWatts");
        if (idlePowerWatts > maxPowerWatts) {
            throw new IllegalArgumentException(
                    "platform.idlePowerWatts (" + idlePowerWatts + ") exceeds maxPowerWatts (" + maxPowerWatts
                            + "); both default to 0 (no power model) when omitted");
        }

        List<PlatformProfile.FrequencyTier> frequencyTiers = frequencyTiers(value, "platform");

        if (frequencyTiers != null && (idlePowerWatts > 0.0 || maxPowerWatts > 0.0)) {
            throw new IllegalArgumentException(
                    "platform.frequencyTiers and platform.idlePowerWatts/maxPowerWatts are mutually exclusive:"
                            + " the tier table REPLACES the flat fields — one source of truth"
                            + " (BAT-D4, DVFS_DIMENSION_DESIGN.md §2.5)");
        }
        double cpuCostPerSecond = decimal(value, "cpuCostPerSecond", 3, false);
        Map<Integer, VmProfile> vmProfiles =
                vmProfiles(value, mips.size(), idlePowerWatts, maxPowerWatts, cpuCostPerSecond, frequencyTiers);

        Double carbonIntensityGramsPerKWh = value.has("carbonIntensityGramsPerKWh")
                        && !value.get("carbonIntensityGramsPerKWh").isJsonNull()
                ? Double.valueOf(
                        number(value.get("carbonIntensityGramsPerKWh"), "platform.carbonIntensityGramsPerKWh", false))
                : null;
        if (carbonIntensityGramsPerKWh != null
                && frequencyTiers == null
                && idlePowerWatts <= 0.0
                && maxPowerWatts <= 0.0
                && !anyVmProfileHasPower(vmProfiles)) {
            throw new IllegalArgumentException("platform.carbonIntensityGramsPerKWh ("
                    + carbonIntensityGramsPerKWh
                    + ") requires a powered platform: declare platform.idlePowerWatts/maxPowerWatts,"
                    + " platform.frequencyTiers, or powered platform.vmProfiles — carbon multiplies the energy"
                    + " chain and an unpowered profile makes the accounting vacuous (BAT-C3)");
        }
        PlatformProfile.CostSpec costs = new PlatformProfile.CostSpec(cpuCostPerSecond, 0, 0, 0);
        PlatformProfile.Builder result = PlatformProfile.builder("workbench-platform")
                .costs(costs)
                .storage(new PlatformProfile.StorageSpec(
                        1000000000L, integer(value, "storageTransferMbPerSecond", 15, 1, Integer.MAX_VALUE)));
        for (int id = 0; id < mips.size(); id++) {
            double speed = number(mips.get(id), "vmMips", true);
            if (speed > 1e12) {
                throw new IllegalArgumentException("vmMips exceeds workbench limit");
            }
            result.addHost(new PlatformProfile.HostSpec(id, 2, speed * 2, 2048, Math.max(10000L, bandwidth), 1000000));
            VmProfile profile = vmProfiles.get(Integer.valueOf(id));
            PlatformProfile.CostSpec vmCosts =
                    profile == null ? null : new PlatformProfile.CostSpec(profile.cpuCostPerSecond, 0, 0, 0);
            result.addVm(new PlatformProfile.VmSpec(
                    id,
                    speed,
                    1,
                    512,
                    bandwidth,
                    10000,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                    vmCosts,
                    profile == null ? idlePowerWatts : profile.idlePowerWatts,
                    profile == null ? maxPowerWatts : profile.maxPowerWatts,
                    carbonIntensityGramsPerKWh,
                    profile == null ? frequencyTiers : profile.frequencyTiers));
            result.pinVmToHost(id, id);
        }
        applySourceStorage(value, result, mips);
        applyNetworkTopology(value, result, mips);
        return result.build();
    }

    private static final class VmProfile {
        final double idlePowerWatts;
        final double maxPowerWatts;
        final double cpuCostPerSecond;
        final List<PlatformProfile.FrequencyTier> frequencyTiers;

        VmProfile(
                double idlePowerWatts,
                double maxPowerWatts,
                double cpuCostPerSecond,
                List<PlatformProfile.FrequencyTier> frequencyTiers) {
            this.idlePowerWatts = idlePowerWatts;
            this.maxPowerWatts = maxPowerWatts;
            this.cpuCostPerSecond = cpuCostPerSecond;
            this.frequencyTiers = frequencyTiers;
        }
    }

    private static Map<Integer, VmProfile> vmProfiles(
            JsonObject platform,
            int vmCount,
            double globalIdlePowerWatts,
            double globalMaxPowerWatts,
            double globalCpuCostPerSecond,
            List<PlatformProfile.FrequencyTier> globalFrequencyTiers) {
        if (!platform.has("vmProfiles")) {
            return Collections.emptyMap();
        }
        JsonArray declared = array(platform, "vmProfiles");
        if (declared.size() == 0) {
            throw new IllegalArgumentException("platform.vmProfiles must be a non-empty array when declared");
        }
        boolean globalFlatPower =
                declaredPlatformField(platform, "idlePowerWatts") || declaredPlatformField(platform, "maxPowerWatts");
        boolean globalTiers = globalFrequencyTiers != null;
        Map<Integer, VmProfile> profiles = new LinkedHashMap<Integer, VmProfile>();
        for (JsonElement item : declared) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("platform.vmProfiles entries must be objects");
            }
            JsonObject entry = item.getAsJsonObject();
            fields(
                    entry,
                    "vmProfiles",
                    "vmId",
                    "idlePowerWatts",
                    "maxPowerWatts",
                    "cpuCostPerSecond",
                    "frequencyTiers");
            if (!entry.has("vmId")) {
                throw new IllegalArgumentException("platform.vmProfiles.vmId is required");
            }
            long vmId = longValue(entry.get("vmId"), "platform.vmProfiles.vmId");
            if (vmId < 0L || vmId >= vmCount) {
                throw new IllegalArgumentException(
                        "platform.vmProfiles.vmId must correspond to a vmMips index in 0.." + (vmCount - 1));
            }
            Integer id = Integer.valueOf((int) vmId);
            if (profiles.containsKey(id)) {
                throw new IllegalArgumentException("platform.vmProfiles.vmId values must be unique: " + vmId);
            }
            boolean profileFlatPower = entry.has("idlePowerWatts") || entry.has("maxPowerWatts");
            boolean profileTiers = entry.has("frequencyTiers");
            if ((globalFlatPower && (profileFlatPower || profileTiers))
                    || (globalTiers && (profileFlatPower || profileTiers))
                    || (profileFlatPower && profileTiers)) {
                throw new IllegalArgumentException(
                        "platform.idlePowerWatts/maxPowerWatts or platform.frequencyTiers and"
                                + " platform.vmProfiles.idlePowerWatts/maxPowerWatts/frequencyTiers are mutually"
                                + " exclusive: one source of truth is required per power model");
            }
            double idlePowerWatts = decimal(entry, "idlePowerWatts", globalIdlePowerWatts, false);
            double maxPowerWatts = decimal(entry, "maxPowerWatts", globalMaxPowerWatts, false);
            if (idlePowerWatts > maxPowerWatts) {
                throw new IllegalArgumentException("platform.vmProfiles VM " + vmId + " idlePowerWatts ("
                        + idlePowerWatts + ") exceeds maxPowerWatts (" + maxPowerWatts + ")");
            }
            List<PlatformProfile.FrequencyTier> tiers = globalFrequencyTiers;
            if (profileTiers) {
                if (entry.get("frequencyTiers").isJsonNull()) {
                    throw new IllegalArgumentException(
                            "platform.vmProfiles.frequencyTiers must be a non-empty array when declared");
                }
                tiers = frequencyTiers(entry, "platform.vmProfiles");
            }
            profiles.put(
                    id,
                    new VmProfile(
                            idlePowerWatts,
                            maxPowerWatts,
                            decimal(entry, "cpuCostPerSecond", globalCpuCostPerSecond, false),
                            tiers));
        }
        return profiles;
    }

    private static boolean declaredPlatformField(JsonObject platform, String key) {
        return platform.has(key) && !platform.get(key).isJsonNull();
    }

    private static boolean anyVmProfileHasPower(Map<Integer, VmProfile> profiles) {
        for (VmProfile profile : profiles.values()) {
            if (profile.idlePowerWatts > 0.0 || profile.maxPowerWatts > 0.0 || profile.frequencyTiers != null) {
                return true;
            }
        }
        return false;
    }

    private static List<PlatformProfile.FrequencyTier> frequencyTiers(JsonObject value, String subject) {
        if (!value.has("frequencyTiers") || value.get("frequencyTiers").isJsonNull()) {
            return null;
        }
        JsonArray declared = array(value, "frequencyTiers");
        if (declared.size() == 0) {
            throw new IllegalArgumentException(subject
                    + ".frequencyTiers must be a non-empty array when declared (BAT-D4;"
                    + " omit the field for the v1 single-tier platform)");
        }
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (JsonElement item : declared) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException(subject + ".frequencyTiers entries must be objects");
            }
            JsonObject tier = item.getAsJsonObject();
            fields(tier, subject + ".frequencyTiers", "mips", "idlePowerWatts", "maxPowerWatts");
            tiers.add(new PlatformProfile.FrequencyTier(
                    decimal(tier, "mips", -1, true),
                    decimal(tier, "idlePowerWatts", -1, false),
                    decimal(tier, "maxPowerWatts", -1, false)));
        }
        return tiers;
    }

    private static SimulationConfig.FrequencyPolicy frequencyPolicy(JsonObject value, PlatformProfile platform) {
        String policyText = text(value, "frequencyPolicy", "DEFAULT");
        SimulationConfig.FrequencyPolicy policy;
        try {
            policy = SimulationConfig.FrequencyPolicy.valueOf(policyText);
        } catch (IllegalArgumentException unknownPolicy) {
            throw new IllegalArgumentException(
                    "platform.frequencyPolicy must be one of DEFAULT, MIN_POWER," + " EDP_HEURISTIC (BAT-D4), got: "
                            + policyText,
                    unknownPolicy);
        }
        if (policy != SimulationConfig.FrequencyPolicy.DEFAULT && !anyVmHasFrequencyTiers(platform)) {
            throw new IllegalArgumentException("platform.frequencyPolicy (" + policy
                    + ") requires platform.frequencyTiers or platform.vmProfiles[].frequencyTiers: on an all-plain"
                    + " platform the policy would be silently inert (BAT-D4)");
        }
        return policy;
    }

    private static SimulationConfig.FrequencyPolicy algorithmFrequencyPolicy(
            JsonObject algorithm,
            String algorithmId,
            SimulationConfig.FrequencyPolicy globalPolicy,
            PlatformProfile platform) {
        String policyText = text(algorithm, "frequencyPolicy", globalPolicy.name());
        SimulationConfig.FrequencyPolicy policy;
        try {
            policy = SimulationConfig.FrequencyPolicy.valueOf(policyText);
        } catch (IllegalArgumentException unknownPolicy) {
            throw new IllegalArgumentException(
                    "algorithm.frequencyPolicy for algorithm '" + algorithmId
                            + "' must be one of DEFAULT, MIN_POWER, EDP_HEURISTIC, got: " + policyText,
                    unknownPolicy);
        }
        if (policy != SimulationConfig.FrequencyPolicy.DEFAULT && !anyVmHasFrequencyTiers(platform)) {
            throw new IllegalArgumentException("algorithm.frequencyPolicy (" + policy + ") for algorithm '"
                    + algorithmId + "' requires platform.frequencyTiers or"
                    + " platform.vmProfiles[].frequencyTiers; the policy would otherwise be silently inert");
        }
        return policy;
    }

    private static boolean anyVmHasFrequencyTiers(PlatformProfile platform) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasFrequencyTiers()) {
                return true;
            }
        }
        return false;
    }

    private static void applySourceStorage(JsonObject value, PlatformProfile.Builder result, JsonArray mips) {
        if (value.has("sourceStorage")) {
            JsonObject source = object(value, "sourceStorage");
            fields(
                    source,
                    "sourceStorage",
                    "attachmentHostId",
                    "readBandwidthMbPerSecond",
                    "writeBandwidthMbPerSecond",
                    "networkBandwidthMbPerSecond");
            if (source.size() != 4) {
                throw new IllegalArgumentException("sourceStorage requires all four explicit fields");
            }
            result.sourceStorage(org.workflowsim.data.v2.DataflowStorageSpec.of(
                    integer(source, "attachmentHostId", -1, 0, mips.size() - 1),
                    number(source.get("readBandwidthMbPerSecond"), "sourceStorage.readBandwidthMbPerSecond", true),
                    number(source.get("writeBandwidthMbPerSecond"), "sourceStorage.writeBandwidthMbPerSecond", true),
                    number(
                            source.get("networkBandwidthMbPerSecond"),
                            "sourceStorage.networkBandwidthMbPerSecond",
                            true)));
        }
    }

    private static void applyNetworkTopology(JsonObject value, PlatformProfile.Builder result, JsonArray mips) {
        if (value.has("networkTopology") && !value.get("networkTopology").isJsonNull()) {
            JsonObject topology = object(value, "networkTopology");
            fields(
                    topology,
                    "networkTopology",
                    "k",
                    "linkBandwidthMbPerSecond",
                    "coreSwitchCount",
                    "hostEdgePlacements");
            int k = integer(topology, "k", 4, 2, 16);
            Integer cores = topology.has("coreSwitchCount")
                            && !topology.get("coreSwitchCount").isJsonNull()
                    ? integer(topology, "coreSwitchCount", 0, 1, 64)
                    : null;
            Map<Integer, Integer> placements = null;
            if (topology.has("hostEdgePlacements")
                    && !topology.get("hostEdgePlacements").isJsonNull()) {
                placements = new LinkedHashMap<Integer, Integer>();
                for (Map.Entry<String, JsonElement> entry :
                        object(topology, "hostEdgePlacements").entrySet()) {
                    int host = Integer.parseInt(entry.getKey());
                    if (host < 0 || !Integer.toString(host).equals(entry.getKey())) {
                        throw new IllegalArgumentException("Invalid host placement ID");
                    }
                    long edge = longValue(entry.getValue(), "hostEdgePlacements");
                    if (edge < 0 || edge > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Invalid edge ID");
                    }
                    placements.put(host, (int) edge);
                }
            }
            NetworkTopologySpec spec = NetworkTopologySpec.fatTree(
                    k, decimal(topology, "linkBandwidthMbPerSecond", 1, true), cores, placements);
            List<Integer> hosts = new ArrayList<Integer>();
            for (int id = 0; id < mips.size(); id++) {
                hosts.add(id);
            }
            FatTreeTopology.fromSpec(spec, hosts);
            result.networkTopology(spec);
        }
    }

    private static DataMovementModel movement(JsonObject sim) {
        String kind = text(sim, "dataMovementModel", "LEGACY_WORKFLOWSIM_V1");
        if (!"FIXED_ENDPOINT_NO_CONTENTION_V1".equals(kind) && sim.has("fixedEndpoint")) {
            throw new IllegalArgumentException("fixedEndpoint parameters require its data movement model");
        }
        switch (DataMovementModel.Kind.valueOf(kind)) {
            case LEGACY_WORKFLOWSIM_V1:
                return DataMovementModel.legacyWorkflowsimV1();
            case PRE_EXECUTION_TRANSFER_DELAY_V1:
                return DataMovementModel.preExecutionTransferDelayV1();
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1:
                return DataMovementModel.preExecutionTransferDelayWithContentionV1();
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1:
                return DataMovementModel.fatTreeContentionV1();
            case COHERENT_FILE_DATAFLOW_V2:
                return DataMovementModel.coherentFileDataflowV2();
            case COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2:
                return DataMovementModel.coherentFileDataflowNoContentionV2();
            case COHERENT_STORAGE_DATAFLOW_V3:
                return DataMovementModel.coherentStorageDataflowV3();
            case COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3:
                return DataMovementModel.coherentStorageDataflowNoContentionV3();
            case FIXED_ENDPOINT_NO_CONTENTION_V1:
                JsonObject fixed = object(sim, "fixedEndpoint");
                fields(fixed, "fixedEndpoint", "accessBandwidth", "latencySeconds", "sourceBandwidth");
                return DataMovementModel.fixedEndpointNoContention(
                        decimal(fixed, "accessBandwidth", -1, true),
                        decimal(fixed, "latencySeconds", 0, false),
                        decimal(fixed, "sourceBandwidth", -1, true));
            default:
                throw new IllegalArgumentException("Unsupported data movement model");
        }
    }

    public String getName() {
        return name;
    }

    public JsonObject getSource() {
        return source.deepCopy();
    }

    public PlatformProfile getPlatform() {
        return platform;
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    public List<Long> getSeeds() {
        return seeds;
    }

    public int getRunCount() {
        return candidates.size() * seeds.size();
    }

    public static final class Candidate {
        public final String id;
        public final String algorithmId;
        public final String workflowPath;
        public final SimulationConfig config;

        Candidate(String id, String algorithmId, String workflowPath, SimulationConfig config) {
            this.id = id;
            this.algorithmId = algorithmId;
            this.workflowPath = workflowPath;
            this.config = config;
        }
    }

    private static JsonElement readJson(com.google.gson.stream.JsonReader reader, int depth) throws IOException {
        if (depth > 64) {
            throw new IllegalArgumentException("Configuration nesting is too deep");
        }
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) {
                        throw new IllegalArgumentException("Duplicate JSON field: " + key);
                    }
                    object.add(key, readJson(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(readJson(reader, depth + 1));
                }
                reader.endArray();
                return array;
            case STRING:
                return new com.google.gson.JsonPrimitive(reader.nextString());
            case NUMBER:
                return new com.google.gson.JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN:
                return new com.google.gson.JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return com.google.gson.JsonNull.INSTANCE;
            default:
                throw new IllegalArgumentException("Unexpected JSON token: " + reader.peek());
        }
    }

    private static void fields(JsonObject value, String subject, String... allowed) {
        Set<String> known = new HashSet<String>(Arrays.asList(allowed));
        for (String key : value.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException(subject + " has unknown field: " + key);
            }
        }
    }

    private static JsonObject object(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return value.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return value.getAsJsonArray(key);
    }

    private static String text(JsonObject value, String key, String fallback) {
        if (!value.has(key) && fallback != null) {
            return fallback;
        }
        if (!value.has(key)
                || !value.get(key).isJsonPrimitive()
                || !value.get(key).getAsJsonPrimitive().isString()
                || value.get(key).getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException(key + " must be a nonempty string");
        }
        return value.get(key).getAsString();
    }

    private static double decimal(JsonObject value, String key, double fallback, boolean positive) {
        if (!value.has(key)) {
            if (fallback < 0) {
                throw new IllegalArgumentException("Missing " + key);
            }
            return fallback;
        }
        return number(value.get(key), key, positive);
    }

    private static double optionalPlatformPower(JsonObject value, String key) {
        if (!value.has(key) || value.get(key).isJsonNull()) {
            return 0.0;
        }
        return number(value.get(key), "platform." + key, false);
    }

    private static double number(JsonElement value, String field, boolean positive) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || (positive ? number <= 0 : number < 0)) {
            throw new IllegalArgumentException(
                    field + " must be finite and " + (positive ? "positive" : "nonnegative"));
        }
        return number;
    }

    private static boolean booleanValue(JsonElement value, String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static long longValue(JsonElement value, String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an exact integer", e);
        }
    }

    private static int integer(JsonObject value, String key, int fallback, int min, int max) {
        long n = value.has(key) ? longValue(value.get(key), key) : fallback;
        if (n < min || n > max) {
            throw new IllegalArgumentException(key + " is out of range " + min + ".." + max);
        }
        return (int) n;
    }
}
