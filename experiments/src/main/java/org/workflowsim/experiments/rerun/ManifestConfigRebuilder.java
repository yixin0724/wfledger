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

/**
 * Stage 3: reconstruct the v4 manifest back into an executable {@link SimulationConfig} and
 * {@link PlatformProfile} (see the "reconstruction rules" contract).
 *
 * <p>Reconstruction is a faithful translation only: every field takes the recorded manifest
 * value, with no guessing, no default filling, and no silent correction; combination legality is
 * left entirely to the existing builders ({@code SimulationConfig.build},
 * {@code PlatformProfile.build}, {@code FailureModelConfig.build}, and so on). Any validation
 * failure -- including mutually exclusive algorithm combinations, unknown enum names, missing
 * fields, data movement model parameters that disagree with the factory semantics, and VM-Host
 * preflight placement that disagrees with the current placement logic -- is mapped uniformly to
 * {@code RECONSTRUCTION_REJECTED}, which is a legal verdict rather than a program error.</p>
 *
 * <p>{@code configuration.workflowPaths} uses the locations resolved and sha256-verified in
 * stage 2, not the original machine paths recorded in the manifest.</p>
 */
public final class ManifestConfigRebuilder {

    private ManifestConfigRebuilder() {}

    /** Reconstruction product: executable configuration plus platform description. */
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

    /**
     * Rebuild the configuration and the platform from the evidence-package manifest.
     *
     * @param evidence evidence package already validated in stage 1
     * @param inputs   inputs already located and hash-verified in stage 2
     * @throws RerunFailureException {@code RECONSTRUCTION_REJECTED}; the exception message carries
     *         the original validation failure cause and the field path
     */
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

    // ---- platform ----

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
                    // BAT-E4.1 (2026-10-27): power fields are forwarded on a round trip (a
                    // missing key is treated as 0 = pre-energy historical manifest; non-zero values
                    // are guarded by the max>=idle check of the VmSpec constructor).
                    optionalNonNegativeDouble(vm.get("idlePowerWatts"), "platform.vms[].idlePowerWatts"),
                    optionalNonNegativeDouble(vm.get("maxPowerWatts"), "platform.vms[].maxPowerWatts"),
                    optionalCarbonIntensity(
                            vm.get("carbonIntensityGramsPerKWh"), "platform.vms[].carbonIntensityGramsPerKWh"),
                    // A1 (2026-11-02): frequency tier table round-trip rebuild. A missing key or
                    // an empty table = v1 single-tier platform (legal default); a non-empty table
                    // is checked by the VmSpec constructor for "strictly descending + mutually
                    // exclusive with the flat power fields" -- an illegal table in the record must
                    // be rejected by reconstruction (RECONSTRUCTION_REJECTED), never silently
                    // corrected.
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

    /**
     * The deterministic VM-Host preflight placement after reconstruction must match the manifest
     * record; a mismatch means the current placement logic differs from the version that produced
     * the evidence, so the rerun is no longer trustworthy and is treated as rejected.
     */
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

