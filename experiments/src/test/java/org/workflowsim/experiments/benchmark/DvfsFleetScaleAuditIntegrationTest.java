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

/**
 * BAT-V6 (2026-10-29 test-engineer adversarial round, DVFS fifth round): large-scale audit of a
 * 64-VM multi-tier fleet. It also writes the evidence to
 * {@code experiments/target/dvfs-fleet64/} so that the command-line verify-study-evidence.py can
 * recompute it in full independently; this class completes "write" internally, and the external
 * party completes "audit" in the same round.
 *
 * <p>Three sources for the expected values: (1) hand derivation from the extended conservation law
 * ({@code E_vm = P_idle(default)*T + sum over tiers[(P_max(tier)-P_idle(default))*busy(tier)]});
 * (2) the tier-speed window identity ({@code exec = work/tier-speed}, the BAT-D6 task-window
 * accounting); (3) the ledger partition invariant (per-tier sum == busy union -- the event order
 * decides attribution, independent of the VM count). Attack target: { the D6 tier-speed task-window
 * fix holds at fleet scale } (old accounting: the task window always used the VM declared speed, so
 * a low-tier Job's expected window was off by the ratio); plus the density-independent deterministic
 * attribution (TreeMap ascending keys + VM-ID-order summation) must not be amplified by a 64-machine
 * fleet into a checking gap.</p>
 */
class DvfsFleetScaleAuditIntegrationTest {

    /** Evidence output root (under target, git-ignored; kept after the test for the CLI audit). */
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
        // -- 0. clean evidence root --
        deleteRecursively(EVIDENCE_ROOT);
        Files.createDirectories(EVIDENCE_ROOT);

        // -- 1. fleet run (FCFS online + session-level EDP_HEURISTIC tier policy) --
        SimulationReport first = runOnce(EVIDENCE_ROOT.resolve("inputs/fleet.dax"));
        assertTrue(first.getMetrics().isAllLogicalTasksCompletedSuccessfully(), "fleet run must complete");
        assertEquals(FLEET_SIZE, first.getMetrics().getVmMetrics().size(), "64-VM fleet");

        // -- 2. per-Job tier identity: all within the declared table; tier spread (early and late
        //       Jobs weigh EDP differently) --
        Set<Double> observedTiers = new HashSet<Double>();
        double workMiSpread = 0.0;
        for (SimulationReport.JobOutcome job : first.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "compute job carries a tier");
                assertTrue(
                        tierTableContains(job.getTierMips().doubleValue()),
                        "assigned tier must be declared: " + job.getTierMips());
                observedTiers.add(Double.valueOf(job.getTierMips().doubleValue()));
                // Tier-speed window identity (BAT-D6 accounting, quantization budget of the CloudSim
                // 0.1 s tick): exec <= work/tier-speed + 0.11 (real execution is also rounded by the
                // tick, so it is >= work/tier-speed - tick).
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
        // The early/late weighting tension must produce a genuinely multi-tier run (the
        // observation premise of the EDP accounting); otherwise the partition/conservation
        // assertions below degenerate into "trivially true on a single tier" -- at least two tiers
        // must be observed.
        assertTrue(
                observedTiers.size() >= 2,
                "an EDP_HEURISTIC run over spread work sizes and clocks must observe at least two"
                        + " distinct tiers (observed: " + observedTiers + ")");

        // -- 3. ledger partition invariant (the core D6-fix assertion at fleet scale) --
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
        // Fleet aggregate == sum over VMs (deterministic summation order, independent of the VM count).
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

        // -- 4. hand-derived conservation law (source 3) == reported energy --
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

        // -- 5. deterministic replay (decided by the event order, not by the VM count): the ledger
        //       and the energy of a second run with the same seed agree bit for bit --
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

        // -- 6. evidence write-out (the input surface for the external CLI audit) --
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(first, EVIDENCE_ROOT.resolve("runs/fleet64"), "result");
        org.workflowsim.experiment.ExperimentArtifactValidator.validate(artifacts.getManifest());
        assertTrue(
                Files.exists(EVIDENCE_ROOT.resolve("runs/fleet64/result.manifest.json")),
                "the auditable manifest must exist for the CLI audit");
    }

    // ------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------

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

    /** A 4-layer x width layered DAG (runtime 1..5 s reference throughput -> 1000..5000 MI work spectrum). */
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

    /** 64-VM/64-Host fleet, each VM at base speed 1000 with 3 tiers (top-3 of the frozen baseline table). */
    private static PlatformProfile fleet64() {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : FLEET_TIERS) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        assertEquals(3, tiers.size(), "top-3 rows of the frozen table");
        // Mirrors the semantics of the first three rows of BenchmarkExecutor.benchmarkDvfsTiers()
        // (re-declared independently; the expectation is copied from the frozen plan constants, not
        // from the run output).
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

    /** Declared work in MI: the source task lengthMi of a single-task Job (runtime x 1000 reference throughput). */
    private static double declaredWorkMi(SimulationReport report, SimulationReport.JobOutcome job) {
        int taskId = job.getTaskIds().get(0).intValue();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId) {
                // lengthMi is the declared (pre-scaling) work; effective is the CloudSim length after
                // tier-speed scaling.
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
