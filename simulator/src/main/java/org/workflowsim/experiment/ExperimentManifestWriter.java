package org.workflowsim.experiment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.WorkflowInputReport;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.SimulationConfig;

public final class ExperimentManifestWriter {

    static final String SCHEMA_V3 = "workflowsim-experiment-manifest-v3";

    static final String SCHEMA_V4 = "workflowsim-experiment-manifest-v4";

    public static final String EVENTS_REVISION_CONTRACT = "F-006/F-007";

    private ExperimentManifestWriter() {}

    static void requireNetworkLedgerReference(SimulationReport report, List<Map<String, Object>> artifacts) {
        if (report.getConfig().getDataflowAssignmentConfig().isEnabled()
                && (!report.getConfig().getNetworkEvidenceConfig().isEnabled()
                        || report.getDataflowComputeRequests() == null)) {
            throw new UnsupportedOperationException(
                    "Online assignment certification requires complete lifecycle capture and frozen compute"
                            + "requests; use an enabled lifecycle mode");
        }
        org.workflowsim.data.DataMovementModel model = report.getConfig().getDataMovementModel();
        if (model.isCoherentStorageDataflowV3() != (report.getPlatform().getSourceStorage() != null)) {
            throw new IllegalArgumentException("sourceStorage presence differs from its declared model");
        }
        org.workflowsim.data.NetworkEvidenceConfig option = report.getConfig().getNetworkEvidenceConfig();
        org.workflowsim.data.NetworkEvidenceConfigCodec.requireCompatible(model, option);
        String expected = org.workflowsim.data.NetworkEvidenceConfigCodec.artifactRole(option);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String role : new String[] {"network-ledger", "file-lifecycle", "storage-lifecycle"}) {
            counts.put(role, 0);
        }
        for (Map<String, Object> artifact : artifacts) {
            Object role = artifact.get("role");
            if (counts.containsKey(role)) {
                counts.put((String) role, counts.get(role) + 1);
            }
        }
        if (expected != null && counts.get(expected) == 0) {
            throw new UnsupportedOperationException(
                    "Network evidence export requires a complete artifact bundle; use ExperimentArtifactWriter");
        }
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (count.getValue() > 1 || (count.getValue() > 0 && !count.getKey().equals(expected))) {
                throw new IllegalArgumentException(
                        "Network evidence artifact roles disagree with recording configuration");
            }
        }
        if (model.usesCoherentDataflowRuntime() != (report.getDataflowPlan() != null)) {
            throw new IllegalArgumentException("Coherent core file plan presence disagrees with model");
        }
    }

    static Map<String, Object> networkContextSnapshot(SimulationReport report) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("configuration", configuration(report));
        root.put("platform", platform(report.getPlatform()));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("simulationEndSeconds", report.getSimulationEndSeconds());
        result.put("jobs", report.getJobs());
        result.put("actualVmHostAssignments", report.getActualVmHostAssignments());
        root.put("result", result);
        return root;
    }

    static Map<String, Object> fileLifecycleContextSnapshot(SimulationReport report) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("configuration", configuration(report));
        root.put("platform", platform(report.getPlatform()));
        root.put("workflowGraph", report.getWorkflowGraph());
        root.put("dataflowPlan", report.getDataflowPlan());
        if (report.getConfig().getDataflowAssignmentConfig().isEnabled()) {
            root.put("dataflowComputeRequests", report.getDataflowComputeRequests());
        }
        root.put("result", result(report));
        return root;
    }

    public static void writeJson(SimulationReport report, Path outputFile) throws IOException {
        writeJson(report, outputFile, (ExperimentEvidenceContext) null);
    }

    public static void writeJson(SimulationReport report, Path outputFile, ExperimentEvidenceContext evidenceContext)
            throws IOException {
        if (report == null || outputFile == null) {
            throw new IllegalArgumentException("Report and output path are required");
        }
        writeJson(report, outputFile, Collections.<Map<String, Object>>emptyList(), evidenceContext);
    }

    static void writeJson(SimulationReport report, Path outputFile, List<Map<String, Object>> artifacts)
            throws IOException {
        writeJson(report, outputFile, artifacts, null);
    }

    static void writeJson(
            SimulationReport report,
            Path outputFile,
            List<Map<String, Object>> artifacts,
            ExperimentEvidenceContext evidenceContext)
            throws IOException {
        if (report == null || outputFile == null) {
            throw new IllegalArgumentException("Report and output path are required");
        }
        requireNetworkLedgerReference(report, artifacts);
        ExperimentArtifactWriter.writeJson(outputFile, toManifest(report, artifacts, evidenceContext));
    }

    static Map<String, Object> toManifest(SimulationReport report, List<Map<String, Object>> artifacts) {
        return toManifest(report, artifacts, null);
    }

    static Map<String, Object> toManifest(
            SimulationReport report, List<Map<String, Object>> artifacts, ExperimentEvidenceContext evidenceContext) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", SCHEMA_V4);
        manifest.put("configuration", configuration(report));
        manifest.put("platform", platform(report.getPlatform()));
        manifest.put("inputs", inputs(report));
        manifest.put("workflowProfile", report.getWorkflowProfile());
        manifest.put("workflowGraph", report.getWorkflowGraph());
        if (report.getConfig().getDataMovementModel().usesCoherentDataflowRuntime()) {
            manifest.put("dataflowPlan", report.getDataflowPlan());
        }
        if (report.getConfig().getDataflowAssignmentConfig().isEnabled()) {
            manifest.put("dataflowComputeRequests", report.getDataflowComputeRequests());
        }
        manifest.put("result", result(report));
        manifest.put("metrics", report.getMetrics());

        Map<String, Object> energy = new LinkedHashMap<String, Object>();
        EnergyAccounting ea = report.getEnergyAccounting();
        energy.put("totalEnergyJoules", Double.valueOf(ea.getTotalEnergyJoules()));
        energy.put("meanVmEnergyJoules", Double.valueOf(ea.getMeanVmEnergyJoules()));
        energy.put("peakPowerWatts", Double.valueOf(ea.getPeakPowerWatts()));
        energy.put("energyMakespanProductJouleSeconds", Double.valueOf(ea.getEnergyMakespanProductJouleSeconds()));
        energy.put("vmEnergyJoules", ea.getVmEnergyJoules());
        manifest.put("energyMetrics", energy);

        CarbonAccounting ca = report.getCarbonAccounting();
        if (ca != null) {
            Map<String, Object> carbon = new LinkedHashMap<String, Object>();
            carbon.put("totalCarbonGrams", Double.valueOf(ca.getTotalCarbonGrams()));
            carbon.put("meanVmCarbonGrams", Double.valueOf(ca.getMeanVmCarbonGrams()));
            carbon.put("carbonMakespanProductGramSeconds", Double.valueOf(ca.getCarbonMakespanProductGramSeconds()));
            carbon.put("perVmCarbonGrams", ca.getPerVmCarbonGrams());
            carbon.put("totalCarbonExcessWattSeconds", Double.valueOf(ca.getTotalCarbonExcessWattSeconds()));
            carbon.put("windowExcessWattSeconds", ca.getWindowExcessWattSeconds());
            manifest.put("carbonMetrics", carbon);
        }
        manifest.put("events", eventSummary(report));
        manifest.put("artifacts", new ArrayList<Map<String, Object>>(artifacts));
        manifest.put("provenance", ExperimentProvenance.capture(evidenceContext));
        manifest.put("runtime", runtime());
        return manifest;
    }

    private static Map<String, Object> configuration(SimulationReport report) {
        SimulationConfig config = report.getConfig();
        Map<String, Object> values = configuration(config);

        if (!values.containsKey("frequencyPolicy") && anyFrequencyTiers(report.getPlatform())) {
            values.put("frequencyPolicy", config.getFrequencyPolicy().name());
        }
        List<String> resolvedInputPaths = new ArrayList<String>();
        for (org.workflowsim.experiment.SimulationReport.InputArtifact input : report.getInputs()) {
            resolvedInputPaths.add(input.getPath());
        }
        values.put("workflowPaths", resolvedInputPaths);
        return values;
    }

    private static boolean anyFrequencyTiers(PlatformProfile platform) {
        if (platform == null || platform.getVms() == null) {
            return false;
        }
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasFrequencyTiers()) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> configurationSnapshot(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration is required");
        }

        return configuration(config);
    }

    public static Map<String, Object> platformSnapshot(PlatformProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Platform is required");
        }
        return platform(profile);
    }

    private static Map<String, Object> runtime() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("javaRuntimeVersion", System.getProperty("java.runtime.version"));
        values.put("javaVendor", System.getProperty("java.vendor"));
        values.put("javaVmName", System.getProperty("java.vm.name"));
        values.put("osName", System.getProperty("os.name"));
        values.put("osArch", System.getProperty("os.arch"));
        return values;
    }

    private static Map<String, Object> configuration(SimulationConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("vmCount", config.getVmCount());
        values.put("executionSemantics", org.workflowsim.utils.TaskExecutionModel.EXECUTION_SEMANTICS);
        values.put("workflowPaths", new ArrayList<String>(config.getWorkflowPaths()));
        values.put("workflowArrivalSeconds", new ArrayList<Double>(config.getWorkflowArrivalSeconds()));
        values.put("workflowArrivalSemantics", "PREDECLARED_AT_TIME_ZERO;SECONDS_FROM_SIMULATION_ZERO");
        values.put("taskCostMatrix", taskCostMatrix(config));
        values.put("schedulingAlgorithm", config.getSchedulingAlgorithm().name());

        values.put(
                "registeredSchedulingAlgorithm",
                config.getRegisteredSchedulingAlgorithmProvider() == null
                        ? null
                        : config.getRegisteredSchedulingAlgorithmProvider().label());

        if (config.getFrequencyPolicy() != SimulationConfig.FrequencyPolicy.DEFAULT) {
            values.put("frequencyPolicy", config.getFrequencyPolicy().name());
        }
        values.put("planningAlgorithm", config.getPlanningAlgorithm().name());
        values.put("algorithmContract", AlgorithmCatalog.forConfiguration(config));
        values.put("reduceMethod", config.getReduceMethod());
        values.put("deadline", config.getDeadline());
        values.put(
                "deadlineSemantics",
                "OBSERVATION_ONLY_FROM_SIMULATION_TIME_ZERO_AGAINST_SIMULATION_END_SECONDS_" + "NO_SCHEDULING_EFFECT");
        values.put("fileSystem", config.getFileSystem().name());
        values.put("rootSeed", config.getRandomSeed());
        values.put("runtimeScale", config.getRuntimeScale());
        values.put("runtimeReferenceMips", config.getRuntimeReferenceMips());
        values.put("cloudSimMinEventIntervalSeconds", config.getCloudSimMinEventIntervalSeconds());

        if (config.getEventRecorderCap() != SimulationConfig.DEFAULT_EVENT_RECORDER_CAP) {
            values.put("eventRecorderCap", config.getEventRecorderCap());
        }
        if (!config.isEventRecordingEnabled()) {
            values.put("eventRecordingEnabled", Boolean.FALSE);
        }
        values.put("costModel", config.getCostModel().name());
        values.put(
                "modeledProcessingCostScope",
                "ABSTRACT_COST_UNITS;CPU_ENVELOPE_MAY_INCLUDE_MODELED_STAGE_IN;"
                        + "DECLARED_FILE_BYTES_ALL_JOB_FILE_ITEMS;DECIMAL_MB_1_000_000_BYTES;"
                        + "NO_INTERNAL_BILLING_ROUNDING;"
                        + "MEMORY_AND_STORAGE_PRICES_ARE_DECLARED_BUT_NOT_CHARGED_BY_THIS_MODEL");
        values.put("dataMovementModel", dataMovementModel(config.getDataMovementModel()));
        if (config.getDataflowAssignmentConfig().isEnabled()) {
            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put(
                    "mode", config.getDataflowAssignmentConfig().getMode().name());
            assignment.put("policy", config.getDataflowAssignmentConfig().getPolicy());
            values.put("dataflowAssignment", assignment);
        }
        if (config.getNetworkEvidenceConfig().isEnabled()) {
            Map<String, Object> networkEvidence = new LinkedHashMap<String, Object>();
            networkEvidence.put(
                    "mode", config.getNetworkEvidenceConfig().getMode().name());
            networkEvidence.put(
                    "maxTraceRecords", config.getNetworkEvidenceConfig().getMaxTraceRecords());
            values.put("networkEvidence", networkEvidence);
        }
        values.put("overheadModel", overheadModel(config.getOverheadModel()));
        values.put("clustering", clustering(config));
        values.put("failureModel", failureModel(config.getFailureModel()));
        return values;
    }

    private static Map<String, Object> dataMovementModel(DataMovementModel model) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", model.getKind().name());
        values.put("accessLinkBandwidthMbPerSecond", model.getAccessLinkBandwidthMbPerSecond());
        values.put("accessLinkLatencySeconds", model.getAccessLinkLatencySeconds());
        values.put("sourceEndpointBandwidthMbPerSecond", model.getSourceEndpointBandwidthMbPerSecond());
        values.put("contentionSemantics", contentionSemantics(model));
        values.put("transferStartSemantics", transferStartSemantics(model));
        values.put("bandwidthUnit", "DECIMAL_MB_PER_SECOND");
        return values;
    }

    static String contentionSemantics(DataMovementModel model) {
        switch (model.getKind()) {
            case LEGACY_WORKFLOWSIM_V1:
                return "LEGACY_IMPLEMENTATION_DEFINED_NO_EXPLICIT_CONTENTION_MODEL";
            case FIXED_ENDPOINT_NO_CONTENTION_V1:
                return "SERIAL_PER_JOB_NO_SHARED_LINK_CONTENTION";
            case PRE_EXECUTION_TRANSFER_DELAY_V1:
                return "PARALLEL_PARENT_GROUPS_NO_SHARED_LINK_CONTENTION";
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1:
                return "FLUID_MAX_MIN_PROGRESSIVE_FILLING_VM_ENDPOINTS_V2";
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1:
                return "FLUID_MAX_MIN_PROGRESSIVE_FILLING_VM_ENDPOINTS_AND_FAT_TREE_LINKS_V2";
            case COHERENT_FILE_DATAFLOW_V2:
                return "COHERENT_FILES_CHECKED_SHARED_MAX_MIN_V2";
            case COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2:
                return "COHERENT_FILES_ISOLATED_PATH_BOTTLENECK_V2";
            case COHERENT_STORAGE_DATAFLOW_V3:
                return "STORAGE_FILES_CHECKED_SHARED_MAX_MIN_V3";
            case COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3:
                return "STORAGE_FILES_ISOLATED_PATH_BOTTLENECK_V3";
            default:
                throw new IllegalArgumentException("Unknown data movement model " + model.getKind());
        }
    }

    static String transferStartSemantics(DataMovementModel model) {
        if (model.isCoherentStorageDataflowV3()) {
            return "CONTROL_READY_REQUEST;STORE_COMMIT_GATE_IF_SHARED;ASYNC_ALL_SUCCESSFUL_OUTPUTS_V3";
        }
        if (model.isCoherentFileDataflowV2()) {
            return "DEPENDENCY_READY_AT_OBSERVATION_V2;PER_FILE_SETTLEMENT_OBSERVATION_V2;COALESCED_FILE_DESTINATION";
        }
        if (model.isPreExecutionTransferDelayV1()) {
            return "PARENT_FINISH_BASED_ARRIVAL_ESTIMATE;EXTERNAL_AT_JOB_READY";
        }
        if (model.isPreExecutionTransferDelayWithContentionV1() || model.isFatTreeContentionV1()) {
            return "ALL_GROUPS_START_AT_JOB_READY;NO_RETROACTIVE_PARENT_PROGRESS";
        }
        return "STAGE_IN_INCLUDED_IN_JOB_EXECUTION_ENVELOPE";
    }

    private static Map<String, Object> taskCostMatrix(SimulationConfig config) {
        if (config.getTaskCostMatrix() == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("unit", "EXECUTION_SECONDS");
        values.put("runtimeConversion", "ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI");
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        java.util.Map<Integer, java.util.Map<Integer, Double>> sorted =
                new java.util.TreeMap<Integer, java.util.Map<Integer, Double>>(
                        config.getTaskCostMatrix().asMap());
        for (Map.Entry<Integer, java.util.Map<Integer, Double>> task : sorted.entrySet()) {
            for (Map.Entry<Integer, Double> vm : new java.util.TreeMap<Integer, Double>(task.getValue()).entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("taskId", task.getKey());
                entry.put("vmId", vm.getKey());
                entry.put("executionSeconds", vm.getValue());
                entries.add(entry);
            }
        }
        values.put("entries", entries);
        return values;
    }

    private static Map<String, Object> overheadModel(OverheadModelConfig model) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workflowEngineDelayInterval", model.getWorkflowEngineDelayInterval());
        values.put("bandwidth", model.getBandwidth());
        values.put("workflowEngineDelays", delayMap(model.getWorkflowEngineDelays()));
        values.put("queueDelays", delayMap(model.getQueueDelays()));
        values.put("postDelays", delayMap(model.getPostDelays()));
        values.put("clusteringDelays", delayMap(model.getClusteringDelays()));
        return values;
    }

    private static Map<String, Object> clustering(SimulationConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "method", config.getClusteringParameters().getClusteringMethod().name());
        values.put("clustersNum", config.getClusteringParameters().getClustersNum());
        values.put("clustersSize", config.getClusteringParameters().getClustersSize());
        values.put("code", config.getClusteringParameters().getCode());
        return values;
    }

    private static Map<String, Object> delayMap(Map<Integer, DistributionSpec> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionSpec> entry : values.entrySet()) {
            DistributionSpec spec = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("family", spec.getFamily().name());
            item.put("scale", spec.getScale());
            item.put("shape", spec.getShape());
            item.put("priorShape", spec.getPriorShape());
            item.put("priorScale", spec.getPriorScale());
            item.put("likelihoodPrior", spec.getLikelihoodPrior());
            result.put(Integer.toString(entry.getKey()), item);
        }
        return result;
    }

    private static Map<String, Object> failureModel(FailureModelConfig model) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("clusteringAlgorithm", model.getClusteringAlgorithm().name());
        values.put("monitorMode", model.getMonitorMode().name());
        values.put("generatorMode", model.getGeneratorMode().name());
        values.put("distributionFamily", model.getDistributionFamily().name());
        values.put("maxTotalRetryJobs", model.getMaxTotalRetryJobs());
        values.put(
                "generatorAddressing", model.usesVmIdKeyedGeneratorRows() ? "VM_ID_KEYED_ROWS" : "DENSE_VM_ID_MATRIX");
        List<List<Map<String, Object>>> matrix = new ArrayList<>();
        for (DistributionSpec[] row : model.getGeneratorSpecs()) {
            matrix.add(failureGeneratorRow(row));
        }
        values.put("generators", matrix);
        Map<String, List<Map<String, Object>>> keyedRows = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionSpec[]> entry :
                model.getGeneratorSpecsByVmId().entrySet()) {
            keyedRows.put(Integer.toString(entry.getKey().intValue()), failureGeneratorRow(entry.getValue()));
        }
        values.put("generatorsByVmId", keyedRows);
        return values;
    }

    private static List<Map<String, Object>> failureGeneratorRow(DistributionSpec[] specs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DistributionSpec spec : specs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("family", spec.getFamily().name());
            item.put("scale", spec.getScale());
            item.put("shape", spec.getShape());
            item.put("priorShape", spec.getPriorShape());
            item.put("priorScale", spec.getPriorScale());
            item.put("likelihoodPrior", spec.getLikelihoodPrior());
            items.add(item);
        }
        return items;
    }

    private static Map<String, Object> platform(PlatformProfile profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", profile.getName());
        values.put("hostCount", profile.getHosts().size());
        values.put("vmCount", profile.getVms().size());
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (PlatformProfile.HostSpec host : profile.getHosts()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", host.getId());
            value.put("pes", host.getPes());
            value.put("mipsPerPe", host.getMipsPerPe());
            value.put("ramMb", host.getRamMb());
            value.put("bandwidth", host.getBandwidth());
            value.put("storageMb", host.getStorageMb());
            hosts.add(value);
        }
        List<Map<String, Object>> vms = new ArrayList<>();
        for (PlatformProfile.VmSpec vm : profile.getVms()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", vm.getId());
            value.put("mips", vm.getMips());
            value.put("pes", vm.getPes());
            value.put("ramMb", vm.getRamMb());
            value.put("bandwidth", vm.getBandwidth());
            value.put("imageSizeMb", vm.getImageSizeMb());
            value.put("vmm", vm.getVmm());
            value.put("schedulerMode", vm.getSchedulerMode().name());

            value.put("idlePowerWatts", vm.getIdlePowerWatts());
            value.put("maxPowerWatts", vm.getMaxPowerWatts());

            if (vm.hasFrequencyTiers()) {
                List<Map<String, Object>> frequencyTiers = new ArrayList<Map<String, Object>>();
                for (PlatformProfile.FrequencyTier tier : vm.getFrequencyTiers()) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("mips", Double.valueOf(tier.getMips()));
                    item.put("idlePowerWatts", Double.valueOf(tier.getIdlePowerWatts()));
                    item.put("maxPowerWatts", Double.valueOf(tier.getMaxPowerWatts()));
                    frequencyTiers.add(item);
                }
                value.put("frequencyTiers", frequencyTiers);
            }

            if (vm.getCarbonIntensityGramsPerKWh() != null) {
                value.put("carbonIntensityGramsPerKWh", vm.getCarbonIntensityGramsPerKWh());
            }
            value.put("pinnedHostId", profile.getPinnedVmHostIds().get(vm.getId()));
            value.put("preflightHostId", profile.getVmHostAssignments().get(vm.getId()));
            value.put("costs", vm.hasCosts() ? costs(vm.getCosts()) : null);
            vms.add(value);
        }
        values.put("hosts", hosts);
        values.put("vms", vms);
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("capacityMb", profile.getStorage().getCapacityMb());
        storage.put("maxTransferRateMbPerSecond", profile.getStorage().getMaxTransferRateMbPerSecond());
        values.put("storage", storage);
        values.put("costs", costs(profile.getCosts()));

        if (!profile.getCarbonWindows().isEmpty()) {
            List<Map<String, Object>> carbonWindows = new ArrayList<Map<String, Object>>();
            for (PlatformProfile.CarbonWindow window : profile.getCarbonWindows()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("startSeconds", Double.valueOf(window.getStartSeconds()));
                item.put("endSeconds", Double.valueOf(window.getEndSeconds()));
                item.put("greenWatts", Double.valueOf(window.getGreenWatts()));
                carbonWindows.add(item);
            }
            values.put("carbonWindows", carbonWindows);
        }
        values.put(
                "networkTopology", networkTopology(profile.getNetworkTopology(), profile.getSourceStorage() != null));
        if (profile.getSourceStorage() != null) {
            org.workflowsim.data.v2.DataflowStorageSpec spec = profile.getSourceStorage();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("attachmentHostId", spec.getAttachmentHostId());
            source.put("readBandwidthMbPerSecond", spec.getReadBandwidthMbPerSecond());
            source.put("writeBandwidthMbPerSecond", spec.getWriteBandwidthMbPerSecond());
            source.put("networkBandwidthMbPerSecond", spec.getNetworkBandwidthMbPerSecond());
            values.put("sourceStorage", source);
        }
        return values;
    }

    private static Map<String, Object> networkTopology(
            org.workflowsim.network.NetworkTopologySpec spec, boolean boundedStore) {
        if (spec == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("kind", spec.getKind().name());
        values.put("k", spec.getK());
        values.put("linkBandwidthMbPerSecond", spec.getLinkBandwidthMbPerSecond());
        values.put("coreSwitchCount", spec.getCoreSwitchCount());
        values.put(
                "hostEdgePlacements",
                spec.getHostEdgePlacements() == null
                        ? null
                        : new java.util.TreeMap<Integer, Integer>(spec.getHostEdgePlacements()));
        values.put("defaultPlacementPolicy", "HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES");
        values.put("routingPolicy", "DETERMINISTIC_AL_FARES_FAT_TREE_V1");
        values.put("linkDirectionality", "INDEPENDENT_DIRECTED_LINKS");
        values.put(
                "externalSourceRouting",
                boundedStore ? "BOUNDED_STORAGE_HOST_ATTACHMENT_V3" : "BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY");
        return values;
    }

    private static Map<String, Object> costs(PlatformProfile.CostSpec value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cpuPerSecond", value.getCpuPerSecond());
        result.put("memory", value.getMemory());
        result.put("storage", value.getStorage());
        result.put("bandwidth", value.getBandwidth());
        return result;
    }

    private static List<Map<String, Object>> inputs(SimulationReport report) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < report.getInputs().size(); index++) {
            SimulationReport.InputArtifact artifact = report.getInputs().get(index);
            WorkflowInputReport inputReport = report.getInputReports().get(index);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", artifact.getPath());
            value.put("sha256", artifact.getSha256());
            value.put("sizeBytes", artifact.getSizeBytes());
            value.put("format", inputReport.getFormat().name());
            value.put("declaredVersion", inputReport.getDeclaredVersion());
            value.put("taskCount", inputReport.getTaskCount());
            List<Map<String, Object>> normalizations = new ArrayList<>();
            for (WorkflowInputReport.Normalization normalization : inputReport.getNormalizations()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", normalization.getKind().name());
                item.put("subject", normalization.getSubject());
                item.put("originalValue", normalization.getOriginalValue());
                item.put("normalizedValue", normalization.getNormalizedValue());
                normalizations.add(item);
            }
            value.put("normalizations", normalizations);
            values.add(value);
        }
        return values;
    }

    private static Map<String, Object> result(SimulationReport report) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("makespan", report.getMakespan());
        values.put("simulationEndSeconds", report.getSimulationEndSeconds());
        values.put("logicalTaskCompletionStatus", report.getLogicalTaskCompletionStatus());
        values.put("logicalTaskCompletionSeconds", report.getLogicalTaskCompletionSeconds());
        values.put("terminalLifecycleTailSeconds", report.getTerminalLifecycleTailSeconds());
        values.put("workflowCompletedSuccessfully", report.isWorkflowCompletedSuccessfully());
        values.put("totalJobs", report.getTotalJobs());
        values.put("successfulJobs", report.getSuccessfulJobs());
        values.put("failedJobs", report.getFailedJobs());
        values.put("vmSummaries", report.getVmSummaries().values());
        values.put("jobs", jobOutcomeSerializationView(report));
        values.put("tasks", report.getTasks());
        values.put("actualVmHostAssignments", report.getActualVmHostAssignments());
        values.put("workflowOutcomes", report.getWorkflowOutcomes());
        values.put("sharedStorageDagPlanTrace", report.getSharedStorageDagPlanTrace());
        return values;
    }

    private static Object jobOutcomeSerializationView(SimulationReport report) {
        boolean anyTierAssignment = false;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getTierMips() != null) {
                anyTierAssignment = true;
                break;
            }
        }
        if (!anyTierAssignment) {
            return report.getJobs();
        }

        com.google.gson.Gson reflection = new com.google.gson.Gson();
        List<Object> view = new ArrayList<Object>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            com.google.gson.JsonObject item = reflection.toJsonTree(job).getAsJsonObject();
            if (job.getTierMips() != null) {
                item.addProperty("tierMips", job.getTierMips());
            }
            view.add(item);
        }
        return view;
    }

    private static Map<String, Object> eventSummary(SimulationReport report) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schema", "workflowsim-simulation-events-v1");
        values.put("eventCount", report.getEvents().size());
        if (!report.getEvents().isEmpty()) {
            values.put("firstSequence", report.getEvents().get(0).getSequence());
            values.put(
                    "lastSequence",
                    report.getEvents().get(report.getEvents().size() - 1).getSequence());

            values.put("revisionContract", EVENTS_REVISION_CONTRACT);
        }
        return values;
    }
}
