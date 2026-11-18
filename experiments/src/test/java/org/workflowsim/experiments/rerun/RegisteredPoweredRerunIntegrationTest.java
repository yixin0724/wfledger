package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class RegisteredPoweredRerunIntegrationTest {

    @TempDir
    Path directory;

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
    void registeredPoweredAlgorithmRerunsIdenticallyEndToEnd() throws Exception {

        SchedulingAlgorithmProvider provider =
                org.workflowsim.experiment.registry.AlgorithmRegistry.find("ENERGY_LPT_FIRST");
        assertEquals(
                org.workflowsim.experiments.registry.EnergyLptFirstSchedulerProvider.class,
                provider.getClass(),
                "the SPI must surface the E5 provider in this classpath");

        Path run = directory.resolve("study/runs/original");
        Path input = run.getParent().getParent().resolve("inputs/bag.dax");
        Files.createDirectories(input.getParent());
        Files.write(input, dax().getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(input.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .deadline(400)
                .registeredSchedulingAlgorithm(provider)
                .build();
        PlatformProfile platform = poweredPlatform();
        SimulationReport report = new SimulationRunner().run(config, platform);
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        ExperimentArtifactWriter.write(report, run, "result");

        Path output = directory.resolve("study/runs/rerun");
        RerunReport result = RerunDiffExecutor.execute(run, output);
        java.nio.file.Files.write(
                directory.resolve("verdict.json"), result.toJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(RerunVerdict.IDENTICAL_CORE, result.getVerdict(), result.toJson());

        com.google.gson.JsonObject original = com.google.gson.JsonParser.parseString(
                        new String(Files.readAllBytes(run.resolve("result.manifest.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        com.google.gson.JsonObject repeat = com.google.gson.JsonParser.parseString(new String(
                        Files.readAllBytes(output.resolve("rerun/result.manifest.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(
                original.getAsJsonObject("energyMetrics").get("totalEnergyJoules"),
                repeat.getAsJsonObject("energyMetrics").get("totalEnergyJoules"),
                "energy must replay bit-exactly across the registered-algorithm rerun");

        assertEquals(
                original.getAsJsonObject("configuration").get("registeredSchedulingAlgorithm"),
                repeat.getAsJsonObject("configuration").get("registeredSchedulingAlgorithm"));
        assertEquals(
                true,
                repeat.getAsJsonObject("metrics")
                        .get("deadlineObservationEnabled")
                        .getAsBoolean(),
                "the deadline axis must be observed in metrics alongside the registered label");
        assertEquals(
                original.getAsJsonObject("metrics").get("deadlineMet"),
                repeat.getAsJsonObject("metrics").get("deadlineMet"),
                "deadline verdicts must replay identically");
    }

    private static String dax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"3\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"40\"/>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"95\"/>\n"
                + "  <job id=\"c\" name=\"c\" runtime=\"35\"/>\n"
                + "</adag>\n";
    }

    private static PlatformProfile poweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("r7-powered");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(100 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1000L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            250.0,
                            400.0))
                    .pinVmToHost(ids[i], 100 + i);
        }
        return builder.build();
    }
}
