package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class EnergyRerunCompatibilityIntegrationTest {

    @TempDir
    Path directory;

    private static boolean priorLogDisabled;
    private int serial;

    @BeforeAll
    static void quiet() {
        priorLogDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterAll
    static void restore() {
        Log.setDisabled(priorLogDisabled);
    }

    @Test
    void poweredEvidenceRerunIsIdenticalCore() throws Exception {
        Path run = fixture(true), output = directory.resolve("powered-repeat");
        RerunReport result = RerunDiffExecutor.execute(run, output);
        assertEquals(RerunVerdict.IDENTICAL_CORE, result.getVerdict(), result.toJson());

        JsonObject original = read(run.resolve("result.manifest.json")).getAsJsonObject("energyMetrics");
        JsonObject repeat = read(output.resolve("rerun/result.manifest.json")).getAsJsonObject("energyMetrics");
        assertEquals(original.get("totalEnergyJoules"), repeat.get("totalEnergyJoules"));
        assertEquals(original.get("peakPowerWatts"), repeat.get("peakPowerWatts"));
    }

    @Test
    void legacyEvidenceWithoutEnergyKeysRerunsIdenticalCore() throws Exception {
        Path run = fixture(false), output = directory.resolve("legacy-repeat");
        stripEnergyDimension(run);
        RerunReport result = RerunDiffExecutor.execute(run, output);
        assertEquals(RerunVerdict.IDENTICAL_CORE, result.getVerdict(), result.toJson());
    }

    @Test
    void nonzeroEnergyIsNeverTreatedAsAbsentEquivalent() throws Exception {

        Path original = fixture(true), rerun = fixture(true);
        stripEnergyDimension(rerun);
        RerunEvidence a = RerunEvidenceReader.read(original);
        RerunEvidence b = RerunEvidenceReader.read(rerun);
        boolean diverged = false;
        try {
            EvidenceCoreDiffer.DiffResult result = EvidenceCoreDiffer.compare(a, b);
            diverged = !result.isIdenticalCore();
        } catch (Exception failure) {
            diverged = true;
        }
        assertEquals(true, diverged, "nonzero original energy vs stripped rerun must stay core-divergent");
    }

    @Test
    void adversariallyCorruptedZeroSectionsStayDivergent() throws Exception {

        Path original = fixture(false), rerun = fixture(true);
        stripEnergyDimension(original);

        RerunEvidence a = RerunEvidenceReader.read(original);
        RerunEvidence b = RerunEvidenceReader.read(rerun);
        boolean diverged = false;
        try {
            EvidenceCoreDiffer.DiffResult result = EvidenceCoreDiffer.compare(a, b);
            diverged = !result.isIdenticalCore();
        } catch (Exception failure) {
            diverged = true;
        }
        assertEquals(true, diverged, "nonzero rerun energy vs absent original must stay divergent");
    }

    @Test
    void rebuilderRejectsStringTypedAndHalfDeclaredPower() throws Exception {

        Path run = fixture(false);
        Path manifest = run.resolve("result.manifest.json");
        JsonObject root = read(manifest);
        root.getAsJsonObject("platform")
                .getAsJsonArray("vms")
                .get(0)
                .getAsJsonObject()
                .addProperty("idlePowerWatts", "250");
        Files.write(manifest, root.toString().getBytes(StandardCharsets.UTF_8));
        RerunEvidence stringTyped = RerunEvidenceReader.read(run);

        RerunFailureException stringRejection = assertThrows(
                RerunFailureException.class,
                () -> ManifestConfigRebuilder.rebuild(stringTyped, RerunInputResolver.resolve(stringTyped)));
        assertTrue(
                stringRejection.getMessage().contains("idlePowerWatts"),
                "rejection must cite the field through the wrapper: " + stringRejection.getMessage());
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, stringRejection.getVerdict());

        Path run2 = fixture(false);
        Path manifest2 = run2.resolve("result.manifest.json");
        JsonObject root2 = read(manifest2);
        root2.getAsJsonObject("platform")
                .getAsJsonArray("vms")
                .get(0)
                .getAsJsonObject()
                .addProperty("idlePowerWatts", 250.0);
        Files.write(manifest2, root2.toString().getBytes(StandardCharsets.UTF_8));
        RerunEvidence halfDeclared = RerunEvidenceReader.read(run2);
        RerunFailureException halfRejection = assertThrows(
                RerunFailureException.class,
                () -> ManifestConfigRebuilder.rebuild(halfDeclared, RerunInputResolver.resolve(halfDeclared)));
        assertTrue(
                halfRejection.getMessage().contains("Invalid power fields"),
                "half-declaration must be rejected by VmSpec validation: " + halfRejection.getMessage());
    }

    @Test
    void jsonNullPowerFieldsReadAsUnpoweredLegacyForm() throws Exception {

        Path run = fixture(false), output = directory.resolve("null-power-repeat");
        Path manifest = run.resolve("result.manifest.json");
        JsonObject root = read(manifest);
        root.getAsJsonObject("platform")
                .getAsJsonArray("vms")
                .get(0)
                .getAsJsonObject()
                .add("idlePowerWatts", com.google.gson.JsonNull.INSTANCE);
        root.getAsJsonObject("platform")
                .getAsJsonArray("vms")
                .get(1)
                .getAsJsonObject()
                .add("maxPowerWatts", com.google.gson.JsonNull.INSTANCE);
        Files.write(manifest, root.toString().getBytes(StandardCharsets.UTF_8));
        RerunReport result = RerunDiffExecutor.execute(run, output);
        assertEquals(RerunVerdict.IDENTICAL_CORE, result.getVerdict(), result.toJson());
    }

    private Path fixture(boolean powered) throws Exception {
        Path run = directory.resolve("study-" + (serial++) + "/runs/original");
        Path input = run.getParent().getParent().resolve("inputs/flow.dax");
        Files.createDirectories(input.getParent());
        Files.write(
                input,
                ("<adag><job id=\"p\" runtime=\"1\"/><job id=\"c\" runtime=\"1\"/>"
                                + "<child ref=\"c\"><parent ref=\"p\"/></child></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(input.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("energy-rerun-fixture");
        int[] ids = {3, 9};
        for (int i = 0; i < 2; i++) {
            int host = 50 + i;
            PlatformProfile.VmSpec vm = powered
                    ? new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            250.0,
                            400.0)
                    : new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED);
            builder.addHost(new PlatformProfile.HostSpec(host, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(vm)
                    .pinVmToHost(ids[i], host);
        }
        SimulationReport report = new SimulationRunner().run(config, builder.build());
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        ExperimentArtifactWriter.write(report, run, "result");
        return run;
    }

    private static void stripEnergyDimension(Path run) throws Exception {
        Path manifest = run.resolve("result.manifest.json");
        JsonObject root = read(manifest);
        root.remove("energyMetrics");
        for (JsonElement element : root.getAsJsonObject("platform").getAsJsonArray("vms")) {
            JsonObject vm = element.getAsJsonObject();
            vm.remove("idlePowerWatts");
            vm.remove("maxPowerWatts");
        }
        Files.write(manifest, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject read(Path path) throws Exception {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }
}
