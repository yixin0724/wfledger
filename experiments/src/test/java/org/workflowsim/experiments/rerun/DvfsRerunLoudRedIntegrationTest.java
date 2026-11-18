package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class DvfsRerunLoudRedIntegrationTest {

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
    void triDimensionalDvfsEvidenceRerunsIdenticallyOnceTiersRoundTrip() throws Exception {
        Path study = directory.resolve("tri");
        Path run = runAndWriteEvidence(study, "runs/tri", tieredCarbonPlatform(), FrequencyPolicy.DEFAULT);

        RerunReport report = assertDoesNotThrow(
                () -> RerunDiffExecutor.execute(run, study.resolve("out")),
                "the tier rebuild must not turn a legitimate rerun into a usage-class exception");

        assertEquals(
                RerunVerdict.IDENTICAL_CORE,
                report.getVerdict(),
                "a tiered+carbon bundle must rerun green once the tier table round-trips: " + report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");
        assertTrue(
                report.getCoreDivergences().isEmpty(),
                "no core divergence may survive: " + pointers(report.getCoreDivergences()));
    }

    @Test
    void tieredEvidenceRerunsIdenticallyAndTheRebuiltSideCarriesTheTierTable() throws Exception {
        Path study = directory.resolve("tiered");
        Path run = runAndWriteEvidence(study, "runs/tiered", tieredPlatform(), FrequencyPolicy.MIN_POWER);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");

        String rerunManifest =
                new String(Files.readAllBytes(study.resolve("out/rerun/result.manifest.json")), StandardCharsets.UTF_8);
        assertTrue(rerunManifest.contains("frequencyTiers"), "the rebuilt side must carry the tier table");
        assertTrue(rerunManifest.contains("tierMips"), "the rebuilt side must carry per-job tier evidence");
        assertTrue(rerunManifest.contains("MIN_POWER"), "the rebuilt side must carry the frequency policy");
    }

    @Test
    void malformedTierTableInTheRecordedManifestIsStillRejectedLoudly() throws Exception {
        Path study = directory.resolve("malformed");
        Path run = runAndWriteEvidence(study, "runs/malformed", twoTierPlatform(), FrequencyPolicy.DEFAULT);
        scrambleTierOrder(run);

        RerunReport report = assertDoesNotThrow(() -> RerunDiffExecutor.execute(run, study.resolve("out")));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, report.getVerdict(), report.toJson());
        assertEquals(4, report.getExitCode(), "contract exit code for a rebuild refusal");
        assertTrue(Files.exists(study.resolve("out/rerun-report.json")), "the refusal report must be written");
        assertFalse(Files.exists(study.resolve("out/rerun")), "a refused rerun must not fabricate a bundle");
        String reason = report.toJson();
        assertTrue(
                reason.contains("frequency") || reason.contains("mips") || reason.contains("descending"),
                "the refusal must cite the tier table as the cause: " + reason);
    }

    @Test
    void v1FlatPowerEvidenceStillRerunsIdentically() throws Exception {
        Path study = directory.resolve("v1");
        Path run = runAndWriteEvidence(study, "runs/v1", plainPoweredPlatform(), null);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");
    }

    @Test
    void preD4ShapedV1EvidenceRerunsIdentically() throws Exception {
        Path study = directory.resolve("legacy");
        Path run = runAndWriteEvidence(study, "runs/legacy", plainPoweredPlatform(), null);
        stripFrequencyPolicyKey(run);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(
                RerunVerdict.IDENTICAL_CORE,
                report.getVerdict(),
                "legacy pre-D4 v1 evidence must rerun green; any core divergence here is a fabrication"
                        + " of the D4 unconditional frequencyPolicy emission: " + report.toJson());
        assertEquals(0, report.getExitCode());
    }

    @Test
    void tieredBudgetCarbonEvidenceRerunsIdentically() throws Exception {
        Path study = directory.resolve("tiered-budget");
        Path run = runAndWriteEvidence(study, "runs/tiered-budget", tieredBudgetPlatform(), FrequencyPolicy.MIN_POWER);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");

        String rerunManifest =
                new String(Files.readAllBytes(study.resolve("out/rerun/result.manifest.json")), StandardCharsets.UTF_8);
        assertTrue(rerunManifest.contains("carbonWindows"), "the rebuilt side must carry the window table");
        assertTrue(rerunManifest.contains("frequencyTiers"), "the rebuilt side must carry the tier table");
        assertTrue(rerunManifest.contains("carbonMetrics"), "the rebuilt run must produce budget carbon again");
    }

    @Test
    void manifestDeclaringBothTierTableAndFlatPowerIsRejected() throws Exception {
        assertRejectedAfter(run -> addFlatPowerBesidesTiers(run), "one source of truth", "BOTH frequencyTiers");
    }

    @Test
    void tierEntryWithoutMipsIsRejected() throws Exception {
        assertRejectedAfter(run -> dropFirstTierMips(run), "mips", "missing");
    }

    @Test
    void nonStringFrequencyPolicyIsAReportedRejectionNotAUsageExit() throws Exception {
        assertRejectedAfter(run -> setRawFrequencyPolicy(run, 42), "frequencyPolicy must be a JSON string");
    }

    @Test
    void unknownFrequencyPolicyNameIsRejected() throws Exception {
        assertRejectedAfter(run -> setFrequencyPolicy(run, "TURBO_MODE"), "frequencyPolicy", "TURBO_MODE");
    }

    @Test
    void nonDefaultPolicyWithoutAnyTierTableIsRejected() throws Exception {
        assertRejectedAfter(
                run -> {
                    stripTierTable(run);
                    setFrequencyPolicy(run, "MIN_POWER");
                },
                "MIN_POWER",
                "requires at least one VM");
    }

    private interface Tamper {
        void apply(Path runDirectory) throws Exception;
    }

    private void assertRejectedAfter(Tamper tamper, String... expectedFragments) throws Exception {
        Path study = directory.resolve("tamper-" + System.nanoTime());
        Path run = runAndWriteEvidence(study, "runs/tamper", twoTierPlatform(), FrequencyPolicy.MIN_POWER);
        tamper.apply(run);

        RerunReport report = assertDoesNotThrow(() -> RerunDiffExecutor.execute(run, study.resolve("out")));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, report.getVerdict(), report.toJson());
        assertEquals(4, report.getExitCode(), "rebuild refusals keep exit 4");
        String reason = report.toJson();
        for (String fragment : expectedFragments) {
            assertTrue(reason.contains(fragment), "the refusal must cite '" + fragment + "': " + reason);
        }
    }

    private static JsonObject readManifest(Path runDirectory) throws Exception {
        Path manifest = runDirectory.resolve("result.manifest.json");
        return JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void writeManifest(Path runDirectory, JsonObject doc) throws Exception {
        Files.write(runDirectory.resolve("result.manifest.json"), doc.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject firstVm(JsonObject doc) {
        return doc.getAsJsonObject("platform").getAsJsonArray("vms").get(0).getAsJsonObject();
    }

    private static void addFlatPowerBesidesTiers(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        JsonObject vm = firstVm(doc);
        vm.addProperty("idlePowerWatts", 250.0);
        vm.addProperty("maxPowerWatts", 400.0);
        writeManifest(runDirectory, doc);
    }

    private static void dropFirstTierMips(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        com.google.gson.JsonArray tiers = firstVm(doc).getAsJsonArray("frequencyTiers");
        tiers.get(0).getAsJsonObject().remove("mips");
        writeManifest(runDirectory, doc);
    }

    private static void setRawFrequencyPolicy(Path runDirectory, int value) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        doc.getAsJsonObject("configuration").addProperty("frequencyPolicy", value);
        writeManifest(runDirectory, doc);
    }

    private static void setFrequencyPolicy(Path runDirectory, String policy) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        doc.getAsJsonObject("configuration").addProperty("frequencyPolicy", policy);
        writeManifest(runDirectory, doc);
    }

    private static void stripTierTable(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        for (com.google.gson.JsonElement element :
                doc.getAsJsonObject("platform").getAsJsonArray("vms")) {
            element.getAsJsonObject().remove("frequencyTiers");
        }
        writeManifest(runDirectory, doc);
    }

    private static void scrambleTierOrder(Path runDirectory) throws Exception {
        Path manifest = runDirectory.resolve("result.manifest.json");
        JsonObject doc = JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject vm =
                doc.getAsJsonObject("platform").getAsJsonArray("vms").get(0).getAsJsonObject();
        com.google.gson.JsonArray tiers = vm.getAsJsonArray("frequencyTiers");
        com.google.gson.JsonArray reversed = new com.google.gson.JsonArray();
        for (int i = tiers.size() - 1; i >= 0; i--) {
            reversed.add(tiers.get(i));
        }
        vm.add("frequencyTiers", reversed);
        Files.write(manifest, doc.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void stripFrequencyPolicyKey(Path runDirectory) throws Exception {
        Path manifest = runDirectory.resolve("result.manifest.json");
        JsonObject doc = JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject configuration = doc.getAsJsonObject("configuration");

        configuration.remove("frequencyPolicy");
        assertTrue(
                !configuration.has("frequencyPolicy"),
                "the pre-D4 frozen shape (no frequencyPolicy member) must be constructible");
        Files.write(manifest, doc.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String pointers(List<RerunReport.DivergenceEntry> divergences) {
        List<String> values = new ArrayList<String>();
        for (RerunReport.DivergenceEntry divergence : divergences) {
            values.add(divergence.getPointer());
        }
        return values.toString();
    }

    private Path runAndWriteEvidence(Path study, String relativeRun, PlatformProfile platform, FrequencyPolicy policy)
            throws Exception {
        Path input = study.resolve("inputs/chain.dax");
        Files.createDirectories(input.getParent());
        Files.write(input, chainDax().getBytes(StandardCharsets.UTF_8));
        SimulationConfig.Builder builder = SimulationConfig.builder(input.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L);
        if (policy != null) {
            builder.frequencyPolicy(policy);
        }
        SimulationReport report = new SimulationRunner().run(builder.build(), platform);
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        Path run = study.resolve(relativeRun);
        ExperimentArtifactWriter.write(report, run, "result");
        return run;
    }

    private static String chainDax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"t1\" name=\"a\" runtime=\"10\"/>\n"
                + "  <job id=\"t2\" name=\"b\" runtime=\"30\"/>\n"
                + "  <child ref=\"t2\"><parent ref=\"t1\"/></child>\n"
                + "</adag>\n";
    }

    private static PlatformProfile tieredPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}}, null);
    }

    private static PlatformProfile twoTierPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}, {500.0, 100.0, 118.75}}, null);
    }

    private static PlatformProfile tieredCarbonPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}}, Double.valueOf(380.0));
    }

    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-v1-plain");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(plainVm(0));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(plainVm(1));
        return builder.build();
    }

    private static PlatformProfile tieredBudgetPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-tiered-budget");
        java.util.List<PlatformProfile.FrequencyTier> tiers = java.util.Arrays.asList(
                new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0),
                new PlatformProfile.FrequencyTier(500.0, 100.0, 118.75));
        for (int id = 0; id < 2; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000, 2048, 10000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id,
                    1000.0,
                    1,
                    512,
                    1000L,
                    10000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                    null,
                    0.0,
                    0.0,
                    null,
                    tiers));
            builder.pinVmToHost(id, id);
        }
        builder.carbonWindow(0.0, 60.0, 200.0);
        builder.carbonWindow(60.0, 4000.0, 300.0);
        return builder.build();
    }

    private static PlatformProfile tierPlatform(double[][] tierRows, Double carbonIntensity) {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : tierRows) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-tiered");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(tieredVm(0, tiers, carbonIntensity));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(tieredVm(1, tiers, carbonIntensity));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVm(
            int id, List<PlatformProfile.FrequencyTier> tiers, Double carbonIntensity) {
        return new PlatformProfile.VmSpec(
                id,
                1000.0,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                0.0,
                0.0,
                carbonIntensity,
                tiers);
    }

    private static PlatformProfile.VmSpec plainVm(int id) {
        return new PlatformProfile.VmSpec(
                id,
                1000.0,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                250.0,
                400.0,
                null,
                null);
    }
}
