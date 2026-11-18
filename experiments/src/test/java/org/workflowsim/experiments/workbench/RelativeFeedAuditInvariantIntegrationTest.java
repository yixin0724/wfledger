package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class RelativeFeedAuditInvariantIntegrationTest {

    static final Path PROBE_DIR = Paths.get("target", "relative-feed-probe");

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void relativeWorkflowFeedWritesAnAuditableManifest() throws Exception {
        Files.createDirectories(PROBE_DIR);

        Path absoluteInput = PROBE_DIR.resolve("relative-feed.dax");
        Files.write(absoluteInput, dax().getBytes(StandardCharsets.UTF_8));
        String relativeFeed = tempDir.relativize(tempDir).toString().isEmpty()
                ? "target/relative-feed-probe/relative-feed.dax"
                : "target/relative-feed-probe/relative-feed.dax";

        Path cwdCheck = Paths.get(relativeFeed);
        if (!Files.isRegularFile(cwdCheck)) {

            org.junit.jupiter.api.Assumptions.assumeTrue(false, "CWD anchor mismatch");
        }
        SimulationConfig config = SimulationConfig.builder(relativeFeed, 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .build();
        SimulationReport report = new SimulationRunner().run(config, rosterPlatform());
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, PROBE_DIR, "result");
        com.google.gson.JsonObject manifest = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                        java.nio.file.Files.newInputStream(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<String> declared = new ArrayList<>();
        for (com.google.gson.JsonElement element :
                manifest.getAsJsonObject("configuration").getAsJsonArray("workflowPaths")) {
            declared.add(element.getAsString());
        }
        List<String> recorded = new ArrayList<>();
        for (com.google.gson.JsonElement element : manifest.getAsJsonArray("inputs")) {
            recorded.add(element.getAsJsonObject().get("path").getAsString());
        }
        assertEquals(
                declared,
                recorded,
                "configuration.workflowPaths must record the same resolved "
                        + "paths as inputs[].path (ordered-input-paths invariant), even for relative feeds");
    }

    private static String dax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"2\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"30\"/>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"45\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "</adag>\n";
    }

    private static PlatformProfile rosterPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("r9-relative-feed");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(400 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1000L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                    .pinVmToHost(ids[i], 400 + i);
        }
        return builder.build();
    }
}
