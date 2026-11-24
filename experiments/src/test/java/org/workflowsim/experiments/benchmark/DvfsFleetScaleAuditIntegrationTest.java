package org.workflowsim.experiments.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationMetrics;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

class DvfsFleetScaleAuditIntegrationTest {

    private static final Path EVIDENCE_ROOT = Paths.get("target").resolve("dvfs-fleet64");

    private static final int FLEET_SIZE = 64;

    private static final double[][] FLEET_TIERS = {
        {2000.0, 250.0, 4000.0}, {1000.0, 250.0, 400.0}, {500.0, 100.0, 118.75},
    };

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
    void fleet64ThreeTierEdpRunIsLedgerExactAndWritesAuditableEvidence() throws Exception {

        deleteRecursively(EVIDENCE_ROOT);
        Files.createDirectories(EVIDENCE_ROOT);

        SimulationReport first = runOnce(EVIDENCE_ROOT.resolve("inputs/fleet.dax"));
        assertTrue(first.getMetrics().isAllLogicalTasksCompletedSuccessfully(), "fleet run must complete");
        assertEquals(FLEET_SIZE, first.getMetrics().getVmMetrics().size(), "64-VM fleet");

        Set<Double> observedTiers = new HashSet<Double>();
        double workMiSpread = 0.0;
        for (SimulationReport.JobOutcome job : first.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "compute job carries a tier");
                assertTrue(
                        tierTableContains(job.getTierMips().doubleValue()),
                        "assigned tier must be declared: " + job.getTierMips());
                observedTiers.add(Double.valueOf(job.getTierMips().doubleValue()));

                double declaredWorkMi = declaredWorkMi(first, job);
                workMiSpread = Math.max(workMiSpread, declaredWorkMi);
                double tierSpeed = job.getTierMips().doubleValue();
                double expectedExec = declaredWorkMi / tierSpeed;
                assertTrue(
                        job.getExecutionTime() <= expectedExec + 0.11,
                        "task window must not EXCEED work/tier-speed (+tick): job " + job.getJobId() + " exec="
                                + job.getExecutionTime() + " expected=" + expectedExec);
                assertTrue(
                        job.getExecutionTime() >= expectedExec - 0.11,
                        "task window must not MISS work/tier-speed (-tick): job " + job.getJobId() + " exec="
                                + job.getExecutionTime() + " expected=" + expectedExec);
            } else {
                assertNull(job.getTierMips(), "bookkeeping envelopes stay v1-null");
            }
        }

        // FGCS-R5 (2026-11-24): local EDP is Pmax * T^2, independent of the dispatch clock and of the
        // work size (T scales linearly with MI). Hand derivation for this table: 4000 / 2000^2 = 1e-3,
        // 400 / 1000^2 = 4e-4, 118.75 / 500^2 = 4.75e-4 per MI^2, so every Job takes the 1000 tier.
        // The former clock-dependent tier mix was an artifact of charging elapsed idle time.
        assertEquals(
                java.util.Collections.singleton(Double.valueOf(1000.0)),
                observedTiers,
                "clock-invariant local EDP selects the hand-derived 1000 tier for every Job");

        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                first.getMetrics().getVmMetrics().entrySet()) {
            Map<Double, Double> ledger =
                    first.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            double busyUnion = entry.getValue().getModeledBusyIntervalSeconds();
            if (busyUnion <= 0.0) {
                assertTrue(ledger == null || ledger.isEmpty(), "idle VM carries no ledger");
                continue;
            }
            assertNotNull(ledger, "busy VM " + entry.getKey() + " must carry its per-tier ledger");
            double sum = 0.0;
            for (double busy : new TreeMap<Double, Double>(ledger).values()) {
                sum += busy;
            }
            assertEquals(
                    busyUnion,
                    sum,
                    1e-9,
                    "per-tier busy must partition the busy union on VM " + entry.getKey() + ": " + ledger);
            for (double keyMips : ledger.keySet()) {
                assertTrue(tierTableContains(keyMips), "ledger keys must be declared tiers: " + ledger.keySet());
            }
        }

        double fleetFromVms = 0.0;
        for (Map<Double, Double> perVm :
                first.getMetrics().getPerTierBusySecondsByVm().values()) {
            for (double busy : perVm.values()) {
                fleetFromVms += busy;
            }
        }
        double fleetFromFleetMap = 0.0;
        for (double busy : first.getMetrics().getFleetPerTierBusySeconds().values()) {
            fleetFromFleetMap += busy;
        }
        assertEquals(fleetFromVms, fleetFromFleetMap, 1e-6, "fleet aggregate equals the per-VM sums at 64 VMs");

        double handPerTier = 0.0;
        double handAllAtDefault = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                first.getMetrics().getVmMetrics().entrySet()) {
            PlatformProfile.VmSpec spec = specOf(first.getPlatform(), entry.getKey());
            double defaultIdle = spec.defaultFrequencyTier().getIdlePowerWatts();
            double defaultMax = spec.defaultFrequencyTier().getMaxPowerWatts();
            handPerTier += defaultIdle * first.getMakespan();
            handAllAtDefault += defaultIdle * first.getMakespan()
                    + (defaultMax - defaultIdle) * entry.getValue().getModeledBusyIntervalSeconds();
            Map<Double, Double> ledger =
                    first.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            if (ledger != null) {
                for (Map.Entry<Double, Double> tier : new TreeMap<Double, Double>(ledger).entrySet()) {
                    handPerTier += (maxPowerOfTier(spec, tier.getKey()) - defaultIdle)
                            * tier.getValue().doubleValue();
                }
            }
        }
        assertEquals(
                handPerTier,
                first.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "per-tier conservation law hand recompute at fleet scale");
        assertTrue(
                Math.abs(handAllAtDefault - handPerTier) > 1.0,
                "multi-tier runs must differ from the all-at-default accounting: per-tier=" + handPerTier
                        + " all-at-default=" + handAllAtDefault);

        SimulationReport second = runOnce(EVIDENCE_ROOT.resolve("inputs/fleet.dax"));
        assertEquals(
                first.getMakespan(),
                second.getMakespan(),
                0.0,
                "same seed must replay the identical clock at fleet scale");
        assertEquals(
                first.getEnergyAccounting().getTotalEnergyJoules(),
                second.getEnergyAccounting().getTotalEnergyJoules(),
                0.0,
                "fleet energy must replay bit-exactly");
        assertEquals(
                first.getMetrics().getFleetPerTierBusySeconds(),
                second.getMetrics().getFleetPerTierBusySeconds(),
                "the fleet per-tier ledger must replay identically (event-order determinism, not VM-count)");

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(first, EVIDENCE_ROOT.resolve("runs/fleet64"), "result");
        org.workflowsim.experiment.ExperimentArtifactValidator.validate(artifacts.getManifest());
        assertTrue(
                Files.exists(EVIDENCE_ROOT.resolve("runs/fleet64/result.manifest.json")),
                "the auditable manifest must exist for the CLI audit");
    }

    private SimulationReport runOnce(Path dax) throws Exception {
        if (!Files.exists(dax)) {
            Files.createDirectories(dax.getParent());
            Files.write(dax, layeredDag(8, 5).getBytes(StandardCharsets.UTF_8));
        }
        SimulationConfig config = SimulationConfig.builder(dax.toString(), FLEET_SIZE)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .frequencyPolicy(FrequencyPolicy.EDP_HEURISTIC)
                .build();
        return new SimulationRunner().run(config, fleet64());
    }

    private static String layeredDag(int width, int layers) {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"").append(width * layers).append("\">\n");
        for (int layer = 0; layer < layers; layer++) {
            for (int column = 0; column < width; column++) {
                dax.append("  <job id=\"t")
                        .append(layer * width + column)
                        .append("\" name=\"layer")
                        .append(layer)
                        .append("\" runtime=\"")
                        .append(1 + column % 5)
                        .append("\"/>\n");
            }
        }
        for (int layer = 1; layer < layers; layer++) {
            for (int column = 0; column < width; column++) {
                dax.append("  <child ref=\"t")
                        .append(layer * width + column)
                        .append("\"><parent ref=\"t")
                        .append((layer - 1) * width + column)
                        .append("\"/><parent ref=\"t")
                        .append((layer - 1) * width + (column + width - 1) % width)
                        .append("\"/></child>\n");
            }
        }
        dax.append("</adag>\n");
        return dax.toString();
    }

    private static PlatformProfile fleet64() {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : FLEET_TIERS) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        assertEquals(3, tiers.size(), "top-3 rows of the frozen table");

        List<PlatformProfile.FrequencyTier> frozen = BenchmarkExecutor.benchmarkDvfsTiers();
        List<PlatformProfile.FrequencyTier> frozenTop = frozen.subList(0, Math.min(3, frozen.size()));
        for (int i = 0; i < frozenTop.size(); i++) {
            assertEquals(frozenTop.get(i).getMips(), tiers.get(i).getMips(), 0.0, "frozen tier table row " + i);
            assertEquals(
                    frozenTop.get(i).getMaxPowerWatts(),
                    tiers.get(i).getMaxPowerWatts(),
                    0.0,
                    "frozen tier table row " + i);
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("v6-fleet64");
        for (int i = 0; i < FLEET_SIZE; i++) {
            builder.addHost(new PlatformProfile.HostSpec(i, 2, 2000.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    i,
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
                    tiers));
            builder.pinVmToHost(i, i);
        }
        return builder.build();
    }

    private static boolean tierTableContains(double mips) {
        for (double[] row : FLEET_TIERS) {
            if (row[0] == mips) {
                return true;
            }
        }
        return false;
    }

    private static double maxPowerOfTier(PlatformProfile.VmSpec spec, double mips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (tier.getMips() == mips) {
                return tier.getMaxPowerWatts();
            }
        }
        throw new IllegalStateException("tier " + mips + " not declared by VM " + spec.getId());
    }

    private static PlatformProfile.VmSpec specOf(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        throw new IllegalStateException("VM " + vmId + " not in fleet");
    }

    private static double declaredWorkMi(SimulationReport report, SimulationReport.JobOutcome job) {
        int taskId = job.getTaskIds().get(0).intValue();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId) {

                return task.getLengthMi();
            }
        }
        throw new IllegalStateException("task " + taskId + " of job " + job.getJobId() + " not found");
    }

    private static boolean isCompute(SimulationReport.JobOutcome job) {
        return job.getClassType() == ClassType.COMPUTE.value;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (java.io.IOException error) {
                    throw new RuntimeException("cannot clean " + path, error);
                }
            });
        }
    }
}
