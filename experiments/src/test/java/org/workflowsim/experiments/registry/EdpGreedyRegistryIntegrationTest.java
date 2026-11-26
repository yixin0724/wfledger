package org.workflowsim.experiments.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.experiments.benchmark.BenchmarkExecutor;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class EdpGreedyRegistryIntegrationTest {

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

    @Test
    void registryAcceptanceChainRejectsUnpoweredAndTierlessPlatforms() {
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(EdpGreedySchedulerProvider.LABEL);
        assertSame(EdpGreedySchedulerProvider.class, provider.getClass(), "SPI discovery of EDP_GREEDY");

        assertTrue(provider.declaration().isDvfsAware(), "dvfsAware declaration");
        assertTrue(provider.declaration().isFrequencyTiersRequired(), "frequencyTiersRequired declaration");
        assertTrue(provider.declaration().isPowerModelRequired(), "powerModelRequired declaration — full chain");
        assertTrue(
                provider.declaration().isFrequencyTiersRequired()
                                == provider.declaration().isDvfsAware()
                        && provider.declaration().isFrequencyTiersRequired(),
                "frequencyTiersRequired ⇒ dvfsAware lattice");
        assertTrue(provider.declaration().isEnergyAware(), "powerModelRequired ⇒ energyAware lattice (BAT-E3)");

        IllegalArgumentException unpowered = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        EdpGreedySchedulerProvider.LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                null,
                                PlanningAlgorithm.INVALID,
                                false,
                                false)),
                "powerModelRequired must reject an unpowered platform");
        assertTrue(
                unpowered.getMessage().contains("powerModelRequired"),
                "rejection must cite the power clause: " + unpowered.getMessage());

        IllegalArgumentException tierless = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        EdpGreedySchedulerProvider.LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                null,
                                PlanningAlgorithm.INVALID,
                                true,
                                false)),
                "frequencyTiersRequired must reject a tierless platform");
        assertTrue(
                tierless.getMessage().contains("frequencyTiersRequired"),
                "rejection must cite the frequency-tiers clause: " + tierless.getMessage());

        AlgorithmRegistry.validate(
                EdpGreedySchedulerProvider.LABEL,
                new AlgorithmRegistry.ValidationInput(
                        ReplicaCatalog.FileSystem.LOCAL,
                        DataMovementModel.legacyWorkflowsimV1(),
                        false,
                        null,
                        PlanningAlgorithm.INVALID,
                        true,
                        true));
    }

    @Test
    void edpGreedyRunCarriesPerJobTierEvidenceAndAuditsClean() throws Exception {
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(EdpGreedySchedulerProvider.LABEL);
        Path dax = bagDax();
        PlatformProfile platform = threeTierCarbonFleet();

        SimulationConfig config = SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT)
                .registeredSchedulingAlgorithm(provider)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform);
        assertEquals(
                "COMPLETED_SUCCESSFULLY",
                report.getMetrics().getLogicalTaskCompletionStatus(),
                "the DVFS-aware arm must complete the workflow");

        Map<Integer, Double> declaredWorkByTaskPosition = new LinkedHashMap<Integer, Double>();
        double[] runtimeSeconds = {40, 30, 95, 35};
        for (int i = 0; i < runtimeSeconds.length; i++) {
            declaredWorkByTaskPosition.put(Integer.valueOf(i), Double.valueOf(runtimeSeconds[i] * 1000.0));
        }
        List<Integer> computeTaskIdsAscending = new ArrayList<Integer>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                assertTrue(job.getTierMips() != null, "every compute job gets a tier at admission");
                assertTrue(!job.getTaskIds().isEmpty(), "one task per job in the NONE-clustering bag");
                computeTaskIdsAscending.add(job.getTaskIds().get(0));
            }
        }
        assertEquals(4, computeTaskIdsAscending.size(), "the bag's four compute jobs");
        java.util.Collections.sort(computeTaskIdsAscending);
        Map<Integer, Double> tierByTaskId = new LinkedHashMap<Integer, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                tierByTaskId.put(job.getTaskIds().get(0), job.getTierMips());
            }
        }
        for (Map.Entry<Integer, Double> position : declaredWorkByTaskPosition.entrySet()) {
            int taskId = computeTaskIdsAscending.get(position.getKey().intValue());
            double declaredWork = position.getValue().doubleValue();
            double expectedTier = declaredWork >= EdpGreedySchedulerProvider.LONG_JOB_THRESHOLD_MI ? 500.0 : 2000.0;
            assertEquals(
                    Double.valueOf(expectedTier),
                    tierByTaskId.get(Integer.valueOf(taskId)),
                    "length-based split on task position " + position.getKey() + " (declared work " + declaredWork
                            + " MI): long ⇒ last tier, short ⇒ first tier");
        }

        assertTrue(report.getEnergyAccounting().getTotalEnergyJoules() > 0.0, "tiered fleet is powered");
        assertTrue(report.getCarbonAccounting() != null, "declared intensity produces carbon accounting");
        assertTrue(report.getCarbonAccounting().getTotalCarbonGrams() > 0.0, "single-joule fleet carries grams");

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, tempDir.resolve("edp-greedy-evidence"), "result");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray fleetTiers = manifest.getAsJsonObject("platform")
                .getAsJsonArray("vms")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("frequencyTiers");
        assertEquals(3, fleetTiers.size(), "the 3-tier powered fleet serializes its table");
        assertEquals(2000.0, fleetTiers.get(0).getAsJsonObject().get("mips").getAsDouble(), 0.0, "tier 1 mips");
        assertEquals(500.0, fleetTiers.get(2).getAsJsonObject().get("mips").getAsDouble(), 0.0, "tier 3 mips");
        int longTierJobs = 0;
        int shortTierJobs = 0;
        for (JsonElement element : manifest.getAsJsonObject("result").getAsJsonArray("jobs")) {
            JsonObject job = element.getAsJsonObject();
            if (job.get("classType").getAsInt() == ClassType.COMPUTE.value) {
                assertTrue(job.has("tierMips"), "compute jobs serialize tierMips (evidence face)");
                if (job.get("tierMips").getAsDouble() == 500.0) {
                    longTierJobs++;
                } else if (job.get("tierMips").getAsDouble() == 2000.0) {
                    shortTierJobs++;
                }
            }
        }
        assertEquals(1, longTierJobs, "exactly the longest task (95000 MI) runs at the last tier");
        assertEquals(3, shortTierJobs, "the three short tasks run at the default tier");
        assertEquals(
                "DEFAULT",
                manifest.getAsJsonObject("configuration").get("frequencyPolicy").getAsString(),
                "the session policy stays DEFAULT — the mix is the algorithm's per-Job decision");
        assertEquals(
                EdpGreedySchedulerProvider.LABEL,
                manifest.getAsJsonObject("configuration")
                        .get("registeredSchedulingAlgorithm")
                        .getAsString(),
                "the registered label serializes");
        assertTrue(
                manifest.getAsJsonObject("energyMetrics")
                                .get("totalEnergyJoules")
                                .getAsDouble()
                        > 0.0,
                "energyMetrics present and positive on the tiered fleet");
        assertTrue(
                manifest.getAsJsonObject("carbonMetrics")
                                .get("totalCarbonGrams")
                                .getAsDouble()
                        > 0.0,
                "carbonMetrics rides the per-tier joules at intensity 380");

        assumePythonAuditorPasses(artifacts.getManifest());
    }

    @Test
    void fcfsVsEdpGreedyAltersTierMixOnTheSameDagAndPlatform() throws Exception {
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(EdpGreedySchedulerProvider.LABEL);
        Path dax = bagDax();
        PlatformProfile platform = threeTierCarbonFleet();

        SimulationReport fcfs = new SimulationRunner().run(fcfsConfig(dax), platform);
        SimulationReport greedy = new SimulationRunner().run(registeredConfig(dax, provider), platform);
        assertEquals(
                "COMPLETED_SUCCESSFULLY", fcfs.getMetrics().getLogicalTaskCompletionStatus(), "FCFS arm completes");
        assertEquals(
                "COMPLETED_SUCCESSFULLY",
                greedy.getMetrics().getLogicalTaskCompletionStatus(),
                "EDP_GREEDY arm completes");

        List<Double> fcfsTiers = computeTierMultiset(fcfs);
        List<Double> greedyTiers = computeTierMultiset(greedy);
        assertEquals(4, fcfsTiers.size(), "the bag's four compute jobs (FCFS arm)");
        assertEquals(4, greedyTiers.size(), "the bag's four compute jobs (EDP_GREEDY arm)");
        assertEquals(
                1.0,
                countOf(greedyTiers, 500.0),
                "the longest task is stretched to the last tier by the scheduling-time API");
        assertEquals(3.0, countOf(greedyTiers, 2000.0), "the short tasks stay at the default (first) tier");
        assertEquals(0.0, countOf(fcfsTiers, 500.0), "the FCFS+DEFAULT arm assigns the default tier to every job");
        assertTrue(
                !fcfsTiers.equals(greedyTiers),
                "EDP_GREEDY alters the tier mix vs the DEFAULT policy — the first DVFS-aware result");

        double fcfsEnergy = fcfs.getEnergyAccounting().getTotalEnergyJoules();
        double greedyEnergy = greedy.getEnergyAccounting().getTotalEnergyJoules();
        double fcfsEdp = fcfs.getEnergyAccounting().getEnergyMakespanProductJouleSeconds();
        double greedyEdp = greedy.getEnergyAccounting().getEnergyMakespanProductJouleSeconds();
        System.out.println("[D6-compare] FCFS      : makespan=" + fcfs.getMakespan() + "s energy=" + fcfsEnergy
                + "J edp=" + fcfsEdp + " tierMix=all@2000");
        System.out.println("[D6-compare] EDP_GREEDY: makespan=" + greedy.getMakespan() + "s energy=" + greedyEnergy
                + "J edp=" + greedyEdp + " tierMix=3x2000+1x500");

        assertTrue(greedy.getMakespan() >= fcfs.getMakespan(), "stretching the critical tail cannot finish earlier");
        assertTrue(
                greedyEnergy >= 2 * 250.0 * greedy.getMakespan(),
                "fleet energy never falls below the default-tier idle floor (2×250 W × makespan)");
    }

    private void assumePythonAuditorPasses(Path manifest) throws Exception {
        Path script = findRepositoryScript();
        Assumptions.assumeTrue(script != null, "python auditor script not found from this module");
        Assumptions.assumeTrue(
                new ProcessBuilder("python3", "--version").start().waitFor(30, TimeUnit.SECONDS),
                "python3 runtime unavailable");

        Path reportOut = tempDir.resolve("edp-greedy-audit-report.json");
        Files.deleteIfExists(reportOut);
        ProcessBuilder builder = new ProcessBuilder(
                "python3", "-B", script.toString(), "--output", reportOut.toString(), manifest.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the auditor subprocess must terminate");
        String issuesText;
        if (Files.exists(reportOut)) {
            issuesText = new String(Files.readAllBytes(reportOut), StandardCharsets.UTF_8);
        } else {
            issuesText = "no report file written";
        }
        assertEquals(0, process.exitValue(), "auditor exit code is nonzero on failure: " + output + issuesText);
        assertTrue(output.toString().startsWith("PASS:"), "auditor summary must be PASS: " + output + issuesText);
        JsonObject audit = JsonParser.parseString(issuesText).getAsJsonObject();
        assertEquals("PASS", audit.get("status").getAsString(), "auditor status: " + output + issuesText);
        for (JsonElement run : audit.getAsJsonArray("runs")) {
            assertEquals(
                    0,
                    run.getAsJsonObject().get("issueCount").getAsInt(),
                    "audited bundle must carry zero issues: " + output + issuesText);
        }
    }

    private static Path findRepositoryScript() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve("scripts").resolve("verify-study-evidence.py");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

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
        Path path = tempDir.resolve("edp-greedy-bag.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlatformProfile threeTierCarbonFleet() {
        List<PlatformProfile.FrequencyTier> fleetTiers =
                BenchmarkExecutor.benchmarkDvfsTiers().subList(0, 3);
        PlatformProfile.Builder builder = PlatformProfile.builder("d6-edp-greedy-fleet");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(100 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[i],
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
                            Double.valueOf(BenchmarkExecutor.CARBON_INTENSITY_GRAMS_PER_KWH),
                            fleetTiers))
                    .pinVmToHost(ids[i], 100 + i);
        }
        return builder.build();
    }

    private static SimulationConfig registeredConfig(Path dax, SchedulingAlgorithmProvider provider) {
        return SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT)
                .registeredSchedulingAlgorithm(provider)
                .build();
    }

    private static SimulationConfig fcfsConfig(Path dax) {
        return SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .build();
    }

    private static List<Double> computeTierMultiset(SimulationReport report) {
        List<Double> tiers = new ArrayList<Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                tiers.add(job.getTierMips());
            }
        }
        return tiers;
    }

    private static double countOf(List<Double> values, double target) {
        double count = 0.0;
        for (double value : values) {
            if (value == target) {
                count += 1.0;
            }
        }
        return count;
    }
}
