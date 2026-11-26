package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

class FrequencyTierAssignmentTest {

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

    @Test
    void defaultPolicyAssignsFirstTierWithPerTierLedgerPartition() throws Exception {
        PlatformProfile platform = threeTierPlatform();
        SimulationReport report = run(platform, FrequencyPolicy.DEFAULT);

        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully());
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "compute job must carry its assigned tier");
                assertEquals(2000.0, job.getTierMips().doubleValue(), 0.0, "DEFAULT = FIRST tier");
            } else {
                assertNull(job.getTierMips(), "non-compute attempts must stay v1-null (bookkeeping)");
            }
        }

        double fleetSumsFromLedger = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            double busyUnion = entry.getValue().getModeledBusyIntervalSeconds();
            Map<Double, Double> tiers =
                    report.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            if (busyUnion > 0.0) {
                assertNotNull(tiers, "busy VM must have a per-tier ledger entry");
            }
            double ledgerSum = 0.0;
            if (tiers != null) {
                for (double busy : tiers.values()) {
                    ledgerSum += busy;
                }
            }
            assertEquals(busyUnion, ledgerSum, 1e-9, "per-tier busy must partition the busy union");
            fleetSumsFromLedger += ledgerSum;
        }
        double fleetFromFleetMap = 0.0;
        for (double busy : report.getMetrics().getFleetPerTierBusySeconds().values()) {
            fleetFromFleetMap += busy;
        }
        assertEquals(
                fleetSumsFromLedger,
                fleetFromFleetMap,
                1e-9,
                "fleet per-tier aggregate must equal the per-VM ledger sums");

        ManualEnergy hand = handConservation(report, platform);
        assertEquals(
                hand.perTierTotal,
                report.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "per-tier conservation law hand recompute");

        assertEquals(
                hand.allAtDefaultTotal,
                hand.perTierTotal,
                1e-6,
                "all jobs at the DEFAULT tier: per-tier and all-at-default accountings agree");
        assertNotEquals(
                0.0, report.getEnergyAccounting().getTotalEnergyJoules(), "tiered platform must be" + " powered");
    }

    @Test
    void minPowerRunsAtLowestTierTakesNoticeablyLongerAndSplitsConservation() throws Exception {
        PlatformProfile platform = threeTierPlatform();
        SimulationReport min = run(platform, FrequencyPolicy.MIN_POWER);
        SimulationReport def = run(platform, FrequencyPolicy.DEFAULT);

        assertTrue(min.getMetrics().isWorkflowCompletedSuccessfully());
        for (SimulationReport.JobOutcome job : min.getJobs()) {
            if (isCompute(job)) {
                assertEquals(500.0, job.getTierMips().doubleValue(), 0.0, "MIN_POWER = LAST tier");

                double expectedExec = job.getTaskIds().contains(Integer.valueOf(1)) ? 20.0 : 60.0;
                assertEquals(
                        expectedExec,
                        job.getExecutionTime(),
                        0.11,
                        "declared work at the 500 MIPS tier must double the runtime");
            }
        }
        assertTrue(
                min.getMakespan() > 3.0 * def.getMakespan(),
                "MIN_POWER (0.5x speed) must have a clearly larger makespan than DEFAULT (2x speed): min="
                        + min.getMakespan() + " default=" + def.getMakespan());

        for (Map<Double, Double> tiers :
                min.getMetrics().getPerTierBusySecondsByVm().values()) {
            for (double keyMips : tiers.keySet()) {
                assertTrue(
                        keyMips == 500.0 || keyMips == 2000.0,
                        "MIN_POWER ledger keys must be the last tier (compute) or the default tier " + "(bookkeeping): "
                                + tiers);
            }
        }

        Map<Integer, Double> computeBusyPerVm = new java.util.LinkedHashMap<Integer, Double>();
        for (SimulationReport.JobOutcome job : min.getJobs()) {
            if (isCompute(job)) {
                computeBusyPerVm.merge(
                        Integer.valueOf(job.getVmId()), Double.valueOf(job.getExecutionTime()), Double::sum);
            }
        }
        for (Map.Entry<Integer, Map<Double, Double>> perVm :
                min.getMetrics().getPerTierBusySecondsByVm().entrySet()) {
            double lastTierBusy = perVm.getValue().getOrDefault(Double.valueOf(500.0), Double.valueOf(0.0));
            assertEquals(
                    computeBusyPerVm.getOrDefault(perVm.getKey(), Double.valueOf(0.0)),
                    lastTierBusy,
                    1e-9,
                    "last-tier busy must equal compute-job execution seconds on VM " + perVm.getKey());
        }

        ManualEnergy hand = handConservation(min, platform);
        assertEquals(
                hand.perTierTotal,
                min.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "per-tier conservation law hand recompute (all busy at 500)");
        assertTrue(
                Math.abs(hand.allAtDefaultTotal - hand.perTierTotal) > 1.0,
                "any busy second at a non-default tier must change the total: per-tier (" + hand.perTierTotal
                        + ") vs all-at-default (" + hand.allAtDefaultTotal + ")");
        assertNotEquals(0.0, min.getEnergyAccounting().getTotalEnergyJoules(), "tiered platform must be powered");
    }

    @Test
    void edpHeuristicPicksHandComputedMiddleTierOnPowerAsymmetricTable() throws Exception {
        PlatformProfile platform = edpAsymmetricTierPlatform();
        SimulationReport edp = run(platform, FrequencyPolicy.EDP_HEURISTIC);

        assertTrue(edp.getMetrics().isWorkflowCompletedSuccessfully(), "EDP run must complete");
        List<Double> declaredTiers = new ArrayList<Double>();
        for (PlatformProfile.FrequencyTier tier : platform.getVms().get(0).getFrequencyTiers()) {
            declaredTiers.add(Double.valueOf(tier.getMips()));
        }
        for (SimulationReport.JobOutcome job : edp.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "EDP must assign a tier to every compute job");
                assertTrue(
                        declaredTiers.contains(job.getTierMips()),
                        "assigned tier must come from the declared set: " + job.getTierMips());
                assertEquals(1000.0, job.getTierMips().doubleValue(), 0.0, "hand-computed EDP winner");
            }
        }

        assertEquals(2000.0, firstComputeTier(run(platform, FrequencyPolicy.DEFAULT)), 0.0, "DEFAULT picks first");
        assertEquals(500.0, firstComputeTier(run(platform, FrequencyPolicy.MIN_POWER)), 0.0, "MIN_POWER picks last");
        assertEquals(1000.0, firstComputeTier(edp), 0.0, "EDP_HEURISTIC picks the hand-computed middle");
    }

    @Test
    void edpHeuristicUsesIdleOnlyOutsideLocalExecution() throws Exception {
        PlatformProfile platform = tierPlatform(new double[][] {{2000.0, 250.0, 4000.0}, {1000.0, 0.0, 875.0}});
        SimulationReport edp = run(platform, FrequencyPolicy.EDP_HEURISTIC);

        assertTrue(edp.getMetrics().isWorkflowCompletedSuccessfully(), "EDP run must complete");
        for (SimulationReport.JobOutcome job : edp.getJobs()) {
            if (isCompute(job)) {
                assertEquals(
                        1000.0,
                        job.getTierMips().doubleValue(),
                        0.0,
                        "conservation-consistent local EDP must select the hand-derived slower tier");
            }
        }
    }

    @Test
    void tieredPeakPowerUsesMaximumAcrossDeclaredTiers() throws Exception {
        PlatformProfile platform = tierPlatform(new double[][] {{2000.0, 250.0, 400.0}, {1000.0, 220.0, 900.0}});
        SimulationReport report = run(platform, FrequencyPolicy.MIN_POWER);

        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully(), "tiered run must complete");
        assertEquals(
                1800.0,
                report.getEnergyAccounting().getPeakPowerWatts(),
                0.0,
                "two VMs times the hand-derived maximum declared tier power of 900 W");
    }

    @Test
    void unsetPolicyBehavesExactlyLikeExplicitDefault() throws Exception {
        PlatformProfile platform = threeTierPlatform();
        SimulationReport unset = run(platform, null);
        SimulationReport explicit = run(platform, FrequencyPolicy.DEFAULT);

        assertEquals(unset.getMakespan(), explicit.getMakespan(), 0.0, "same tier decisions => same clock");
        List<Double> unsetTiers = computeTierMultiset(unset);
        List<Double> explicitTiers = computeTierMultiset(explicit);
        assertEquals(explicitTiers, unsetTiers, "unset policy must assign the DEFAULT (first) tier");
        assertEquals(
                unset.getEnergyAccounting().getTotalEnergyJoules(),
                explicit.getEnergyAccounting().getTotalEnergyJoules(),
                0.0,
                "same accountings");
    }

    @Test
    void singleTierPerVmPlatformDegeneratesToV1Energy() throws Exception {
        SimulationReport tiered = run(singleTierPlatform(), null);
        SimulationReport plain = run(plainPoweredPlatform(), null);

        assertTrue(tiered.getMetrics().isWorkflowCompletedSuccessfully());
        assertTrue(plain.getMetrics().isWorkflowCompletedSuccessfully());
        assertEquals(plain.getMakespan(), tiered.getMakespan(), 0.0, "scale factor 1.0 keeps the clock identical");
        assertEquals(
                0,
                Double.compare(
                        plain.getEnergyAccounting().getTotalEnergyJoules(),
                        tiered.getEnergyAccounting().getTotalEnergyJoules()),
                "single-tier platform must conserve EXACTLY like the v1 plain-power platform");
        for (SimulationReport.JobOutcome job : tiered.getJobs()) {
            if (isCompute(job)) {
                assertEquals(1000.0, job.getTierMips().doubleValue(), 0.0, "the single declared tier");
            }
        }
        for (SimulationReport.JobOutcome job : plain.getJobs()) {
            if (isCompute(job)) {
                assertNull(job.getTierMips(), "v1 plain platform carries no tier evidence");
            }
        }

        for (Map<Double, Double> tiers :
                tiered.getMetrics().getPerTierBusySecondsByVm().values()) {
            assertEquals(1, tiers.size(), "single tier platform ledger");
        }
    }

    @Test
    void manifestCarriesTierMipsOnlyWhenAssigned() throws Exception {
        SimulationReport tiered = run(threeTierPlatform(), FrequencyPolicy.MIN_POWER);
        Map<String, Object> tieredManifest =
                ExperimentManifestWriter.toManifest(tiered, new ArrayList<Map<String, Object>>(), null);
        Object tieredJobs = ((Map<?, ?>) tieredManifest.get("result")).get("jobs");
        assertTrue(tieredJobs instanceof List, "view mode produces a list");
        int tieredComputeWithKey = 0;
        for (Object entry : (List<?>) tieredJobs) {
            com.google.gson.JsonObject job = (com.google.gson.JsonObject) entry;
            boolean compute = job.get("classType").getAsInt() == ClassType.COMPUTE.value;
            assertEquals(compute, job.has("tierMips"), "tierMips appears exactly for compute attempts: " + job);
            if (compute) {
                assertEquals(500.0, job.get("tierMips").getAsDouble(), 0.0, "MIN_POWER evidence");
                tieredComputeWithKey++;
            }
        }
        assertTrue(tieredComputeWithKey >= 2, "chain DAG must have at least two compute attempts");

        SimulationReport plain = run(plainPoweredPlatform(), null);
        Map<String, Object> plainManifest =
                ExperimentManifestWriter.toManifest(plain, new ArrayList<Map<String, Object>>(), null);
        Object plainJobs = ((Map<?, ?>) plainManifest.get("result")).get("jobs");
        assertSame(plain.getJobs(), plainJobs, "v1 runs keep the lazy reflection path (byte-identical manifest)");
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(plainManifest);
        assertFalse(
                serialized.contains("tierMips"),
                "v1 manifests must never carry a tierMips key (null = absent = v1 legacy)");
    }

    @Test
    void manifestPlatformSerializesPerVmFrequencyTiers() throws Exception {
        SimulationReport tiered = run(threeTierPlatform(), FrequencyPolicy.MIN_POWER);
        Map<String, Object> tieredManifest =
                ExperimentManifestWriter.toManifest(tiered, new ArrayList<Map<String, Object>>(), null);
        List<?> tieredVms = (List<?>) ((Map<?, ?>) tieredManifest.get("platform")).get("vms");
        assertEquals(2, tieredVms.size(), "fixture platform declares two VMs");
        for (Object entry : tieredVms) {
            Map<?, ?> vm = (Map<?, ?>) entry;
            assertTrue(vm.containsKey("frequencyTiers"), "tiered VM must carry its frequencyTiers table: " + vm);
            List<?> tiers = (List<?>) vm.get("frequencyTiers");
            assertEquals(3, tiers.size(), "three-tier platform must serialize three tiers");
            double[][] declared = {{2000.0, 250.0, 400.0}, {1000.0, 220.0, 280.0}, {500.0, 210.0, 260.0}};
            for (int index = 0; index < tiers.size(); index++) {
                Map<?, ?> tier = (Map<?, ?>) tiers.get(index);
                assertTrue(
                        tier.containsKey("mips")
                                && tier.containsKey("idlePowerWatts")
                                && tier.containsKey("maxPowerWatts"),
                        "tier triple must expose mips/idlePowerWatts/maxPowerWatts: " + tier);
                assertEquals(declared[index][0], ((Number) tier.get("mips")).doubleValue(), 0.0, "tier mips");
                assertEquals(
                        declared[index][1],
                        ((Number) tier.get("idlePowerWatts")).doubleValue(),
                        0.0,
                        "tier idlePowerWatts");
                assertEquals(
                        declared[index][2],
                        ((Number) tier.get("maxPowerWatts")).doubleValue(),
                        0.0,
                        "tier maxPowerWatts");
            }

            assertEquals(
                    0.0,
                    ((Number) vm.get("idlePowerWatts")).doubleValue(),
                    0.0,
                    "tiered VM keeps its flat power fields at zero (one source of truth)");
            assertEquals(
                    0.0,
                    ((Number) vm.get("maxPowerWatts")).doubleValue(),
                    0.0,
                    "tiered VM keeps its flat power fields at zero (one source of truth)");
        }

        SimulationReport plain = run(plainPoweredPlatform(), null);
        Map<String, Object> plainManifest =
                ExperimentManifestWriter.toManifest(plain, new ArrayList<Map<String, Object>>(), null);
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(plainManifest);
        assertFalse(
                serialized.contains("frequencyTiers"),
                "v1 plain-power manifests must NOT carry a frequencyTiers key (empty table = undeclared = v1 legacy)");
    }

    @Test
    void nonDefaultPolicyOnPlainPlatformFailsFast() throws Exception {
        SimulationConfigurationException rejection = assertThrows(
                SimulationConfigurationException.class,
                () -> run(plainPoweredPlatform(), FrequencyPolicy.MIN_POWER),
                "an inert policy must be rejected, not silently ignored");
        assertTrue(
                rejection.getMessage().contains("frequencyTiers"),
                "rejection must cite the frequencyTiers clause: " + rejection.getMessage());
    }

    private static PlatformProfile threeTierPlatform() {
        return tierPlatform(new double[][] {{2000.0, 250.0, 400.0}, {1000.0, 220.0, 280.0}, {500.0, 210.0, 260.0}});
    }

    private static PlatformProfile edpAsymmetricTierPlatform() {
        return tierPlatform(new double[][] {{2000.0, 250.0, 4000.0}, {1000.0, 220.0, 280.0}, {500.0, 210.0, 260.0}});
    }

    private static PlatformProfile tierPlatform(double[][] tierRows) {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : tierRows) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-tier-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(1, tiers));
        return builder.build();
    }

    private static PlatformProfile singleTierPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}});
    }

    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-plain-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(0));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(1));
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
            workflowDax = Paths.get(tempDir.toFile().getAbsolutePath(), "dvfs-chain.dax");
            Files.write(workflowDax, dax.toString().getBytes(StandardCharsets.UTF_8));
        }
        return workflowDax;
    }

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

    private static boolean isCompute(SimulationReport.JobOutcome job) {
        return job.getClassType() == ClassType.COMPUTE.value;
    }

    private static double firstComputeTier(SimulationReport report) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                return job.getTierMips().doubleValue();
            }
        }
        throw new IllegalStateException("run has no compute job");
    }

    private static List<Double> computeTierMultiset(SimulationReport report) {
        List<Double> tiers = new ArrayList<Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                tiers.add(job.getTierMips());
            }
        }
        return tiers;
    }

    private static final class ManualEnergy {
        final double perTierTotal;
        final double allAtDefaultTotal;

        ManualEnergy(double perTierTotal, double allAtDefaultTotal) {
            this.perTierTotal = perTierTotal;
            this.allAtDefaultTotal = allAtDefaultTotal;
        }
    }

    private static ManualEnergy handConservation(SimulationReport report, PlatformProfile platform) {
        double makespan = report.getMakespan();
        Map<Integer, Map<Double, Double>> ledger = report.getMetrics().getPerTierBusySecondsByVm();
        double perTierTotal = 0.0;
        double allAtDefaultTotal = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            PlatformProfile.VmSpec spec = specOf(platform, entry.getKey());
            PlatformProfile.FrequencyTier defaultTier = spec.defaultFrequencyTier();
            double busyUnion = entry.getValue().getModeledBusyIntervalSeconds();
            double ePerTier = defaultTier.getIdlePowerWatts() * makespan;
            Map<Double, Double> tiers = ledger.get(entry.getKey());
            if (tiers != null) {
                for (Map.Entry<Double, Double> tier : new TreeMap<Double, Double>(tiers).entrySet()) {
                    ePerTier += (maxOfTier(spec, tier.getKey()) - defaultTier.getIdlePowerWatts()) * tier.getValue();
                }
            }
            perTierTotal += ePerTier;
            allAtDefaultTotal += defaultTier.getIdlePowerWatts() * makespan
                    + (defaultTier.getMaxPowerWatts() - defaultTier.getIdlePowerWatts()) * busyUnion;
        }
        return new ManualEnergy(perTierTotal, allAtDefaultTotal);
    }

    private static PlatformProfile.VmSpec specOf(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        throw new IllegalStateException("VM " + vmId + " not in test platform");
    }

    private static double maxOfTier(PlatformProfile.VmSpec spec, double mips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (tier.getMips() == mips) {
                return tier.getMaxPowerWatts();
            }
        }
        throw new IllegalStateException("ledger tier " + mips + " not declared by VM " + spec.getId());
    }
}