    // ---- configuration ----

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
                .costModel(enumValue(
                        CostModel.class,
                        requireString(config, "costModel", "configuration.costModel"),
                        "configuration.costModel"))
                // A1 (2026-11-02): frequency tier assignment policy round-trip. A missing
                // key = pre-D4 shape/DEFAULT, consistent with the value semantics "missing key
                // == the only legal DEFAULT"; when present, the policy is rebuilt from the
                // recorded value.
                .frequencyPolicy(rebuildFrequencyPolicy(config.get("frequencyPolicy")))
                .overheadModel(
                        rebuildOverheadModel(requireObject(config, "overheadModel", "configuration.overheadModel")))
                .clusteringParameters(
                        rebuildClustering(requireObject(config, "clustering", "configuration.clustering")))
                .failureModel(rebuildFailureModel(requireObject(config, "failureModel", "configuration.failureModel")))
                .dataMovementModel(rebuildDataMovementModel(
                        requireObject(config, "dataMovementModel", "configuration.dataMovementModel")));
        // BAT-R7 (2026-10-27 seventh review round, red on the first cross-seam
        // chain run): evidence rerun of registered-label algorithms.
        // The manifest records schedulingAlgorithm=INVALID +
        // registeredSchedulingAlgorithm=<label>, but the rebuilder previously read only the
        // former -> INVALID has no provider -> RECONSTRUCTION_REJECTED; the evidence of any
        // registry algorithm had never been rerun (each seam had unit tests; nobody walked the
        // chain).
        // Fix: the label is resolved back to a provider through AlgorithmRegistry.find (SPI); when
        // the classpath does not register that label, fail fast and reject (rerun diagnosis
        // requires the algorithm to be available on the rerun machine -- consistent with evidence
        // chain honesty).
        // Note (continuing the BAT-R4 three-shape discipline): in non-registered historical
        // evidence the key appears as JSON null (manifest writer serializeNulls) and has() is
        // true -- null must be excluded too for the key to count as "declared".
        if (config.has("registeredSchedulingAlgorithm")
                && !config.get("registeredSchedulingAlgorithm").isJsonNull()) {
            String registeredLabel = requireString(
                    config, "registeredSchedulingAlgorithm", "configuration.registeredSchedulingAlgorithm");
            builder.registeredSchedulingAlgorithm(
                    org.workflowsim.experiment.registry.AlgorithmRegistry.find(registeredLabel));
        }
        // Historical v4 absence is explicitly OFF; a present extension must never be ignored.
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
        // The endpoint parameters of a shared factory instance are part of the model
        // semantics; recorded values that disagree mean the manifest comes from an incompatible
        // model version, so reject instead of silently adopting the factory default.
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

    // ---- JSON helpers (every failure is IllegalArgumentException, wrapped by rebuild) ----

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

    /**
     * A1 (2026-11-02): round-trip rebuild of {@code platform.vms[].frequencyTiers}.
     *
     * <p>A missing key or an empty array = v1 single-tier platform (legal default, with no
     * guessing at all). Table entries are rebuilt in recorded order; strict descending order,
     * mutual exclusivity with the flat power fields, and the power range are guarded by the
     * constructor validation of {@link PlatformProfile.VmSpec} -- this method is a faithful
     * translation, not a validator.</p>
     */
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

    /**
     * A1 (2026-11-02): round-trip rebuild of {@code configuration.frequencyPolicy}.
     *
     * <p>A missing key or a null value maps to {@code DEFAULT} (the only legal value of pre-D4
     * frozen evidence; the same rule as the key-absence emission of ExperimentManifestWriter).
     * Unknown enum names are still rejected by {@code enumValue}, with no downgrade.</p>
     */
    private static SimulationConfig.FrequencyPolicy rebuildFrequencyPolicy(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return SimulationConfig.FrequencyPolicy.DEFAULT;
        }
        // A type error is rejected explicitly here instead of letting getAsString() throw
        // Gson's IllegalStateException: both end up as RECONSTRUCTION_REJECTED
        // (RerunDiffExecutor.execute catches IllegalArgumentException and IllegalStateException
        // alike), but an explicit rejection gives actionable information about "which field, which
        // type was expected" instead of a framework-level message (A1 review round,
        // diagnostic-quality fix; that review once misjudged it as a verdict contract hole, since
        // disproved by measurement).
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("configuration.frequencyPolicy must be a JSON string when present");
        }
        return enumValue(
                SimulationConfig.FrequencyPolicy.class, element.getAsString(), "configuration.frequencyPolicy");
    }

    /**
     * BAT-E4.1 (2026-10-27): optional non-negative power field; a missing key or a null value is
     * treated as 0 (pre-energy historical shape), while negative and non-numeric values are
     * rejected explicitly -- power is a physical quantity, and silently accepting an illegal value
     * violates the explicit-failure discipline.
     */
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
