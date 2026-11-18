package org.workflowsim.experiments.fattree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class FatTreeCampaignDagCompatibilityIntegrationTest {

    private static final List<String> EXPECTED_PASSED = Arrays.asList(
            "heft-paper-example",
            "epigenomics-n24",
            "epigenomics-n46",
            "epigenomics-n100",
            "cybershake-n30",
            "cybershake-n50",
            "cybershake-n100",
            "inspiral-n30",
            "inspiral-n50",
            "inspiral-n100");

    private static final List<String> EXPECTED_REJECTED =
            Arrays.asList("montage-n25", "montage-n50", "montage-n100", "sipht-n30", "sipht-n60", "sipht-n100");

    @Test
    void campaignDagSetIsExactlyTheCompatibleSet() throws Exception {
        List<FatTreeSchedulingCampaignExecutor.DagScenario> dags =
                FatTreeSchedulingCampaignExecutor.discoverCoreDags(datasetsRoot());
        List<String> ids = new ArrayList<String>();
        for (FatTreeSchedulingCampaignExecutor.DagScenario dag : dags) {
            ids.add(dag.id);
        }
        assertEquals(EXPECTED_PASSED, ids, "The campaign DAG set must equal the compatibility passing set");
    }

    @Test
    void localCommFamilyClassificationMatchesTheProbedPartition() throws Exception {
        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        List<String> passed = new ArrayList<String>();
        List<String> rejected = new ArrayList<String>();
        for (CandidateDag candidate : allCandidates()) {
            SimulationConfig config = SimulationConfig.builder(candidate.daxPath, 3)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                    .randomSeed(91L)
                    .build();
            try {
                runner.run(config, platform());
                passed.add(candidate.id);
            } catch (Exception exception) {
                rejected.add(candidate.id);
                assertTrue(
                        rootCauseMessage(exception).contains("inconsistent size declarations"),
                        candidate.id + ": the rejection reason must be inconsistent file sizes, actual "
                                + rootCauseMessage(exception));
            }
        }
        assertEquals(EXPECTED_PASSED, passed, "The passing set must match element for element");
        assertEquals(EXPECTED_REJECTED, rejected, "The rejected set must match element for element");
    }

    private static List<CandidateDag> allCandidates() throws IOException {
        String[][] families = {
            {"montage", "n25", "n50", "n100"},
            {"epigenomics", "n24", "n46", "n100"},
            {"cybershake", "n30", "n50", "n100"},
            {"inspiral", "n30", "n50", "n100"},
            {"sipht", "n30", "n60", "n100"}
        };
        List<CandidateDag> candidates = new ArrayList<CandidateDag>();
        candidates.add(new CandidateDag(
                "heft-paper-example",
                datasetsRoot().resolve("dax/heft/heft-paper-example.dax").toString()));
        for (String[] family : families) {
            for (int scaleIndex = 1; scaleIndex < family.length; scaleIndex++) {
                String familyName = family[0];
                String scale = family[scaleIndex];
                java.nio.file.Path dir =
                        datasetsRoot().resolve("dax").resolve(familyName).resolve(scale);
                java.nio.file.Path dax = null;
                try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                        java.nio.file.Files.newDirectoryStream(dir, "*.dax")) {
                    for (java.nio.file.Path entry : stream) {
                        dax = entry;
                    }
                }
                candidates.add(new CandidateDag(familyName + "-" + scale, dax.toString()));
            }
        }
        return candidates;
    }

    private static java.nio.file.Path datasetsRoot() {
        return Paths.get("..", "datasets").toAbsolutePath().normalize();
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private static PlatformProfile platform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("dag-compat-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static final class CandidateDag {
        final String id;
        final String daxPath;

        CandidateDag(String id, String daxPath) {
            this.id = id;
            this.daxPath = daxPath;
        }
    }
}
