package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.experiment.registry.SelectorNullTierDvfsTestSchedulerProvider;
import org.workflowsim.experiment.registry.SelectorTierDvfsTestSchedulerProvider;
import org.workflowsim.experiment.registry.SelectorUndeclaredTierDvfsTestSchedulerProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * BAT-D6 (2026-10-29, docs/advanced/DVFS_DIMENSION_DESIGN.md section 2.2 registration
 * surface): behaviour contract of the first-class scheduling-time API for per-Job
 * frequency-tier assignment
 * ({@link org.workflowsim.scheduling.FrequencyTierSelector}) -- precedence, evidence
 * surface and fail-fast semantics.
 *
 * <p>Source of the expected values (the "contract documents" of the AGENTS three
 * sources): design document section 2.2 states that "a registered DVFS-aware algorithm
 * may assign a frequency tier per Job through the scheduling-time API" -- when the active
 * policy instance implements the selector, the per-Job tier choice belongs to the
 * algorithm, and the session-level policy degrades to the default for algorithms without
 * a selector; an assignment must be a "declared tier inside the candidate table" (an
 * empty tier or an off-table tier is rejected explicitly as an evidence-chain break, the
 * scheduling-side counterpart of the D2 tierByMips clause).</p>
 *
 * <p>The test drives real runs through three test-registry providers (SPI registered on
 * the simulator test classpath): the legal face (always picks the last tier) / break face
 * one (returns null) / break face two (off-table tier mips=1500).</p>
 */
class FrequencyTierSelectorTest {

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

    /** 1. Precedence: the selector (algorithm-side per-Job decision) overrides the session DEFAULT policy. */
    @Test
    void selectorPrecedenceOverSessionDefaultPolicy() throws Exception {
        SimulationReport report = run(null, () -> registeredConfig(SelectorTierDvfsTestSchedulerProvider.LABEL));

        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully(), "selector run must complete");
        List<Double> computeTiers = new ArrayList<Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                assertNotNull(job.getTierMips(), "every compute job gets a tier at admission (BAT-D6)");
                computeTiers.add(job.getTierMips());
            }
        }
        assertTrue(computeTiers.size() >= 2, "bag fixture must carry compute jobs");
        for (Double tier : computeTiers) {
            assertEquals(
                    500.0, tier.doubleValue(), 0.0, "selector picks the LAST tier, not the session DEFAULT (first)");
        }
        // Ledger face: the lowest tier key of the fleet per-tier ledger is exactly the last tier the selector assigns.
        assertEquals(
                Double.valueOf(500.0),
                Double.valueOf(java.util.Collections.min(
                        report.getMetrics().getFleetPerTierBusySeconds().keySet())),
                "the fleet per-tier ledger must reflect the selector's tier (500)");
        // Control arm: built-in FCFS + the same 3-tier platform + session DEFAULT -> first
        // tier 2000 (the default face without a selector).
        SimulationReport builtin = run(null, () -> builtInConfig(SchedulingAlgorithm.FCFS));
        for (SimulationReport.JobOutcome job : builtin.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                assertEquals(
                        2000.0,
                        job.getTierMips().doubleValue(),
                        0.0,
                        "the non-selector arm stays on the session DEFAULT (first tier)");
            }
        }
    }

    /**
     * 2. Break face one: the selector returns null -> explicit failure citing the
     * "exactly one tier at admission" clause.
     */
    @Test
    void nullSelectorOutputFailsFastCitingAdmissionClause() throws Exception {
        expectRunFailureContaining(SelectorNullTierDvfsTestSchedulerProvider.LABEL, "returned no tier for Job");
    }

    /**
     * 3. Break face two: off-table tier (mips 1500 not in {2000, 1000, 500}) -> explicit
     * failure citing the evidence-chain clause.
     */
    @Test
    void undeclaredTierSelectionFailsFastCitingEvidenceChainClause() throws Exception {
        expectRunFailureContaining(
                SelectorUndeclaredTierDvfsTestSchedulerProvider.LABEL, "matches no declared frequency tier");
    }

    // ------------------------------------------------------------------
    // Fixtures and run assembly
    // ------------------------------------------------------------------

    private interface ConfigSupplier {
        SimulationConfig config() throws Exception;
    }

    private SimulationReport run(PlatformProfile platform, ConfigSupplier supplier) throws Exception {
        return new SimulationRunner().run(supplier.config(), platform != null ? platform : threeTierPlatform());
    }

    /**
     * Failure must be explicit and attributable: the real run of a registered break-face
     * provider must throw, and the BAT-D6 clause must be found in the exception chain
     * (including any wrapping by the CloudSim event loop) -- no silent continuation, no
     * fabricated tier evidence.
     */
    private void expectRunFailureContaining(String label, String expectedClause) throws Exception {
        try {
            new SimulationRunner().run(registeredConfig(label), threeTierPlatform());
            fail("Provider " + label + " must fail fail-fast (BAT-D6), but the run completed");
        } catch (Throwable failure) {
            assertCauseChainContains(failure, expectedClause);
        }
    }

    /** Recursive exception-chain match. */
    private static void assertCauseChainContains(Throwable failure, String clause) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 12) {
            if (current.getMessage() != null && current.getMessage().contains(clause)) {
                return;
            }
            current = current.getCause();
            depth++;
        }
        throw new AssertionError(
                "Expected an exception chain containing \"" + clause + "\" (BAT-D6), got: " + failure, failure);
    }

    private SimulationConfig registeredConfig(String label) throws Exception {
        return SimulationConfig.builder(bagDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT)
                .registeredSchedulingAlgorithm(AlgorithmRegistry.find(label))
                .build();
    }

    private SimulationConfig builtInConfig(SchedulingAlgorithm scheduling) throws Exception {
        return SimulationConfig.builder(bagDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(scheduling)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .build();
    }

    /**
     * Dependency-free bag (40/30/95/35 seconds -- the E5 control slots): 4 compute Jobs
     * become ready at once.
     */
    private Path bagDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"4\">\n");
        int[] runs = {40, 30, 95, 35};
        for (int i = 1; i <= runs.length; i++) {
            dax.append("  <job id=\"t")
                    .append(i)
                    .append("\" name=\"b")
                    .append(i)
                    .append("\" runtime=\"")
                    .append(runs[i - 1])
                    .append("\"/>\n");
        }
        dax.append("</adag>\n");
        Path path = tempDir.resolve("selector-bag.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /**
     * 2-VM/2-Host platform, base frequency 1000 per VM, 3 tiers in descending order
     * (2000/1000/500 -- the same table as the D2 test).
     */
    private static PlatformProfile threeTierPlatform() {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        tiers.add(new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0));
        tiers.add(new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0));
        tiers.add(new PlatformProfile.FrequencyTier(500.0, 210.0, 260.0));
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-selector-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVm(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVm(1, tiers));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVm(int id, List<PlatformProfile.FrequencyTier> tiers) {
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
}
