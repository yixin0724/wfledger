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

/**
 * BAT-R7 (2026-10-27, seventh review round) -- cross-seam combination chain: registered-label
 * algorithm x powered platform x rerun path. No earlier review round exercised the three in
 * series (R1-R6 each guarded its own seam).
 *
 * <p>Key question: the registration interface's powerModelRequired validation, the manifest's
 * power serialization, EvidenceCoreDiffer's energy-absence equivalence, and
 * ManifestConfigRebuilder's power forwarding -- each seam being green does not mean the whole
 * chain is green. This test runs one powered run with the registered ENERGY_LPT_FIRST provider
 * (hosted in the experiments test domain, visible through SPI), then reruns it through the
 * whole-chain RerunDiffExecutor: the verdict must be IDENTICAL_CORE and the rerun-side energy
 * consumption must equal the original bit for bit.</p>
 */
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
        // 1. Locate the provider through the registration surface (ServiceLoader) -- constructing
        // it directly with new would bypass the interface.
        SchedulingAlgorithmProvider provider =
                org.workflowsim.experiment.registry.AlgorithmRegistry.find("ENERGY_LPT_FIRST");
        assertEquals(
                org.workflowsim.experiments.registry.EnergyLptFirstSchedulerProvider.class,
                provider.getClass(),
                "the SPI must surface the E5 provider in this classpath");

        // 2. Original run: registered algorithm x powered platform.
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
                .deadline(400) // BAT-R9: deadline joins the registration chain (an axis cut when R8 set its goal)
                .registeredSchedulingAlgorithm(provider)
                .build();
        PlatformProfile platform = poweredPlatform();
        SimulationReport report = new SimulationRunner().run(config, platform);
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        ExperimentArtifactWriter.write(report, run, "result");

        // 3. Whole-chain rerun: rebuild (read the registered label + power) -> rerun -> compare
        // core quantities.
        Path output = directory.resolve("study/runs/rerun");
        RerunReport result = RerunDiffExecutor.execute(run, output);
        java.nio.file.Files.write(
                directory.resolve("verdict.json"), result.toJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(RerunVerdict.IDENTICAL_CORE, result.getVerdict(), result.toJson());

        // 4. Bit-exact energy replay: the rerun side's total energy equals the original side
        // exactly (the conservation law is guaranteed by the same engine).
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
        // The registered label must appear verbatim in both manifests (equivalence on the INVALID
        // enum string is not enough).
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
