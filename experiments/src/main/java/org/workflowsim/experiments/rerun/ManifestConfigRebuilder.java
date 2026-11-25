package org.workflowsim.experiments.rerun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters.CostModel;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class ManifestConfigRebuilder {

    private ManifestConfigRebuilder() {}

    public static final class RebuiltConfiguration {

        private final SimulationConfig config;
        private final PlatformProfile platform;

        RebuiltConfiguration(SimulationConfig config, PlatformProfile platform) {
            this.config = config;
            this.platform = platform;
        }

        public SimulationConfig getConfig() {
            return config;
        }

        public PlatformProfile getPlatform() {
            return platform;
        }
    }

    public static RebuiltConfiguration rebuild(RerunEvidence evidence, RerunInputs inputs)
            throws RerunFailureException {
        JsonObject manifest = evidence.getManifest();
        try {
            JsonObject platformSection = requireObject(manifest, "platform", "platform");
            JsonObject configSection = requireObject(manifest, "configuration", "configuration");
            PlatformProfile platform = rebuildPlatform(platformSection);
            SimulationConfig config = rebuildConfiguration(configSection, inputs);
            verifyPreflightAssignments(platformSection, platform);
            if (config.getDataMovementModel().isCoherentStorageDataflowV3() != (platform.getSourceStorage() != null)) {
                throw new IllegalArgumentException("sourceStorage does not match reconstructed model");
            }
            if (config.getDataMovementModel().usesCoherentDataflowRuntime() && platform.getNetworkTopology() != null) {
                int k = platform.getNetworkTopology().getK();
                double capacity = platform.getNetworkTopology().getLinkBandwidthMbPerSecond() * 1_000_000.0;
                if (k > 32 || !Double.isFinite(capacity) || capacity < Double.MIN_NORMAL) {
                    throw new IllegalArgumentException("Unsupported V2 topology or converted capacity");
                }
            }
            return new RebuiltConfiguration(config, platform);
        } catch (IllegalArgumentException
                | IllegalStateException
                | ClassCastException
                | UnsupportedOperationException
                | SimulationConfigurationException e) {
            throw new RerunFailureException(
                    RerunVerdict.RECONSTRUCTION_REJECTED,
                    "Manifest cannot be rebuilt into a runnable configuration: " + e.getMessage(),
                    e);
        }
    }

    private static PlatformProfile rebuildPlatform(JsonObject platform) {
        PlatformProfile.Builder builder = PlatformProfile.builder(requireString(platform, "name", "platform.name"));
        JsonArray hosts = requireArray(platform, "hosts", "platform.hosts");
        if (hosts.size() == 0) {
            throw new IllegalArgumentException("platform.hosts is empty");
        }
        for (JsonElement element : hosts) {
            JsonObject host = element.getAsJsonObject();
            builder.addHost(new PlatformProfile.HostSpec(
                    requireInt(host, "id", "platform.hosts[].id"),
                    requireInt(host, "pes", "platform.hosts[].pes"),
                    requireDouble(host, "mipsPerPe", "platform.hosts[].mipsPerPe"),
                    requireInt(host, "ramMb", "platform.hosts[].ramMb"),
                    requireLong(host, "bandwidth", "platform.hosts[].bandwidth"),
                    requireLong(host, "storageMb", "platform.hosts[].storageMb")));
        }
        JsonArray vms = requireArray(platform, "vms", "platform.vms");
        if (vms.size() == 0) {
            throw new IllegalArgumentException("platform.vms is empty");
        }
        for (JsonElement element : vms) {
            JsonObject vm = element.getAsJsonObject();
            int id = requireInt(vm, "id", "platform.vms[].id");
            PlatformProfile.CloudletSchedulerMode schedulerMode = enumValue(
                    PlatformProfile.CloudletSchedulerMode.class,
                    requireString(vm, "schedulerMode", "platform.vms[].schedulerMode"),
                    "platform.vms[].schedulerMode");
            builder.addVm(new PlatformProfile.VmSpec(
                    id,
                    requireDouble(vm, "mips", "platform.vms[].mips"),
                    requireInt(vm, "pes", "platform.vms[].pes"),
                    requireInt(vm, "ramMb", "platform.vms[].ramMb"),
                    requireLong(vm, "bandwidth", "platform.vms[].bandwidth"),
                    requireLong(vm, "imageSizeMb", "platform.vms[].imageSizeMb"),
                    requireString(vm, "vmm", "platform.vms[].vmm"),
                    schedulerMode,
                    optionalCosts(vm.get("costs"), "platform.vms[].costs"),
                    optionalNonNegativeDouble(vm.get("idlePowerWatts"), "platform.vms[].idlePowerWatts"),
                    optionalNonNegativeDouble(vm.get("maxPowerWatts"), "platform.vms[].maxPowerWatts"),
                    optionalCarbonIntensity(
                            vm.get("carbonIntensityGramsPerKWh"), "platform.vms[].carbonIntensityGramsPerKWh"),
                    rebuildFrequencyTiers(vm.get("frequencyTiers"))));
            Integer pinned = optionalInt(vm.get("pinnedHostId"));
            if (pinned != null) {
                builder.pinVmToHost(id, pinned.intValue());
            }
        }
        JsonElement rawWindows = platform.get("carbonWindows");
        if (rawWindows != null && !rawWindows.isJsonNull()) {
            for (JsonElement element : rawWindows.getAsJsonArray()) {
                JsonObject window = element.getAsJsonObject();
                builder.carbonWindow(
                        optionalNonNegativeDouble(window.get("startSeconds"), "carbonWindows[].startSeconds"),
                        optionalNonNegativeDouble(window.get("endSeconds"), "carbonWindows[].endSeconds"),
                        optionalNonNegativeDouble(window.get("greenWatts"), "carbonWindows[].greenWatts"));
            }
        }
        JsonObject storage = requireObject(platform, "storage", "platform.storage");
        builder.storage(new PlatformProfile.StorageSpec(
                requireLong(storage, "capacityMb", "platform.storage.capacityMb"),
                requireInt(storage, "maxTransferRateMbPerSecond", "platform.storage.maxTransferRateMbPerSecond")));
        builder.costs(costSpec(requireObject(platform, "costs", "platform.costs"), "platform.costs"));
        if (platform.has("sourceStorage")) {
            JsonObject source = requireObject(platform, "sourceStorage", "platform.sourceStorage");
            builder.sourceStorage(org.workflowsim.data.v2.DataflowStorageSpec.of(
                    requireInt(source, "attachmentHostId", "platform.sourceStorage.attachmentHostId"),
                    requireDouble(
                            source, "readBandwidthMbPerSecond", "platform.sourceStorage.readBandwidthMbPerSecond"),
                    requireDouble(
                            source, "writeBandwidthMbPerSecond", "platform.sourceStorage.writeBandwidthMbPerSecond"),
                    requireDouble(
                            source,
                            "networkBandwidthMbPerSecond",
                            "platform.sourceStorage.networkBandwidthMbPerSecond")));
        }
        JsonElement topology = platform.get("networkTopology");
        if (topology != null && !topology.isJsonNull()) {
            builder.networkTopology(rebuildTopology(topology.getAsJsonObject()));
        }
        return builder.build();
    }

    private static NetworkTopologySpec rebuildTopology(JsonObject topology) {
        String kind = requireString(topology, "kind", "platform.networkTopology.kind");
        if (!NetworkTopologySpec.Kind.FAT_TREE.name().equals(kind)) {
            throw new IllegalArgumentException("Unsupported platform.networkTopology.kind: " + kind);
        }
        Integer coreSwitchCount = optionalInt(topology.get("coreSwitchCount"));
        Map<Integer, Integer> placements = null;
        JsonElement element = topology.get("hostEdgePlacements");
        if (element != null && !element.isJsonNull()) {
            placements = new LinkedHashMap<Integer, Integer>();
            for (Map.Entry<String, JsonElement> entry :
                    element.getAsJsonObject().entrySet()) {
                placements.put(
                        Integer.valueOf(Integer.parseInt(entry.getKey())),
                        Integer.valueOf(entry.getValue().getAsInt()));
            }
        }
        return NetworkTopologySpec.fatTree(
                requireInt(topology, "k", "platform.networkTopology.k"),
                requireDouble(
                        topology, "linkBandwidthMbPerSecond", "platform.networkTopology.linkBandwidthMbPerSecond"),
                coreSwitchCount,
                placements);
    }

    private static void verifyPreflightAssignments(JsonObject platformSection, PlatformProfile platform) {
        Map<Integer, Integer> assignments = platform.getVmHostAssignments();
        for (JsonElement element : requireArray(platformSection, "vms", "platform.vms")) {
            JsonObject vm = element.getAsJsonObject();
            int id = requireInt(vm, "id", "platform.vms[].id");
            Integer recorded = optionalInt(vm.get("preflightHostId"));
            Integer actual = assignments.get(Integer.valueOf(id));
            if (!Objects.equals(recorded, actual)) {
                throw new IllegalArgumentException("VM " + id
                        + " preflight host assignment differs: manifest recorded " + recorded
                        + " but current placement logic computes " + actual);
            }
        }
    }

    private static SimulationConfig rebuildConfiguration(JsonObject config, RerunInputs inputs) {
        JsonArray recordedPaths = requireArray(config, "workflowPaths", "configuration.workflowPaths");
        if (recordedPaths.size() != inputs.size()) {
            throw new IllegalArgumentException("configuration.workflowPaths has "
                    + recordedPaths.size() + " entries but " + inputs.size()
                    + " inputs were resolved");
        }
        List<String> workflowPaths = new ArrayList<String>();
        for (int i = 0; i < inputs.size(); i++) {
            workflowPaths.add(inputs.get(i).getResolvedPath().toString());
        }
        SimulationConfig.Builder builder = SimulationConfig.builder(
                        workflowPaths, requireInt(config, "vmCount", "configuration.vmCount"))
                .workflowArrivalSeconds(doubleList(
                        requireArray(config, "workflowArrivalSeconds", "configuration.workflowArrivalSeconds")))
                .schedulingAlgorithm(enumValue(
                        SchedulingAlgorithm.class,
                        requireString(config, "schedulingAlgorithm", "configuration.schedulingAlgorithm"),
                        "configuration.schedulingAlgorithm"))
                .planningAlgorithm(enumValue(
                        PlanningAlgorithm.class,
                        requireString(config, "planningAlgorithm", "configuration.planningAlgorithm"),
                        "configuration.planningAlgorithm"))
                .reduceMethod(optionalString(config.get("reduceMethod")))
                .deadline(requireLong(config, "deadline", "configuration.deadline"))
                .fileSystem(enumValue(
                        ReplicaCatalog.FileSystem.class,
                        requireString(config, "fileSystem", "configuration.fileSystem"),
                        "configuration.fileSystem"))
                .randomSeed(requireLong(config, "rootSeed", "configuration.rootSeed"))
                .runtimeScale(requireDouble(config, "runtimeScale", "configuration.runtimeScale"))
                .runtimeReferenceMips(
                        requireDouble(config, "runtimeReferenceMips", "configuration.runtimeReferenceMips"))
                .cloudSimMinEventIntervalSeconds(requireDouble(
                        config, "cloudSimMinEventIntervalSeconds", "configuration.cloudSimMinEventIntervalSeconds"))
                .eventRecorderCap(rebuildEventRecorderCap(config.get("eventRecorderCap")))
                .boundedSlowdownThresholdSeconds(
                        rebuildBoundedSlowdownThreshold(config.get("boundedSlowdownThresholdSeconds")))
                .costModel(enumValue(
                        CostModel.class,
                        requireString(config, "costModel", "configuration.costModel"),
                        "configuration.costModel"))
                .frequencyPolicy(rebuildFrequencyPolicy(config.get("frequencyPolicy")))
                .overheadModel(
                        rebuildOverheadModel(requireObject(config, "overheadModel", "configuration.overheadModel")))
                .clusteringParameters(
                        rebuildClustering(requireObject(config, "clustering", "configuration.clustering")))
                .failureModel(rebuildFailureModel(requireObject(config, "failureModel", "configuration.failureModel")))
                .dataMovementModel(rebuildDataMovementModel(
                        requireObject(config, "dataMovementModel", "configuration.dataMovementModel")));

        if (config.has("registeredSchedulingAlgorithm")
                && !config.get("registeredSchedulingAlgorithm").isJsonNull()) {
            String registeredLabel = requireString(
                    config, "registeredSchedulingAlgorithm", "configuration.registeredSchedulingAlgorithm");
            builder.registeredSchedulingAlgorithm(
                    org.workflowsim.experiment.registry.AlgorithmRegistry.find(registeredLabel));
        }

        if (config.has("networkEvidence")) {
            builder.networkEvidence(
                    org.workflowsim.data.NetworkEvidenceConfigCodec.decodeConfig(config.get("networkEvidence")));
        }
        if (config.has("dataflowAssignment")) {
            builder.dataflowAssignment(
                    org.workflowsim.data.v2.DataflowAssignmentConfig.decodeConfig(config.get("dataflowAssignment")));
        }
        JsonElement taskCostMatrix = config.get("taskCostMatrix");
        if (taskCostMatrix != null && !taskCostMatrix.isJsonNull()) {
            builder.taskCostMatrix(rebuildTaskCostMatrix(taskCostMatrix.getAsJsonObject()));
        }
        return builder.build();
    }

    private static org.workflowsim.utils.OverheadModelConfig rebuildOverheadModel(JsonObject overhead) {
        return org.workflowsim.utils.OverheadModelConfig.builder()
                .workflowEngineDelayInterval(requireInt(
                        overhead,
                        "workflowEngineDelayInterval",
                        "configuration.overheadModel.workflowEngineDelayInterval"))
                .bandwidth(requireDouble(overhead, "bandwidth", "configuration.overheadModel.bandwidth"))
                .workflowEngineDelays(delayMap(
                        overhead.get("workflowEngineDelays"), "configuration.overheadModel.workflowEngineDelays"))
                .queueDelays(delayMap(overhead.get("queueDelays"), "configuration.overheadModel.queueDelays"))
                .postDelays(delayMap(overhead.get("postDelays"), "configuration.overheadModel.postDelays"))
                .clusteringDelays(
                        delayMap(overhead.get("clusteringDelays"), "configuration.overheadModel.clusteringDelays"))
                .build();
    }

    private static Map<Integer, DistributionSpec> delayMap(JsonElement element, String path) {
        Map<Integer, DistributionSpec> result = new LinkedHashMap<Integer, DistributionSpec>();
        if (element == null || element.isJsonNull()) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            result.put(
                    Integer.valueOf(Integer.parseInt(entry.getKey())),
                    distributionSpec(entry.getValue().getAsJsonObject(), path + "[" + entry.getKey() + "]"));
        }
        return result;
    }

    private static ClusteringParameters rebuildClustering(JsonObject clustering) {
        return new ClusteringParameters(
                requireInt(clustering, "clustersNum", "configuration.clustering.clustersNum"),
                requireInt(clustering, "clustersSize", "configuration.clustering.clustersSize"),
                enumValue(
                        ClusteringParameters.ClusteringMethod.class,
                        requireString(clustering, "method", "configuration.clustering.method"),
                        "configuration.clustering.method"),
                optionalString(clustering.get("code")));
    }

    private static FailureModelConfig rebuildFailureModel(JsonObject failure) {
        FailureModelConfig.Builder builder = FailureModelConfig.builder()
                .clusteringAlgorithm(enumValue(
                        FailureParameters.FTCluteringAlgorithm.class,
                        requireString(failure, "clusteringAlgorithm", "configuration.failureModel.clusteringAlgorithm"),
                        "configuration.failureModel.clusteringAlgorithm"))
                .monitorMode(enumValue(
                        FailureParameters.FTCMonitor.class,
                        requireString(failure, "monitorMode", "configuration.failureModel.monitorMode"),
                        "configuration.failureModel.monitorMode"))
                .generatorMode(enumValue(
                        FailureParameters.FTCFailure.class,
                        requireString(failure, "generatorMode", "configuration.failureModel.generatorMode"),
                        "configuration.failureModel.generatorMode"))
                .distributionFamily(enumValue(
                        DistributionFamily.class,
                        requireString(failure, "distributionFamily", "configuration.failureModel.distributionFamily"),
                        "configuration.failureModel.distributionFamily"))
                .maxTotalRetryJobs(
                        requireInt(failure, "maxTotalRetryJobs", "configuration.failureModel.maxTotalRetryJobs"));
        JsonArray matrix = requireArray(failure, "generators", "configuration.failureModel.generators");
        if (matrix.size() > 0) {
            DistributionSpec[][] specs = new DistributionSpec[matrix.size()][];
            for (int row = 0; row < matrix.size(); row++) {
                specs[row] = specRow(matrix.get(row), "configuration.failureModel.generators[" + row + "]");
            }
            builder.generatorSpecs(specs);
        }
        JsonObject keyed = requireObject(failure, "generatorsByVmId", "configuration.failureModel.generatorsByVmId");
        if (keyed.entrySet().size() > 0) {
            Map<Integer, DistributionSpec[]> rows = new LinkedHashMap<Integer, DistributionSpec[]>();
            for (Map.Entry<String, JsonElement> entry : keyed.entrySet()) {
                rows.put(
                        Integer.valueOf(Integer.parseInt(entry.getKey())),
                        specRow(
                                entry.getValue(),
                                "configuration.failureModel.generatorsByVmId[" + entry.getKey() + "]"));
            }
            builder.generatorSpecsByVmId(rows);
        }
        return builder.build();
    }

    private static DistributionSpec[] specRow(JsonElement element, String path) {
        JsonArray row = element.getAsJsonArray();
        DistributionSpec[] specs = new DistributionSpec[row.size()];
        for (int i = 0; i < row.size(); i++) {
            specs[i] = distributionSpec(row.get(i).getAsJsonObject(), path + "[" + i + "]");
        }
        return specs;
    }

    private static DistributionSpec distributionSpec(JsonObject item, String path) {
        DistributionFamily family =
                enumValue(DistributionFamily.class, requireString(item, "family", path + ".family"), path + ".family");
        double scale = requireDouble(item, "scale", path + ".scale");
        double shape = requireDouble(item, "shape", path + ".shape");
        Double priorShape = optionalDouble(item.get("priorShape"));
        Double priorScale = optionalDouble(item.get("priorScale"));
        Double likelihoodPrior = optionalDouble(item.get("likelihoodPrior"));
        if (priorShape == null && priorScale == null && likelihoodPrior == null) {
            return DistributionSpec.of(family, scale, shape);
        }
        if (priorShape == null || priorScale == null || likelihoodPrior == null) {
            throw new IllegalArgumentException(path + " has partially specified priors");
        }
        return DistributionSpec.withPriors(
                family,
                scale,
                shape,
                priorShape.doubleValue(),
                priorScale.doubleValue(),
                likelihoodPrior.doubleValue());
    }

    private static DataMovementModel rebuildDataMovementModel(JsonObject movement) {
        String kindName = requireString(movement, "kind", "configuration.dataMovementModel.kind");
        DataMovementModel.Kind kind =
                enumValue(DataMovementModel.Kind.class, kindName, "configuration.dataMovementModel.kind");
        double access = requireDouble(
                movement,
                "accessLinkBandwidthMbPerSecond",
                "configuration.dataMovementModel.accessLinkBandwidthMbPerSecond");
        double latency = requireDouble(
                movement, "accessLinkLatencySeconds", "configuration.dataMovementModel.accessLinkLatencySeconds");
        double source = requireDouble(
                movement,
                "sourceEndpointBandwidthMbPerSecond",
                "configuration.dataMovementModel.sourceEndpointBandwidthMbPerSecond");
        DataMovementModel model;
        switch (kind) {
            case LEGACY_WORKFLOWSIM_V1:
                model = DataMovementModel.legacyWorkflowsimV1();
                break;
            case FIXED_ENDPOINT_NO_CONTENTION_V1:
                model = DataMovementModel.fixedEndpointNoContention(access, latency, source);
                break;
            case PRE_EXECUTION_TRANSFER_DELAY_V1:
                model = DataMovementModel.preExecutionTransferDelayV1();
                break;
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1:
                model = DataMovementModel.preExecutionTransferDelayWithContentionV1();
                break;
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1:
                model = DataMovementModel.fatTreeContentionV1();
                break;
            case COHERENT_FILE_DATAFLOW_V2:
                model = DataMovementModel.coherentFileDataflowV2();
                break;
            case COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2:
                model = DataMovementModel.coherentFileDataflowNoContentionV2();
                break;
            case COHERENT_STORAGE_DATAFLOW_V3:
                model = DataMovementModel.coherentStorageDataflowV3();
                break;
            case COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3:
                model = DataMovementModel.coherentStorageDataflowNoContentionV3();
                break;
            default:
                throw new IllegalArgumentException("Unsupported configuration.dataMovementModel.kind: " + kindName);
        }

        if (Double.compare(model.getAccessLinkBandwidthMbPerSecond(), access) != 0
                || Double.compare(model.getAccessLinkLatencySeconds(), latency) != 0
                || Double.compare(model.getSourceEndpointBandwidthMbPerSecond(), source) != 0) {
            throw new IllegalArgumentException("configuration.dataMovementModel parameters ("
                    + access + ", " + latency + ", " + source + ") do not match the rebuilt "
                    + kindName + " model (" + model.getAccessLinkBandwidthMbPerSecond() + ", "
                    + model.getAccessLinkLatencySeconds() + ", "
                    + model.getSourceEndpointBandwidthMbPerSecond() + ")");
        }
        return model;
    }

    private static TaskCostMatrix rebuildTaskCostMatrix(JsonObject matrix) {
        TaskCostMatrix.Builder builder = TaskCostMatrix.builder();
        JsonArray entries = requireArray(matrix, "entries", "configuration.taskCostMatrix.entries");
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            builder.put(
                    requireInt(entry, "taskId", "configuration.taskCostMatrix.entries[].taskId"),
                    requireInt(entry, "vmId", "configuration.taskCostMatrix.entries[].vmId"),
                    requireDouble(
                            entry, "executionSeconds", "configuration.taskCostMatrix.entries[].executionSeconds"));
        }
        return builder.build();
    }

    private static PlatformProfile.CostSpec costSpec(JsonObject costs, String path) {
        return new PlatformProfile.CostSpec(
                requireDouble(costs, "cpuPerSecond", path + ".cpuPerSecond"),
                requireDouble(costs, "memory", path + ".memory"),
                requireDouble(costs, "storage", path + ".storage"),
                requireDouble(costs, "bandwidth", path + ".bandwidth"));
    }

    private static PlatformProfile.CostSpec optionalCosts(JsonElement element, String path) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return costSpec(element.getAsJsonObject(), path);
    }

    private static List<Double> doubleList(JsonArray array) {
        List<Double> values = new ArrayList<Double>();
        for (JsonElement element : array) {
            values.add(Double.valueOf(element.getAsDouble()));
        }
        return values;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String path) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + " references unknown value: " + name);
        }
    }

    private static JsonObject requireObject(JsonObject parent, String field, String path) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(path + " is missing or not an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject parent, String field, String path) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(path + " is missing or not an array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject parent, String field, String path) {
        JsonElement element = parent.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(path + " is missing or not a string");
        }
        return element.getAsString();
    }

    private static int requireInt(JsonObject parent, String field, String path) {
        return primitive(parent, field, path).getAsInt();
    }

    private static long requireLong(JsonObject parent, String field, String path) {
        return primitive(parent, field, path).getAsLong();
    }

    private static double requireDouble(JsonObject parent, String field, String path) {
        return primitive(parent, field, path).getAsDouble();
    }

    private static JsonElement primitive(JsonObject parent, String field, String path) {
        JsonElement element = parent.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(path + " is missing or not a number");
        }
        return element;
    }

    private static String optionalString(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static Integer optionalInt(JsonElement element) {
        return element == null || element.isJsonNull() ? null : Integer.valueOf(element.getAsInt());
    }

    private static Double optionalDouble(JsonElement element) {
        return element == null || element.isJsonNull() ? null : Double.valueOf(element.getAsDouble());
    }

    private static List<PlatformProfile.FrequencyTier> rebuildFrequencyTiers(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return java.util.Collections.emptyList();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("platform.vms[].frequencyTiers must be an array when present");
        }
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("platform.vms[].frequencyTiers entries must be objects");
            }
            JsonObject tier = item.getAsJsonObject();
            tiers.add(new PlatformProfile.FrequencyTier(
                    requireDouble(tier, "mips", "platform.vms[].frequencyTiers[].mips"),
                    optionalNonNegativeDouble(
                            tier.get("idlePowerWatts"), "platform.vms[].frequencyTiers[].idlePowerWatts"),
                    optionalNonNegativeDouble(
                            tier.get("maxPowerWatts"), "platform.vms[].frequencyTiers[].maxPowerWatts")));
        }
        return tiers;
    }

    private static SimulationConfig.FrequencyPolicy rebuildFrequencyPolicy(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return SimulationConfig.FrequencyPolicy.DEFAULT;
        }

        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("configuration.frequencyPolicy must be a JSON string when present");
        }
        return enumValue(
                SimulationConfig.FrequencyPolicy.class, element.getAsString(), "configuration.frequencyPolicy");
    }

    /**
     * Rebuilds the declared bounded-slowdown floor.
     *
     * <p>FGCS-R6 (2026-11-25): evidence written before the floor became a declared parameter has no field and
     * was computed with the then hard-coded 10 s, which is the default; a present value must be a finite
     * positive JSON number and is never replaced.
     */
    private static double rebuildBoundedSlowdownThreshold(JsonElement element) {
        if (element == null) {
            return SimulationConfig.DEFAULT_BOUNDED_SLOWDOWN_THRESHOLD_SECONDS;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                    "configuration.boundedSlowdownThresholdSeconds must be a JSON number when present");
        }
        double value = element.getAsDouble();
        if (!(value > 0.0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    "configuration.boundedSlowdownThresholdSeconds must be finite and positive: " + value);
        }
        return value;
    }

    private static int rebuildEventRecorderCap(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return SimulationConfig.DEFAULT_EVENT_RECORDER_CAP;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("configuration.eventRecorderCap must be a JSON number when present");
        }
        long value;
        try {
            value = element.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(
                    "configuration.eventRecorderCap must be an exact integer when present", e);
        }
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "configuration.eventRecorderCap must be a positive int when present: " + value);
        }
        return (int) value;
    }

    private static Double optionalCarbonIntensity(JsonElement element, String field) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a JSON number when present");
        }
        double value = element.getAsDouble();
        if (value < 0.0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite and >= 0: " + value);
        }
        return Double.valueOf(value);
    }

    private static double optionalNonNegativeDouble(JsonElement element, String field) {
        if (element == null || element.isJsonNull()) {
            return 0.0;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a JSON number when present");
        }
        double value = element.getAsDouble();
        if (value < 0.0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite and >= 0: " + value);
        }
        return value;
    }
}
