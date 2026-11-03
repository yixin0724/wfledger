package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

/**
 * BAT-V4.2 (2026-10-29 test-engineer adversarial round, DVFS round 5): red -> green
 * on the emission shape of the frequencyPolicy key in the configuration section.
 *
 * <p>Attack background (empirical finding of this round): D4 made
 * {@code ExperimentManifestWriter} write {@code configuration.frequencyPolicy}
 * unconditionally -- including the DEFAULT (lazy) value of a v1 tierless platform.
 * Consequently <b>every v1 evidence bundle frozen before D4</b> (the power-v1 /
 * carbon-v1 / standard-v1 baselines, and all historical bundles) gains one core
 * divergence on rerun under EvidenceCoreDiffer,
 * {@code /configuration/frequencyPolicy <missing> vs "DEFAULT"} -> the whole body
 * of historical evidence degrades from IDENTICAL_CORE to DIVERGED. The contract
 * between the e-prior evidence and "v1 manifest bit-for-bit unchanged" (the same
 * discipline as the D3 platform section: "key omitted when empty") is broken by a
 * single emission line of D4. Red anchor: assert that the serialized surface of a
 * v1 run carries no {@code frequencyPolicy}.</p>
 *
 * <p>Emission rules after the fix (mirroring the
 * "declared -> present, absent -> key omitted" culture of
 * tierMips/frequencyTiers/carbonWindows):</p>
 * <ul>
 *   <li>v1 tierless platform (the policy can only be DEFAULT -- the runner's BAT-D2
 *       gate rejects anything else): key omitted;</li>
 *   <li>non-DEFAULT policy: always written (any platform);</li>
 *   <li>declared tier table (tiered platform): always written (required by rerun
 *       semantics; the DEFAULT cells of the D5 three-dimensional baseline keep an
 *       explicit label).</li>
 * </ul>
 */
class FrequencyTierManifestEmissionTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;
    private Path workflowDax;

    @BeforeEach
    void quietSimLog() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimLog() {
        Log.setDisabled(logWasDisabled);
    }

    /**
     * Red anchor (red before this round's fix): the full serialized surface of a v1
     * tierless run carries no frequencyPolicy key.
     */
    @Test
    void v1TierlessManifestOmitsTheFrequencyPolicyKeyEntirely() throws Exception {
        SimulationReport plain = run(plainPoweredPlatform(), null);
        java.util.Map<String, Object> manifest =
                ExperimentManifestWriter.toManifest(plain, new ArrayList<java.util.Map<String, Object>>(), null);
        assertFalse(manifest.get("configuration").toString().isEmpty(), "wire format parse guard");
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(manifest);
        assertFalse(
                serialized.contains("frequencyPolicy"),
                "v1 tierless manifests must NOT carry a frequencyPolicy key (absent = DEFAULT = v1 legacy"
                        + " shape; unconditional emission demeans every pre-D4 frozen bundle's rerun"
                        + " into a fake core divergence): " + excerpt(serialized));
    }

    /**
     * A tiered run must write the frequency policy (part of the rerun semantics; same
     * as the DEFAULT cells of the D5 baseline).
     */
    @Test
    void tieredManifestRecordsTheFrequencyPolicy() throws Exception {
        SimulationReport tiered = run(twoTierPlatform(), FrequencyPolicy.DEFAULT);
        java.util.Map<String, Object> manifest =
                ExperimentManifestWriter.toManifest(tiered, new ArrayList<java.util.Map<String, Object>>(), null);
        Object policy = ((java.util.Map<?, ?>) manifest.get("configuration")).get("frequencyPolicy");
        assertEquals("DEFAULT", String.valueOf(policy), "tiered manifests record the policy label explicitly");
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(manifest);
        assertFalse(serialized.contains("\"tierMips\":null"), "sanity: no fabricated null tier evidence");
    }

    private static String excerpt(String serialized) {
        int at = serialized.indexOf("configuration");
        return at < 0
                ? serialized.substring(0, Math.min(200, serialized.length()))
                : serialized.substring(at, Math.min(at + 400, serialized.length()));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private SimulationReport run(PlatformProfile platform, FrequencyPolicy policy) throws Exception {
        SimulationConfig.Builder builder = SimulationConfig.builder(chainDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L);
        if (policy != null) {
            builder.frequencyPolicy(policy);
        }
        return new SimulationRunner().run(builder.build(), platform);
    }

    /** Two-tier platform (2000 default / 500 last tier). */
    private static PlatformProfile twoTierPlatform() {
        java.util.List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        tiers.add(new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0));
        tiers.add(new PlatformProfile.FrequencyTier(500.0, 210.0, 260.0));
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-emit-tier");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(1, tiers));
        return builder.build();
    }

    /** v1 flat-power platform (250/400, tierless). */
    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-emit-plain");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                0,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                250.0,
                400.0,
                null,
                null));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                1,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                250.0,
                400.0,
                null,
                null));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVmSpec(int id, java.util.List<PlatformProfile.FrequencyTier> tiers) {
        return new PlatformProfile.VmSpec(
                id,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                0.0,
                0.0,
                null,
                tiers);
    }

    private Path chainDax() throws Exception {
        if (workflowDax == null) {
            StringBuilder dax = new StringBuilder();
            dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            dax.append("<adag version=\"3.3\" count=\"2\">\n");
            dax.append("  <job id=\"t1\" name=\"a\" runtime=\"10\"/>\n");
            dax.append("  <job id=\"t2\" name=\"b\" runtime=\"30\"/>\n");
            dax.append("  <child ref=\"t2\"><parent ref=\"t1\"/></child>\n");
            dax.append("</adag>\n");
            workflowDax = Paths.get(tempDir.toFile().getAbsolutePath(), "v4-emit-chain.dax");
            Files.write(workflowDax, dax.toString().getBytes(StandardCharsets.UTF_8));
        }
        return workflowDax;
    }
}
