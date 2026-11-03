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

/**
 * BAT-V4 (2026-10-29 test-engineer adversarial round, DVFS round five): rerun
 * compatibility of DVFS evidence -- "an honest loud red, not a crash, not -green, not
 * silence".
 *
 * <p>This round (a real CLI rerun against the D5 frozen baseline bundles) found two
 * real defects, locked red->green here:</p>
 * <ol>
 *   <li><b>Runtime rejection escapes the verdict contract (real defect #1)</b>: when the
 *       manifest of three-dimensional (tiered+carbon) evidence is rebuilt,
 *       {@code ManifestConfigRebuilder} strips the frequency tiers (the documented
 *       forward contract); the rebuilt "no power + carbon intensity" illegal
 *       half-declared platform throws {@code IllegalArgumentException} (the BAT-C1
 *       half-declaration law) while {@code SimulationReport} is constructed. No rerun
 *       had <b>caught that exception</b>: it bubbled up to the CLI usage fallback
 *       ("rerun refused", exit 64) and <b>neither report was written</b> -- exactly the
 *       crash-class failure that the contract's execution-flow section eliminates with
 *       "a runtime rejection is a legal form of RECONSTRUCTION_REJECTED". Fix:
 *       RerunExecutor maps IllegalArgumentException/IllegalStateException/
 *       SimulationConfigurationException from the execution phase uniformly to
 *       RECONSTRUCTION_REJECTED (reports still written, exit 4).</li>
 *   <li><b>False core divergence of historical v1 evidence (real defect #2)</b>: since
 *       D4 the configuration section writes frequencyPolicy unconditionally -- pre-D4
 *       frozen evidence (the power-v1/carbon-v1/standard-v1 baselines) gains a
 *       {@code /configuration/frequencyPolicy &lt;missing&gt; vs "DEFAULT"} single-point
 *       core divergence on every rerun, degrading the whole batch from IDENTICAL_CORE to
 *       DIVERGED (this test reproduces it with an equivalent bundle that deletes the key
 *       to restore the pre-D4 shape; the fix is the key-default emission rule in
 *       ExperimentManifestWriter, see FrequencyTierManifestEmissionTest).</li>
 * </ol>
 *
 * <p>Three sources for the expectations: RERUN_DIFF_CONTRACT.md (verdict semantics /
 * exit-code table) + DVFS_DIMENSION_DESIGN.md section 2.4 ("a tiered manifest rerun
 * fails on the recorded red divergence, not silently" -- the carbon-free tiered form
 * goes through the differ's loud red; the three-dimensional form is rejected at runtime
 * by current code after rebuilding -> RECONSTRUCTION_REJECTED of the verdict contract).
 * Reruns of v1 evidence (tierless flat power) must stay IDENTICAL_CORE.</p>
 *
 * <p><b>A1 (2026-11-02 user adjudication): tiered evidence is rerunnable again.</b>
 * {@code ManifestConfigRebuilder} now round-trips
 * {@code platform.vms[].frequencyTiers} and {@code configuration.frequencyPolicy}, so the
 * "tiered" and "tiered+carbon" evidence move from "runtime rejection / loud red on the
 * tier evidence surface" to <b>IDENTICAL_CORE</b>. The old contract (rebuild strips the
 * tiers -> rejection or leaf-by-leaf loud red) is anchored by the first two tests of
 * this class and rewritten here against the new contract; the obligation "a failed
 * rebuild must stay loud" has not disappeared -- a malformed table (not strictly
 * descending) is still refused by the rebuilder, anchored by the third test.</p>
 */
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

    /**
     * A1 main anchor (2026-11-02): a rerun of three-dimensional (tiered+carbon) evidence
     * must be IDENTICAL_CORE.
     *
     * <p>Before the fix: the rebuilder stripped the frequency tiers -> the rebuilt
     * platform ("carbon intensity + no power") violated the BAT-C1 half-declaration law
     * -> runtime rejection (RECONSTRUCTION_REJECTED). After the fix: the tier table and
     * the assignment policy round-trip through the manifest, the rebuilt platform is
     * isomorphic to the original run, and the rerun matches leaf by leaf.</p>
     */
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

    /**
     * A1 second anchor: carbon-free tiered evidence also reruns IDENTICAL_CORE, and the
     * rerun side must <b>carry</b> the tier table and the per-Job tier identity -- the
     * rebuild is not "it happens to match after degrading to v1", it really rebuilds the
     * tiers.
     */
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

    /**
     * The loud-refusal obligation has not disappeared: when the tier table in the
     * manifest is malformed (not strictly descending), the rebuilder still refuses it and
     * maps to RECONSTRUCTION_REJECTED (report written, exit 4).
     */
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

    /** v1 evidence (tierless flat power): the rerun must stay green (BAT-E5 invariant regression slot). */
    @Test
    void v1FlatPowerEvidenceStillRerunsIdentically() throws Exception {
        Path study = directory.resolve("v1");
        Path run = runAndWriteEvidence(study, "runs/v1", plainPoweredPlatform(), null);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");
    }

    /**
     * Red->red->green anchor for defect #2: pre-D4-shaped v1 evidence (configuration
     * section without the frequencyPolicy key -- the actual shape of every frozen
     * power-v1/carbon-v1/standard-v1 bundle) must still be IDENTICAL_CORE. The value
     * semantics are equivalent (missing key == the only legal DEFAULT on a tierless
     * platform); a divergence can only be a false signal of the emission surface.
     */
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

    /**
     * A1 x A2 interaction (2026-11-02 review round): a bundle with tiers + carbon windows
     * (budget model) must still rerun IDENTICAL_CORE. This path exercises two new things
     * at once: tier table rebuild (A1) and the per-tier fleet power curve (A2) -- the
     * windows are rebuilt independently by the rebuilder, the tier table is rebuilt by
     * VmSpec, and both sides must match.
     */
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

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Erase the frequencyPolicy member from the run-manifest configuration: the pre-D4 frozen shape. */
    /**
     * Adversarial surface (A1 round): the rebuilder performs a faithful translation only,
     * so any "the record contradicts itself" shape must be refused as
     * RECONSTRUCTION_REJECTED rather than half-run. Four shapes, one anchor each: tier
     * table and flat power coexist (one source of truth) / tier without mips (missing
     * physical quantity) / unknown policy enum / non-DEFAULT policy with no tiers in the
     * record (which would silently go inert at runtime).
     */
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

    /** Tier table plus flat power coexist: the BAT-D1 one-source-of-truth law must refuse it. */
    private static void addFlatPowerBesidesTiers(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        JsonObject vm = firstVm(doc);
        vm.addProperty("idlePowerWatts", 250.0);
        vm.addProperty("maxPowerWatts", 400.0);
        writeManifest(runDirectory, doc);
    }

    /**
     * A tier without mips: the physical quantity is missing, so the rebuilder must fail
     * explicitly instead of filling in a default.
     */
    private static void dropFirstTierMips(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        com.google.gson.JsonArray tiers = firstVm(doc).getAsJsonArray("frequencyTiers");
        tiers.get(0).getAsJsonObject().remove("mips");
        writeManifest(runDirectory, doc);
    }

    /** Write the policy field as a non-string (JSON number): a type error must still follow the verdict contract. */
    private static void setRawFrequencyPolicy(Path runDirectory, int value) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        doc.getAsJsonObject("configuration").addProperty("frequencyPolicy", value);
        writeManifest(runDirectory, doc);
    }

    /** Unknown policy enum: do not degrade to DEFAULT. */
    private static void setFrequencyPolicy(Path runDirectory, String policy) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        doc.getAsJsonObject("configuration").addProperty("frequencyPolicy", policy);
        writeManifest(runDirectory, doc);
    }

    /**
     * Erase the tier table but keep (or inject) a non-DEFAULT policy.
     */
    private static void stripTierTable(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        for (com.google.gson.JsonElement element :
                doc.getAsJsonObject("platform").getAsJsonArray("vms")) {
            element.getAsJsonObject().remove("frequencyTiers");
        }
        writeManifest(runDirectory, doc);
    }

    /**
     * Reverse the tier order recorded in the manifest: mips becomes strictly ascending,
     * which is an illegal table the rebuilder must refuse.
     */
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
        // After the BAT-V4.2 fix, the manifest of a v1 tierless run omits this key by
        // default anyway (a bit-for-bit restoration of the pre-D4 frozen shape); before
        // the fix the key was emitted unconditionally, so delete it here to reproduce the
        // actual shape of the frozen bundles -- whatever the current emission surface
        // does, this test anchors "the keyless original <em>must</em> rerun green".
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

    /** Single-tier platform: tier speed = VM base speed 1000 (clock identical to v1), pair (250/400). */
    private static PlatformProfile tieredPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}}, null);
    }

    /**
     * Two-tier platform: used only by the "illegal tier table" contrast test (a
     * single-tier table cannot encode an illegal order).
     */
    private static PlatformProfile twoTierPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}, {500.0, 100.0, 118.75}}, null);
    }

    /**
     * Three-dimensional platform: the same table plus a uniform carbon intensity of 380
     * (the fleet profile of the D5 frozen baseline).
     */
    private static PlatformProfile tieredCarbonPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}}, Double.valueOf(380.0));
    }

    /** v1 flat-power platform (250/400), using the same power pair as the tiered platform. */
    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-v1-plain");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(plainVm(0));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(plainVm(1));
        return builder.build();
    }

    /**
     * Tiered plus carbon-window platform: frequency tier table and budget windows
     * declared together (the A1 x A2 interaction surface).
     */
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
