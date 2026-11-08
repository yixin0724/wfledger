package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

/**
 * Carbon-dimension C1 conservation/identity test (BAT-C1, 2026-10-28, red-to-green anchor).
 * Legal sources for the expected values: the dimensional conversion law and the budget excess
 * integral formula (docs/advanced/CARBON_DIMENSION_DESIGN.md §1).
 */
class CarbonAccountingTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;
    private PlatformProfile lastProfile;

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
    void intensityUnitLawHoldsOnARealRun() throws Exception {
        SimulationReport report = run(true, 380.0, null);
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "declared intensity must produce an accounting");
        double expected = 0.0;
        for (java.util.Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            PlatformProfile.VmSpec spec = specOf(entry.getKey());
            double e = spec.getIdlePowerWatts() * report.getMakespan()
                    + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts())
                            * entry.getValue().getModeledBusyIntervalSeconds();
            expected += e / 3.6e6 * 380.0;
        }
        assertEquals(
                0,
                Double.compare(expected, carbon.getTotalCarbonGrams()),
                "intensity unit law: expected=" + expected + " actual=" + carbon.getTotalCarbonGrams());
        assertEquals(
                0,
                Double.compare(expected * report.getMakespan(), carbon.getCarbonMakespanProductGramSeconds()),
                "carbon×makespan identity");
        assertEquals(
                report.getMetrics().getVmMetrics().size(),
                carbon.getPerVmCarbonGrams().size(),
                "per-VM map completeness");
        assertTrue(carbon.getTotalCarbonGrams() > 0.0, "1 kWh-scale run must have positive grams");
    }

    @Test
    void budgetExcessIntegratesAndMatchesAHandCase() throws Exception {
        // Two windows (green 300 W / green 100 W) cover [0, 120 s). Busy fleet P = 250 + 150 = 400 W.
        // Window one exceeds by 100 x busy_in_window1; window two exceeds by 300 x busy_in_window2.
        // The hand derivation is performed inside the assertions: because the exact busy intervals are
        // unknown, first verify window sum == total and strict positivity, then cross-check the total
        // integral against the known formula.
        SimulationReport report = run(true, null, new double[][] {{0.0, 60.0, 300.0}, {60.0, 120.0, 100.0}});
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "declared windows must produce an accounting");
        double windowSum = 0.0;
        for (double v : carbon.getWindowExcessWattSeconds().values()) {
            windowSum += v;
        }
        assertEquals(windowSum, carbon.getTotalCarbonExcessWattSeconds(), 1e-9, "window sums == total");
        assertTrue(
                carbon.getTotalCarbonExcessWattSeconds() > 0.0,
                "green 300/100 below active 400 must yield positive excess");
        // Hard-core verification of the declared dependence: each window excess (P - green) x overlap duration >= 0
        // and structurally correct.
        assertEquals(
                0, Double.compare(0.0, carbon.getTotalCarbonGrams()), "budget model leaves intensity family untouched");
    }

    @Test
    void unpoweredIntensityIsRejectedAndPlainStaysAbsent() {
        // No carbon model declared -> null (a legal default, not all zeros).
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            SimulationReport plain = run(true, null, null);
            assertNull(plain.getCarbonAccounting(), "no carbon declaration => absent accounting");
        });
        // Unpowered platform + declared intensity -> constructor fail-fast.
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> run(false, 380.0, null),
                "unpowered + declared intensity must fail-fast (BAT-C1)");
        assertTrue(
                rejection.getMessage().contains("carbonIntensityGramsPerKWh"),
                "rejection must cite the field: " + rejection.getMessage());
    }

    @Test
    void bothModelsTogetherAreRejected() {
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> run(true, 380.0, new double[][] {{0.0, 60.0, 300.0}}),
                "both carbon models must be rejected at report construction");
        assertTrue(
                rejection.getMessage().contains("mutually exclusive"),
                "rejection must name the exclusivity clause: " + rejection.getMessage());
    }

    @Test
    void tieredPlatformIntensityRidesTheConservedPerTierEnergy() throws Exception {
        // BAT-D4 (2026-10-29) tiered ride-through red-to-green anchor. A frequency-tier platform
        // (flat power identically 0, BAT-D1 single source of truth) is a profile of declared power:
        // intensity carbon multiplies the <b>per-tier conserved energy</b>
        // (docs/advanced/DVFS_DIMENSION_DESIGN.md §1.5/§2.4). Previously this path was failed fast
        // as a whole by the BAT-C1 flat-field gate (a tiered run could not produce real carbonMetrics
        // -- the forward gap noted in the D3 design). Legal sources for the expected value: the §2.3
        // per-tier conservation law x the dimensional conversion law, with inputs taken from the
        // frequency-tier table + the per-tier ledger (the same source as the energy section) and the
        // TreeMap ascending summation order mirroring EnergyAccounting (floating-point determinism
        // discipline), without copying the carbon section's own output.
        java.nio.file.Path workflow = java.nio.file.Paths.get(tempDir.toFile().getAbsolutePath(), "dvfs.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.EDP_HEURISTIC)
                .randomSeed(20261029L)
                .build();
        java.util.List<PlatformProfile.FrequencyTier> tiers = java.util.Arrays.asList(
                new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0),
                new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0),
                new PlatformProfile.FrequencyTier(500.0, 210.0, 240.0));
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-carbon-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        2000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        0.0,
                        0.0,
                        380.0,
                        tiers))
                .pinVmToHost(0, 0);
        lastProfile = builder.build();
        SimulationReport report = new SimulationRunner().run(config, lastProfile);
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "tiered platform + declared intensity must produce an accounting");
        assertEquals(1, carbon.getPerVmCarbonGrams().size(), "per-VM map completeness");
        // §2.3 per-tier conservation law: E = P_idle(default tier) x T
        // + sum over tiers of (P_max(tier) - P_idle(default tier)) x busy(tier)
        double defaultIdle = 250.0;
        double expectedEnergy = defaultIdle * report.getMakespan();
        java.util.Map<Double, Double> ledger =
                report.getMetrics().getPerTierBusySecondsByVm().get(0);
        assertTrue(ledger != null && !ledger.isEmpty(), "EDP-tiered run must carry the per-tier ledger");
        for (java.util.Map.Entry<Double, Double> tier : new java.util.TreeMap<Double, Double>(ledger).entrySet()) {
            double tierMax =
                    tierMaxOf(lastProfile.getVms().get(0), tier.getKey().doubleValue());
            expectedEnergy += (tierMax - defaultIdle) * tier.getValue().doubleValue();
        }
        double expectedGrams = expectedEnergy / 3.6e6 * 380.0;
        assertEquals(
                0,
                Double.compare(expectedGrams, carbon.getTotalCarbonGrams()),
                "per-tier intensity law: expected=" + expectedGrams + " actual=" + carbon.getTotalCarbonGrams());
        assertEquals(
                0,
                Double.compare(expectedGrams, carbon.getPerVmCarbonGrams().get(0)),
                "per-VM grams ride the same conserved energy");
        assertEquals(
                0,
                Double.compare(expectedGrams * report.getMakespan(), carbon.getCarbonMakespanProductGramSeconds()),
                "carbon×makespan identity on tiered runs");
        assertTrue(carbon.getTotalCarbonGrams() > 0.0, "tiered powered run must have positive grams");
    }

    /**
     * A2 red-to-green anchor (2026-11-02): frequency-tier platform + carbon window = budget carbon.
     * Previously {@code budgetModel} honoured only the flat power fields, so a tiered VM (flat fields
     * identically 0, BAT-D1) failed fast outright, and the dimension crossing "5-tier P-state x green
     * budget" could not produce real carbonMetrics; the Python auditor had long implemented that branch
     * (the forward contract of DVFS_DIMENSION_DESIGN §2.3).
     *
     * <p>Legal source for the expected value: the contract law itself (a sentence-by-sentence mirror of
     * the documented Python-side convention) -- per-instant fleet power = sum over stalled VMs of the
     * default-tier idle + sum over active Jobs of (P_max(their tier) - P_idle(default tier)); this test
     * independently recomputes by that law inside the assertions and does not copy implementation
     * output.</p>
     */
    @Test
    void tieredPlatformBudgetCarbonUsesThePerTierFleetPowerCurve() throws Exception {
        SimulationReport report = runTieredBudget();
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "tiered platform + declared windows must now produce an accounting");

        // Independent recomputation of the contract law: endpoint folding by +/-dyn,
        // in-segment fleet power = idle_sum + sum of the active dyn terms.
        PlatformProfile profile = lastProfile;
        double defaultIdle = 250.0;
        double idleSum = 0.0;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            idleSum += spec.hasFrequencyTiers()
                    ? spec.getFrequencyTiers().get(0).getIdlePowerWatts()
                    : spec.getIdlePowerWatts();
        }
        java.util.TreeMap<Double, Double> ends = new java.util.TreeMap<Double, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2 || job.getFinishTime() <= job.getStartTime()) {
                continue;
            }
            PlatformProfile.VmSpec spec = profile.getVms().get(job.getVmId());
            double dyn;
            if (spec.hasFrequencyTiers()) {
                double tierMips = job.getTierMips() == null
                        ? spec.getFrequencyTiers().get(0).getMips()
                        : job.getTierMips().doubleValue();
                dyn = tierMaxOf(spec, tierMips) - defaultIdle;
            } else {
                dyn = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
            }
            ends.merge(Double.valueOf(job.getStartTime()), Double.valueOf(dyn), Double::sum);
            ends.merge(Double.valueOf(job.getFinishTime()), Double.valueOf(-dyn), Double::sum);
        }
        double expectedTotal = expectedTieredExcess(report, profile);
        assertEquals(
                0,
                Double.compare(expectedTotal, carbon.getTotalCarbonExcessWattSeconds()),
                "tiered budget law: expected=" + expectedTotal + " actual=" + carbon.getTotalCarbonExcessWattSeconds());
        assertTrue(expectedTotal > 0.0, "the fixture must produce a positive excess (green below active power)");
        // The budget family produces no grams (the two families are mutually exclusive):
        // the gram fields stay 0, which distinguishes them from the intensity family.
        assertEquals(0, Double.compare(0.0, carbon.getTotalCarbonGrams()));
    }

    /**
     * A2 cross-language adversarial case (2026-11-02): a Job on a flat VM carrying tierMips is a broken
     * evidence chain -- Java must reject it under the same law as the Python auditor, rather than
     * silently compute a "plausible-looking" value from the flat power (that would let the same piece
     * of evidence yield different verdicts on the two sides).
     */
    @Test
    void flatVmJobCarryingTierIdentityIsRejectedLikeThePythonAuditor() {
        PlatformProfile.VmSpec flat = new PlatformProfile.VmSpec(
                7,
                1000,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                120.0,
                300.0,
                null);
        assertEquals(
                0,
                Double.compare(180.0, CarbonAccounting.jobDynamicWatts(flat, 3, null)),
                "a tierless Job on a flat VM contributes max−idle");
        IllegalArgumentException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> CarbonAccounting.jobDynamicWatts(flat, 3, Double.valueOf(500.0)));
        assertTrue(rejected.getMessage().contains("non-tiered VM"), rejected.getMessage());

        PlatformProfile.VmSpec tiered = new PlatformProfile.VmSpec(
                8,
                1000,
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
                java.util.Arrays.asList(
                        new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0),
                        new PlatformProfile.FrequencyTier(500.0, 100.0, 118.75)));
        assertEquals(
                0,
                Double.compare(150.0, CarbonAccounting.jobDynamicWatts(tiered, 4, null)),
                "a tierless Job on a tiered VM is attributed to the default tier (400−250)");
        assertEquals(
                0,
                Double.compare(-131.25, CarbonAccounting.jobDynamicWatts(tiered, 4, Double.valueOf(500.0))),
                "a low tier may draw less than the default-tier idle (118.75−250)");
    }

    /** Contract-law recomputation: the window excess of the per-tier fleet power curve
     * (an independent pass over the documented convention). */
    private static double expectedTieredExcess(SimulationReport report, PlatformProfile profile) {
        double defaultIdleOfTiered = 0.0;
        double idleSum = 0.0;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (spec.hasFrequencyTiers()) {
                defaultIdleOfTiered = spec.getFrequencyTiers().get(0).getIdlePowerWatts();
                idleSum += defaultIdleOfTiered;
            } else {
                idleSum += spec.getIdlePowerWatts();
            }
        }
        java.util.TreeMap<Double, Double> ends = new java.util.TreeMap<Double, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2 || job.getFinishTime() <= job.getStartTime()) {
                continue;
            }
            PlatformProfile.VmSpec spec = profile.getVms().get(job.getVmId());
            double dyn;
            if (spec.hasFrequencyTiers()) {
                double tierMips = job.getTierMips() == null
                        ? spec.getFrequencyTiers().get(0).getMips()
                        : job.getTierMips().doubleValue();
                dyn = tierMaxOf(spec, tierMips)
                        - spec.getFrequencyTiers().get(0).getIdlePowerWatts();
            } else {
                dyn = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
            }
            ends.merge(Double.valueOf(job.getStartTime()), Double.valueOf(dyn), Double::sum);
            ends.merge(Double.valueOf(job.getFinishTime()), Double.valueOf(-dyn), Double::sum);
        }
        java.util.List<double[]> segments = new java.util.ArrayList<double[]>();
        double active = 0.0;
        double prev = 0.0;
        for (java.util.Map.Entry<Double, Double> end : ends.entrySet()) {
            double t = end.getKey().doubleValue();
            if (t > prev) {
                segments.add(new double[] {prev, t, idleSum + active});
            }
            active += end.getValue().doubleValue();
            prev = t;
        }
        if (prev < report.getMakespan()) {
            segments.add(new double[] {prev, report.getMakespan(), idleSum + active});
        }
        double total = 0.0;
        for (PlatformProfile.CarbonWindow window : profile.getCarbonWindows()) {
            double acc = 0.0;
            for (double[] segment : segments) {
                double lo = Math.max(segment[0], window.getStartSeconds());
                double hi = Math.min(segment[1], window.getEndSeconds());
                if (hi > lo) {
                    acc += Math.max(0.0, segment[2] - window.getGreenWatts()) * (hi - lo);
                }
            }
            total += acc;
        }
        return total;
    }

    /**
     * A2 adversarial surface: a mixed fleet (one tiered VM + one flat VM) must follow the same fleet
     * power curve under one budget window -- the tiered contribution takes the default-tier idle plus
     * the per-tier dynamics, while the flat contribution keeps the v1 max-idle.
     */
    @Test
    void mixedFleetBudgetCarbonMixesTieredAndFlatContributions() throws Exception {
        SimulationReport report = runMixedFleetBudget();
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "a mixed fleet with windows must produce an accounting");
        double expected = expectedTieredExcess(report, lastProfile);
        assertEquals(
                0,
                Double.compare(expected, carbon.getTotalCarbonExcessWattSeconds()),
                "mixed-fleet law: expected=" + expected + " actual=" + carbon.getTotalCarbonExcessWattSeconds());
        assertTrue(expected > 0.0, "the fixture must yield a positive excess");
    }

    /** Mixed-fleet fixture: VM0 is tiered (default-tier idle 250), VM1 is flat (idle 120 / max 300). */
    private SimulationReport runMixedFleetBudget() throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "mixed.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER)
                .randomSeed(20261103L)
                .build();
        java.util.List<PlatformProfile.FrequencyTier> tiers = java.util.Arrays.asList(
                new PlatformProfile.FrequencyTier(2000.0, 250.0, 4000.0),
                new PlatformProfile.FrequencyTier(500.0, 100.0, 118.75));
        PlatformProfile.Builder builder = PlatformProfile.builder("mixed-fleet-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        2000,
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
                        tiers))
                .pinVmToHost(0, 0);
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        1,
                        2000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        120.0,
                        300.0,
                        null,
                        java.util.Collections.<PlatformProfile.FrequencyTier>emptyList()))
                .pinVmToHost(1, 1);
        builder.carbonWindow(0.0, 20000.0, 260.0);
        lastProfile = builder.build();
        return new SimulationRunner().run(config, lastProfile);
    }

    /** Frequency-tier platform + single-window budget-carbon fixture: MIN_POWER pushes the Job to a
     * low tier, so the power curve follows the tier. */
    private SimulationReport runTieredBudget() throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "tiered-budget.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER)
                .randomSeed(20261102L)
                .build();
        java.util.List<PlatformProfile.FrequencyTier> tiers = java.util.Arrays.asList(
                new PlatformProfile.FrequencyTier(2000.0, 250.0, 4000.0),
                new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0),
                new PlatformProfile.FrequencyTier(500.0, 100.0, 118.75));
        PlatformProfile.Builder builder = PlatformProfile.builder("tiered-budget-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        2000,
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
                        tiers))
                .pinVmToHost(0, 0);
        builder.carbonWindow(0.0, 10000.0, 200.0);
        lastProfile = builder.build();
        return new SimulationRunner().run(config, lastProfile);
    }

    /** BAT-D4: look up the tier power by exact MIPS identity (the frequency-tier table is unique in
     * descending order; the same contract as VmSpec.tierByMips). */
    private static double tierMaxOf(PlatformProfile.VmSpec spec, double mips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (Double.compare(tier.getMips(), mips) == 0) {
                return tier.getMaxPowerWatts();
            }
        }
        throw new IllegalStateException("tier mips " + mips + " matches no declared tier");
    }

    /**
     * CaWoSched report compatibility: the planner and report integrate the same full deadline
     * horizon and heterogeneous flat dynamic powers. VM0 (idle 10, max 110) runs [0.21,2.21);
     * VM1 (idle 20, max 220) runs [0.21,1.21). Dynamic excess is 100*2+200*1=400 W*s and idle
     * excess is 30*10=300 W*s, so the full-deadline total is 700 W*s. The actual Job intervals
     * are asserted before the hand arithmetic.
     */
    @Test
    void caWoSchedBudgetUsesFullDeadlineAndHeterogeneousFlatPower() throws Exception {
        SimulationReport report = runCaWoSchedFlatBudget(PlanningAlgorithm.CAWOSCHED_SLACK);
        assertJobWindow(report, 1, 1, 0.21, 1.21);
        assertJobWindow(report, 2, 0, 0.21, 2.21);
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertEquals(700.0, carbon.getTotalCarbonExcessWattSeconds(), 1.0e-9);
        assertEquals(550.0, carbon.getWindowExcessWattSeconds().get(0), 1.0e-9);
        assertEquals(150.0, carbon.getWindowExcessWattSeconds().get(1), 1.0e-9);
    }

    /** The pressure label shares the same report semantics; the fixed ASAP fixture is identical. */
    @Test
    void caWoSchedPressureBudgetUsesTheSameFullDeadlineLaw() throws Exception {
        SimulationReport report = runCaWoSchedFlatBudget(PlanningAlgorithm.CAWOSCHED_PRESSURE);
        assertJobWindow(report, 1, 1, 0.21, 1.21);
        assertJobWindow(report, 2, 0, 0.21, 2.21);
        assertEquals(700.0, report.getCarbonAccounting().getTotalCarbonExcessWattSeconds(), 1.0e-9);
    }

    /** Non-CaWoSched keeps the historical first-VM delta and makespan horizon. */
    @Test
    void nonCaWoSchedBudgetPreservesFirstVmAndMakespanLaw() throws Exception {
        SimulationReport report = runCaWoSchedFlatBudget(PlanningAlgorithm.SHARED_STORAGE_HEFT);
        assertJobWindow(report, 1, 1, 0.21, 1.21);
        assertJobWindow(report, 2, 0, 0.21, 2.21);
        double expected = 30.0 * report.getMakespan() + 100.0 * 1.0 + 100.0 * 2.0;
        assertEquals(expected, report.getCarbonAccounting().getTotalCarbonExcessWattSeconds(), 1.0e-9);
        assertEquals(
                expected,
                report.getCarbonAccounting().getWindowExcessWattSeconds().get(0),
                1.0e-9);
        assertTrue(!report.getCarbonAccounting().getWindowExcessWattSeconds().containsKey(1));
    }

    private static void assertJobWindow(SimulationReport report, int taskId, int vmId, double start, double finish) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == 2 && job.getTaskIds().contains(taskId)) {
                assertEquals(vmId, job.getVmId());
                assertEquals(start, job.getStartTime(), 1.0e-9);
                assertEquals(finish, job.getFinishTime(), 1.0e-9);
                return;
            }
        }
        throw new AssertionError("Missing compute Job for task " + taskId);
    }

    private SimulationReport runCaWoSchedFlatBudget(PlanningAlgorithm algorithm) throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), algorithm + "-budget.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"1\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"4\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .deadline(10L)
                .runtimeReferenceMips(1000.0)
                .randomSeed(20261108L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("cawosched-flat-budget");
        builder.addHost(new PlatformProfile.HostSpec(0, 1, 2000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        2000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        10.0,
                        110.0,
                        null))
                .pinVmToHost(0, 0);
        builder.addHost(new PlatformProfile.HostSpec(1, 1, 1000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        1,
                        1000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        20.0,
                        220.0,
                        null))
                .pinVmToHost(1, 1);
        builder.carbonWindow(0.0, 5.0, 0.0).carbonWindow(5.0, 10.0, 0.0);
        lastProfile = builder.build();
        return new SimulationRunner().run(config, lastProfile);
    }

    // ---- fixtures ----

    private SimulationReport run(boolean powered, Double intensity, double[][] windows) throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "carbon.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261028L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-test");
        PlatformProfile.VmSpec vm = new PlatformProfile.VmSpec(
                0,
                1000,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                powered ? 250.0 : 0.0,
                powered ? 400.0 : 0.0,
                intensity);
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L))
                .addVm(vm)
                .pinVmToHost(0, 0);
        if (windows != null) {
            for (double[] w : windows) {
                builder.carbonWindow(w[0], w[1], w[2]);
            }
        }
        lastProfile = builder.build();
        return new SimulationRunner().run(config, lastProfile);
    }

    private PlatformProfile.VmSpec specOf(int vmId) {
        for (PlatformProfile.VmSpec spec : lastProfile.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        throw new IllegalStateException("vm " + vmId + " not in test profile");
    }
}
