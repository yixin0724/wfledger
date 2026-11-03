package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

/**
 * BAT-V2 (2026-10-29 test-engineer adversarial round, DVFS round five): boundary-semantics deep dive --
 * the degeneration identity of "a tier exactly on the busy boundary". Three sources for the expected
 * values: (1) the degeneration identity (design §2.4 promise: MIN_POWER on a single-tier platform
 * equals running every Job at that unique tier, with the conservation law and the energy giving
 * exactly the v1 formula on the same set of numbers); (2) the conservation law (BAT-E1 v1 linear law
 * E_vm = idle x T_sim + (max - idle) x T_busy); (3) the boundary tier-speed identity (tier mips ==
 * the declared VM speed -> the scaledTierWorkMi factor is exactly 1.0 -> the clock is bit-identical
 * to v1).
 *
 * <p>Attack target: if MIN_POWER's tier-selection code degenerates wrongly on a single-tier table
 * (for example a "last tier" index out of bounds, the default tier pointing at another tier, or the
 * length scaling not being the identity at factor 1.0), every test in this class goes red -- D2's
 * last-tier assignment and D1's single-tier degeneration law must both hold on the
 * <b>non-default-policy</b> path.</p>
 */
class FrequencyTierBoundaryDegenerationTest {

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
     * V2 main assertion: MIN_POWER x single-tier platform (tier speed exactly the declared VM speed
     * 1000 -- the common boundary of up- and down-scaling) vs a v1 flat-power twin platform:
     *
     * <ul>
     *   <li>every compute Job is assigned exactly that unique tier (tierMips=1000) and the bookkeeping
     *       envelope stays null;</li>
     *   <li>the clock is bit-identical (scaling factor 1.0: execution seconds, makespan and task
     *       windows all equal);</li>
     *   <li>the per-tier ledger has exactly one key per VM (1000.0), whose value == that VM's busy
     *       union;</li>
     *   <li>the total energy is <b>bit-identical</b> to the v1 twin platform (0.0 tolerance), and ==
     *       the hand-derived v1 law (per VM over the metrics end/busy: 250 x end + 150 x busy);</li>
     *   <li>MIN_POWER's source of "slower / cheaper" distinguishability is falsified by the attack:
     *       on the single-tier boundary it is neither slower nor cheaper -- both elapsed time and
     *       energy must be identical to v1 (so that the degenerate path cannot sneak out a fake DVFS
     *       effect).</li>
     * </ul>
     */
    @Test
    void minPowerOnSingleTierPlatformIsExactlyTheV1LawOnTheSameNumbers() throws Exception {
        // The v1 twin platform has no frequency tiers (a non-DEFAULT policy is rejected by the runner),
        // so it takes the default DEFAULT policy.
        SimulationReport tieredReport = run(singleTierPlatform(), FrequencyPolicy.MIN_POWER).report;
        SimulationReport plain = run(plainPoweredPlatform(), null).report;

        assertTrue(tieredReport.getMetrics().isWorkflowCompletedSuccessfully(), "tiered run must complete");
        assertTrue(plain.getMetrics().isWorkflowCompletedSuccessfully(), "plain run must complete");

        // 1. Every Job sits exactly on the unique tier (MIN_POWER = last tier = the only tier);
        // the bookkeeping envelope (stage-in) is null.
        for (SimulationReport.JobOutcome job : tieredReport.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "compute job must carry its tier on a tiered platform");
                assertEquals(1000.0, job.getTierMips().doubleValue(), 0.0, "the single declared tier");
            } else {
                assertNull(job.getTierMips(), "bookkeeping envelope stays v1-null");
            }
        }
        // v1-side certificate: no tier evidence surface.
        for (SimulationReport.JobOutcome job : plain.getJobs()) {
            assertNull(job.getTierMips(), "v1 platform carries no tier evidence");
        }

        // 2. The clock is bit-identical (boundary tier speed gives factor 1.0).
        assertEquals(plain.getMakespan(), tieredReport.getMakespan(), 0.0, "schedule clock must be bit-identical");
        for (int i = 0; i < plain.getJobs().size(); i++) {
            assertEquals(
                    plain.getJobs().get(i).getExecutionTime(),
                    tieredReport.getJobs().get(i).getExecutionTime(),
                    0.0,
                    "per-job execution time identical at the boundary tier speed");
            assertEquals(
                    plain.getJobs().get(i).getStartTime(),
                    tieredReport.getJobs().get(i).getStartTime(),
                    0.0,
                    "per-job start identical");
        }

        // 3. Per-tier ledger: exactly one key per VM (the tier speed), value == the busy union;
        // the fleet ledger key set contains only that tier as well.
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                tieredReport.getMetrics().getVmMetrics().entrySet()) {
            Map<Double, Double> ledger =
                    tieredReport.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            if (entry.getValue().getModeledBusyIntervalSeconds() > 0.0) {
                assertNotNull(ledger, "busy VM must carry a ledger");
                assertEquals(1, ledger.size(), "single tier table must yield exactly one ledger key");
                assertEquals(
                        entry.getValue().getModeledBusyIntervalSeconds(),
                        ledger.values().iterator().next().doubleValue(),
                        1e-9,
                        "the one tier's busy must equal the busy union");
                assertEquals(
                        java.util.Arrays.asList(1000.0),
                        new ArrayList<Double>(ledger.keySet()),
                        "the one ledger key is the declared tier mips");
            }
        }
        assertEquals(1, tieredReport.getMetrics().getFleetPerTierBusySeconds().size(), "fleet ledger key set");

        // 4. Energy: bit-identical to the v1 twin platform, and == the hand-derived v1 law.
        assertEquals(
                plain.getEnergyAccounting().getTotalEnergyJoules(),
                tieredReport.getEnergyAccounting().getTotalEnergyJoules(),
                0.0,
                "MIN_POWER on a single-tier platform must conserve EXACTLY the v1 law");
        // Hand-derived v1 law (the [conservation law] of the three legal sources):
        // sum over VMs of idle x end + (max - idle) x busy_vm.
        double hand = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                tieredReport.getMetrics().getVmMetrics().entrySet()) {
            hand += 250.0 * tieredReport.getMakespan()
                    + 150.0 * entry.getValue().getModeledBusyIntervalSeconds();
        }
        assertEquals(
                hand,
                tieredReport.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "manual v1 conservation on the tier's power pair");
        // The twin platform itself also equals that hand-derived value (the same numbers and the
        // same formula on both sides -- the degeneration-identity loop closes).
        assertEquals(
                hand,
                plain.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "manual v1 conservation on the flat twin");

        // 5. Attack falsification: on the single-tier boundary MIN_POWER yields no fake
        //    "slower or cheaper".
        //    (If the degenerate path mis-indexes onto a non-existent "slower tier", energy or clock
        //    must deviate from v1.)
        assertEquals(
                tieredReport.getEnergyAccounting().getPeakPowerWatts(),
                plain.getEnergyAccounting().getPeakPowerWatts(),
                0.0,
                "fleet peak power identical");
        assertEquals(
                tieredReport.getEnergyAccounting().getEnergyMakespanProductJouleSeconds(),
                plain.getEnergyAccounting().getEnergyMakespanProductJouleSeconds(),
                0.0,
                "EDP identical");
    }

    /**
     * Ledger attribution semantics at the busy boundary: missing-tier bookkeeping seconds fall on the
     * default tier (= the only tier of a single-tier table) as a control on a 2-tier platform --
     * MIN_POWER picks the last tier (500) on a 2-tier platform while the bookkeeping seconds still
     * fall on the first tier (2000, the default tier), and the per-tier sum == the busy union (D2's
     * ledger split identity still holds when the policy tier differs from the default tier).
     */
    @Test
    void bookkeepingSecondsFallAtTheDefaultTierWhileComputeFallsAtThePolicyTier() throws Exception {
        PlatformProfile platform = twoTierPlatform();
        SimulationReport report = run(platform, FrequencyPolicy.MIN_POWER).report;
        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully());
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                assertEquals(500.0, job.getTierMips().doubleValue(), 0.0, "MIN_POWER picks the LAST tier");
            }
        }
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            Map<Double, Double> ledger =
                    report.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            if (ledger != null) {
                double sum = 0.0;
                for (double busy : new TreeMap<Double, Double>(ledger).values()) {
                    sum += busy;
                }
                assertEquals(
                        entry.getValue().getModeledBusyIntervalSeconds(),
                        sum,
                        1e-9,
                        "per-tier busy must partition the busy union (default-tier bookkeeping included)");
                // Key set is a subset of {500 (compute), 2000 (default-tier bookkeeping)} --
                // an off-table key is a broken evidence chain.
                for (double keyMips : ledger.keySet()) {
                    assertTrue(
                            keyMips == 500.0 || keyMips == 2000.0,
                            "ledger keys must be the policy tier or the default tier: " + ledger.keySet());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // fixtures (mirroring the fixture discipline of FrequencyTierAssignmentTest:
    // serial chain DAG t1(10s) -> t2(30s))
    // ------------------------------------------------------------------

    private static final class PlatformReportHolder {

        final SimulationReport report;

        PlatformReportHolder(SimulationReport report) {
            this.report = report;
        }
    }

    private PlatformReportHolder run(PlatformProfile platform, FrequencyPolicy policy) throws Exception {
        SimulationConfig.Builder builder = SimulationConfig.builder(chainDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L);
        if (policy != null) {
            builder.frequencyPolicy(policy);
        }
        return new PlatformReportHolder(new SimulationRunner().run(builder.build(), platform));
    }

    /** Single-tier platform: the only tier (1000/250/400) -- tier speed exactly the VM base speed
     * (the common boundary of up- and down-scaling). */
    private static PlatformProfile singleTierPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}});
    }

    /** 2-tier platform: (2000/250/400) (default), (500/210/260) (last). */
    private static PlatformProfile twoTierPlatform() {
        return tierPlatform(new double[][] {{2000.0, 250.0, 400.0}, {500.0, 210.0, 260.0}});
    }

    /** v1 flat-power twin: the same power pair (250/400) as the single-tier platform. */
    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v2-plain-twin");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(0));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(1));
        return builder.build();
    }

    private static PlatformProfile tierPlatform(double[][] tierRows) {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : tierRows) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("v2-tier-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(1, tiers));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVmSpec(int id, List<PlatformProfile.FrequencyTier> tiers) {
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

    private static PlatformProfile.VmSpec plainVmSpec(int id) {
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
                250.0,
                400.0,
                null,
                null);
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
            workflowDax = Paths.get(tempDir.toFile().getAbsolutePath(), "v2-chain.dax");
            Files.write(workflowDax, dax.toString().getBytes(StandardCharsets.UTF_8));
        }
        return workflowDax;
    }

    private static boolean isCompute(SimulationReport.JobOutcome job) {
        return job.getClassType() == ClassType.COMPUTE.value;
    }
}
