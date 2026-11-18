package org.workflowsim.experiments.reference.p7;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class P7BaselineMatrix {

    public static final long ROOT_SEED = 20260901L;
    public static final double RUNTIME_REFERENCE_MIPS = 1000.0;
    public static final double RUNTIME_SCALE = 1.0;

    public static final double CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS = 0.1;

    public static final int SMALL_VM_COUNT = 10;
    public static final int LARGE_VM_COUNT = 50;

    private static final List<Parameters.SchedulingAlgorithm> SCHEDULING_ALGORITHMS =
            Collections.unmodifiableList(Arrays.asList(
                    Parameters.SchedulingAlgorithm.FCFS,
                    Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MCT,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN));

    private static final List<Workload> WORKLOADS = Collections.unmodifiableList(Arrays.asList(
            new Workload(
                    "epigenomics-n100",
                    "Epigenomics",
                    100,
                    SMALL_VM_COUNT,
                    "dax/epigenomics/n100/Epigenomics_100.dax",
                    "374521746417b18133682de21b654b84c32acde6332462c0ce916c4ba12c7f36"),
            new Workload(
                    "epigenomics-n997",
                    "Epigenomics",
                    997,
                    LARGE_VM_COUNT,
                    "dax/epigenomics/n997/Epigenomics_997.dax",
                    "2ed853db24126750a1bf427b5731d5fb18b6d31baf3d2088c14b310478bed628")));

    private static final Set<String> BASELINE_CELL_KEYS = buildBaselineCellKeys();

    private P7BaselineMatrix() {}

    public static List<Parameters.SchedulingAlgorithm> schedulingAlgorithms() {
        return SCHEDULING_ALGORITHMS;
    }

    static Set<String> baselineCellKeys() {
        return BASELINE_CELL_KEYS;
    }

    private static Set<String> buildBaselineCellKeys() {
        Set<String> result = new LinkedHashSet<String>();
        for (Workload workload : WORKLOADS) {
            for (PlatformVariant platformVariant : PlatformVariant.values()) {
                String scenarioId = workload.id + "-" + platformVariant.name().toLowerCase(Locale.ROOT);
                for (Parameters.SchedulingAlgorithm algorithm : SCHEDULING_ALGORITHMS) {
                    result.add(cellKey(scenarioId, algorithm.name()));
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static boolean isBaselineScenarioId(String scenarioId) {
        if (scenarioId == null) {
            return false;
        }
        for (String cell : baselineCellKeys()) {
            int separator = cell.indexOf('\u0000');
            if (scenarioId.equals(cell.substring(0, separator))) {
                return true;
            }
        }
        return false;
    }

    static boolean isBaselineAlgorithmName(String algorithmName) {
        if (algorithmName == null) {
            return false;
        }
        for (Parameters.SchedulingAlgorithm algorithm : SCHEDULING_ALGORITHMS) {
            if (algorithm.name().equals(algorithmName)) {
                return true;
            }
        }
        return false;
    }

    static String cellKey(String scenarioId, String algorithmName) {
        return scenarioId + "\u0000" + algorithmName;
    }

    static Scenario scenarioById(String scenarioId) {
        if (scenarioId == null) {
            return null;
        }
        for (Workload workload : WORKLOADS) {
            for (PlatformVariant platformVariant : PlatformVariant.values()) {
                Scenario candidate = new Scenario(workload, platformVariant);
                if (scenarioId.equals(candidate.getId())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static List<Scenario> scenarios(Path datasetRoot) {
        ReferenceDatasetRoot.require(datasetRoot);
        List<Scenario> scenarios = new ArrayList<>();
        for (Workload workload : WORKLOADS) {
            scenarios.add(new Scenario(workload, PlatformVariant.H0_HOMOGENEOUS));
            scenarios.add(new Scenario(workload, PlatformVariant.H1_HETEROGENEOUS));
        }
        return Collections.unmodifiableList(scenarios);
    }

    public static SimulationConfig baselineConfig(
            Scenario scenario, Parameters.SchedulingAlgorithm schedulingAlgorithm, Path datasetRoot) {
        if (scenario == null || schedulingAlgorithm == null || datasetRoot == null) {
            throw new IllegalArgumentException("Scenario, scheduling algorithm, and dataset root are required");
        }
        if (!SCHEDULING_ALGORITHMS.contains(schedulingAlgorithm)) {
            throw new IllegalArgumentException(
                    "Scheduling algorithm is outside the P7 baseline: " + schedulingAlgorithm);
        }
        return SimulationConfig.builder(scenario.resolveWorkflow(datasetRoot).toString(), scenario.getVmCount())
                .schedulingAlgorithm(schedulingAlgorithm)
                .planningAlgorithm(Parameters.PlanningAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .randomSeed(ROOT_SEED)
                .runtimeReferenceMips(RUNTIME_REFERENCE_MIPS)
                .runtimeScale(RUNTIME_SCALE)
                .cloudSimMinEventIntervalSeconds(CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS)
                .overheadModel(OverheadModelConfig.none())
                .clusteringParameters(new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null))
                .failureModel(FailureModelConfig.disabled())
                .costModel(Parameters.CostModel.DATACENTER)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .build();
    }

    public static PlatformProfile baselinePlatform(Scenario scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario is required");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("p7-" + scenario.getId());
        for (int id = 0; id < scenario.getVmCount(); id++) {
            double vmMips = scenario.getPlatformVariant() == PlatformVariant.H0_HOMOGENEOUS
                    ? 1000.0
                    : (id % 2 == 0 ? 500.0 : 1500.0);
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, vmMips, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    public enum PlatformVariant {
        H0_HOMOGENEOUS,
        H1_HETEROGENEOUS
    }

    public static final class Scenario {

        private final Workload workload;
        private final PlatformVariant platformVariant;

        private Scenario(Workload workload, PlatformVariant platformVariant) {
            this.workload = workload;
            this.platformVariant = platformVariant;
        }

        public String getId() {
            return workload.id + "-" + platformVariant.name().toLowerCase(Locale.ROOT);
        }

        public String getWorkflowFamily() {
            return workload.family;
        }

        public int getExpectedTaskCount() {
            return workload.expectedTaskCount;
        }

        public int getVmCount() {
            return workload.vmCount;
        }

        public String getRelativeWorkflowPath() {
            return workload.relativeWorkflowPath;
        }

        public String getExpectedWorkflowSha256() {
            return workload.expectedWorkflowSha256;
        }

        public PlatformVariant getPlatformVariant() {
            return platformVariant;
        }

        public Path resolveWorkflow(Path datasetRoot) {
            return ReferenceDatasetRoot.resolveFile(datasetRoot, workload.relativeWorkflowPath);
        }
    }

    private static final class Workload {

        private final String id;
        private final String family;
        private final int expectedTaskCount;
        private final int vmCount;
        private final String relativeWorkflowPath;
        private final String expectedWorkflowSha256;

        private Workload(
                String id,
                String family,
                int expectedTaskCount,
                int vmCount,
                String relativeWorkflowPath,
                String expectedWorkflowSha256) {
            this.id = id;
            this.family = family;
            this.expectedTaskCount = expectedTaskCount;
            this.vmCount = vmCount;
            this.relativeWorkflowPath = relativeWorkflowPath;
            this.expectedWorkflowSha256 = expectedWorkflowSha256;
        }
    }
}
