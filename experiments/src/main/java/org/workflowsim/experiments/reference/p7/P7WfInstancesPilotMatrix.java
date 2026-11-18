package org.workflowsim.experiments.reference.p7;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class P7WfInstancesPilotMatrix {

    public static final long ROOT_SEED = 20260902L;
    public static final int VM_COUNT = 4;
    public static final double RUNTIME_REFERENCE_MIPS = 1000.0;
    public static final double RUNTIME_SCALE = 1.0;

    private static final List<Scenario> SCENARIOS = Collections.unmodifiableList(Arrays.asList(
            new Scenario(
                    "makeflow-blast-small-001",
                    "Makeflow",
                    "BLAST",
                    43,
                    "wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json",
                    "5e132ac7f63096dec62173da1c7512554f9ddb08dc420f2005af277a04b8e845"),
            new Scenario(
                    "nextflow-bacass-001",
                    "Nextflow",
                    "Bacass",
                    11,
                    "wfinstances/v1.5/nextflow/bacass-dirt02-001.json",
                    "4cbab2c46e2d7c6094701d5a9d03bc4675d59bbb96effc2ae3c255c5a4fb2999"),
            new Scenario(
                    "pegasus-srasearch-10a-001",
                    "Pegasus",
                    "SRaSearch",
                    22,
                    "wfinstances/v1.5/pegasus/srasearch/srasearch-chameleon-10a-001.json",
                    "c2b5d73841750b5af68e5ff65f5f2d04b51a4b6b18086e7a5b1dfb72317fd4b6"),
            new Scenario(
                    "pegasus-montage-2mass-005d-001",
                    "Pegasus",
                    "Montage",
                    58,
                    "wfinstances/v1.5/pegasus/montage/montage-chameleon-2mass-005d-001.json",
                    "5795e0ab9e13bb7d50d046796bcbc8ec0a884eba0512a95222bbd557bc6d0b65")));

    private P7WfInstancesPilotMatrix() {}

    public static List<Scenario> scenarios() {
        return SCENARIOS;
    }

    public static SimulationConfig baselineConfig(
            Scenario scenario, Path datasetRoot, Parameters.SchedulingAlgorithm algorithm) {
        if (scenario == null || datasetRoot == null || algorithm == null) {
            throw new IllegalArgumentException("Scenario, dataset root, and scheduling algorithm are required");
        }
        return SimulationConfig.builder(scenario.resolve(datasetRoot).toString(), VM_COUNT)
                .schedulingAlgorithm(algorithm)
                .planningAlgorithm(Parameters.PlanningAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .randomSeed(ROOT_SEED)
                .runtimeReferenceMips(RUNTIME_REFERENCE_MIPS)
                .runtimeScale(RUNTIME_SCALE)
                .overheadModel(OverheadModelConfig.none())
                .clusteringParameters(new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null))
                .failureModel(FailureModelConfig.disabled())
                .costModel(Parameters.CostModel.DATACENTER)
                .build();
    }

    public static PlatformProfile baselinePlatform() {
        return PlatformProfiles.homogeneousLocal("p7-wfinstances-h0", VM_COUNT);
    }

    public static final class Scenario {
        private final String id;
        private final String system;
        private final String workflowFamily;
        private final int expectedTaskCount;
        private final String relativePath;
        private final String expectedSha256;

        private Scenario(
                String id,
                String system,
                String workflowFamily,
                int expectedTaskCount,
                String relativePath,
                String expectedSha256) {
            this.id = id;
            this.system = system;
            this.workflowFamily = workflowFamily;
            this.expectedTaskCount = expectedTaskCount;
            this.relativePath = relativePath;
            this.expectedSha256 = expectedSha256;
        }

        public String getId() {
            return id;
        }

        public String getSystem() {
            return system;
        }

        public String getWorkflowFamily() {
            return workflowFamily;
        }

        public int getExpectedTaskCount() {
            return expectedTaskCount;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getExpectedSha256() {
            return expectedSha256;
        }

        public Path resolve(Path datasetRoot) {
            return ReferenceDatasetRoot.resolveFile(datasetRoot, relativePath);
        }
    }
}
