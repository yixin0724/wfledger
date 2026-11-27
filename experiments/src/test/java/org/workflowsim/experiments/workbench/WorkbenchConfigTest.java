package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithm;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithms;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.configuration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.costs;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.json;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.localConfiguration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.text;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.topology;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.workflow;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

class WorkbenchConfigTest {
    @TempDir
    Path directory;

    private boolean loggingWasDisabled;

    @BeforeEach
    void silenceSimulationLogging() {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLogging() {
        Log.setDisabled(loggingWasDisabled);
    }

    @Test
    void platformPowerFieldsRoundTripAndValidate() throws Exception {

        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject powered = configuration(workflow.toString(), 2);
        powered.getAsJsonObject("platform").addProperty("idlePowerWatts", 250.0);
        powered.getAsJsonObject("platform").addProperty("maxPowerWatts", 400.0);
        Path poweredFile = json(directory.resolve("powered.json"), powered);
        WorkbenchConfig loaded = WorkbenchConfig.read(poweredFile);
        assertTrue(loaded.getPlatform().getVms().size() == 2, "two VMs");
        for (PlatformProfile.VmSpec spec : loaded.getPlatform().getVms()) {
            assertTrue(spec.hasPowerModel(), "declared power must survive the read");
            assertTrue(spec.getIdlePowerWatts() == 250.0 && spec.getMaxPowerWatts() == 400.0, "uniform power values");
        }
        JsonObject unpowered = configuration(workflow.toString(), 2);
        Path unpoweredFile = json(directory.resolve("unpowered.json"), unpowered);
        for (PlatformProfile.VmSpec spec :
                WorkbenchConfig.read(unpoweredFile).getPlatform().getVms()) {
            assertTrue(!spec.hasPowerModel(), "no declaration -> no power model");
        }
        JsonObject inverted = configuration(workflow.toString(), 2);
        inverted.getAsJsonObject("platform").addProperty("idlePowerWatts", 400.0);
        inverted.getAsJsonObject("platform").addProperty("maxPowerWatts", 250.0);
        Path invertedFile = json(directory.resolve("inverted.json"), inverted);
        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(invertedFile));
        assertTrue(
                rejection.getMessage().contains("idlePowerWatts"),
                "rejection must cite the power field: " + rejection.getMessage());
        JsonObject negative = configuration(workflow.toString(), 2);
        negative.getAsJsonObject("platform").addProperty("idlePowerWatts", -1.0);
        Path negativeFile = json(directory.resolve("negative.json"), negative);
        assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(negativeFile));
    }

    @Test
    void simulationEventRecorderCapIsConfiguration() throws Exception {

        Path workflow = workflow(directory, "flow.dax", false);

        JsonObject declared = configuration(workflow.toString(), 2);
        declared.getAsJsonObject("simulation").addProperty("eventRecorderCap", 50000);
        WorkbenchConfig loaded = WorkbenchConfig.read(json(directory.resolve("cap.json"), declared));
        assertEquals(50000, loaded.getCandidates().get(0).config.getEventRecorderCap());

        JsonObject absent = configuration(workflow.toString(), 2);
        WorkbenchConfig defaults = WorkbenchConfig.read(json(directory.resolve("cap-defaults.json"), absent));
        assertEquals(
                SimulationConfig.DEFAULT_EVENT_RECORDER_CAP,
                defaults.getCandidates().get(0).config.getEventRecorderCap());

        JsonObject zero = configuration(workflow.toString(), 2);
        zero.getAsJsonObject("simulation").addProperty("eventRecorderCap", 0);
        Path zeroFile = json(directory.resolve("cap-zero.json"), zero);
        IllegalArgumentException zeroRejection =
                assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(zeroFile));
        assertTrue(
                zeroRejection.getMessage().contains("eventRecorderCap"),
                "the rejection must cite the field: " + zeroRejection.getMessage());

        JsonObject fractional = configuration(workflow.toString(), 2);
        fractional.getAsJsonObject("simulation").addProperty("eventRecorderCap", 1.5);
        Path fractionalFile = json(directory.resolve("cap-fractional.json"), fractional);
        assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(fractionalFile));
    }

    @Test
    void simulationBoundedSlowdownThresholdIsConfiguration() throws Exception {
        Path workflow = workflow(directory, "slowdown-flow.dax", false);
        JsonObject declared = configuration(workflow.toString(), 2);
        declared.getAsJsonObject("simulation").addProperty("boundedSlowdownThresholdSeconds", 0.1);
        WorkbenchConfig loaded = WorkbenchConfig.read(json(directory.resolve("tau.json"), declared));
        assertEquals(0.1, loaded.getCandidates().get(0).config.getBoundedSlowdownThresholdSeconds(), 0.0);

        WorkbenchConfig defaults = WorkbenchConfig.read(
                json(directory.resolve("tau-default.json"), configuration(workflow.toString(), 2)));
        assertEquals(10.0, defaults.getCandidates().get(0).config.getBoundedSlowdownThresholdSeconds(), 0.0);

        JsonObject zero = configuration(workflow.toString(), 2);
        zero.getAsJsonObject("simulation").addProperty("boundedSlowdownThresholdSeconds", 0);
        Path zeroFile = json(directory.resolve("tau-zero.json"), zero);
        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(zeroFile));
        assertTrue(rejection.getMessage().contains("boundedSlowdownThresholdSeconds"), rejection.getMessage());
    }

    @Test
    void evidenceOutputDefaultsToSummaryAndAcceptsOnlyTheTwoDeclaredValues() throws Exception {
        Path workflow = workflow(directory, "evidence-flow.dax", false);
        JsonObject absent = configuration(workflow.toString(), 2);
        absent.remove("evidence");
        assertEquals(
                org.workflowsim.experiment.EvidenceOutput.SUMMARY,
                WorkbenchConfig.read(json(directory.resolve("evidence-absent.json"), absent))
                        .getEvidenceOutput());

        for (String[] declared : new String[][] {{"summary", "SUMMARY"}, {"full", "FULL"}}) {
            JsonObject config = configuration(workflow.toString(), 2);
            config.addProperty("evidence", declared[0]);
            assertEquals(
                    org.workflowsim.experiment.EvidenceOutput.valueOf(declared[1]),
                    WorkbenchConfig.read(json(directory.resolve("evidence-" + declared[0] + ".json"), config))
                            .getEvidenceOutput());
        }

        for (String invalid : new String[] {"FULL", "none", "true", " full"}) {
            JsonObject config = configuration(workflow.toString(), 2);
            config.addProperty("evidence", invalid);
            Path file = json(directory.resolve("evidence-invalid.json"), config);
            IllegalArgumentException rejection =
                    assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(file));
            assertTrue(rejection.getMessage().contains("evidence"), rejection.getMessage());
        }

        JsonObject nonString = configuration(workflow.toString(), 2);
        nonString.addProperty("evidence", true);
        Path nonStringFile = json(directory.resolve("evidence-boolean.json"), nonString);
        assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(nonStringFile));
    }

    @Test
    void workbenchRejectsTheCarbonDeferralLabelWithAPointer() throws Exception {
        Path workflow = workflow(directory, "defer-flow.dax", false);
        JsonObject configuration = configuration(workflow.toString(), 2);
        JsonObject algorithm = configuration.getAsJsonArray("algorithms").get(0).getAsJsonObject();
        algorithm.addProperty("scheduler", "CARBON_WINDOW_DEFER");
        Path file = json(directory.resolve("defer.json"), configuration);

        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(file));
        assertTrue(
                rejection.getMessage().contains("CARBON_WINDOW_DEFER"),
                "the refusal must name the label: " + rejection.getMessage());
        assertTrue(
                rejection.getMessage().contains("SimulationRunner")
                        || rejection.getMessage().contains("carbonWindows"),
                "the refusal must point at the supported route: " + rejection.getMessage());
    }

    @Test
    void platformCarbonIntensityUniformRoundTripAndValidate() throws Exception {

        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject powered = configuration(workflow.toString(), 2);
        powered.getAsJsonObject("platform").addProperty("idlePowerWatts", 250.0);
        powered.getAsJsonObject("platform").addProperty("maxPowerWatts", 400.0);
        powered.getAsJsonObject("platform").addProperty("carbonIntensityGramsPerKWh", 380.0);
        WorkbenchConfig loaded = WorkbenchConfig.read(json(directory.resolve("carbon.json"), powered));
        for (PlatformProfile.VmSpec spec : loaded.getPlatform().getVms()) {
            assertTrue(
                    spec.getCarbonIntensityGramsPerKWh() != null && spec.getCarbonIntensityGramsPerKWh() == 380.0,
                    "uniform carbon intensity on every VM");
            assertTrue(spec.hasPowerModel(), "carbon fixture must remain powered");
        }
        JsonObject plain = configuration(workflow.toString(), 2);
        for (PlatformProfile.VmSpec spec : WorkbenchConfig.read(json(directory.resolve("plain.json"), plain))
                .getPlatform()
                .getVms()) {
            assertTrue(spec.getCarbonIntensityGramsPerKWh() == null, "no carbon declaration -> null intensity");
        }

        JsonObject orphan = configuration(workflow.toString(), 2);
        orphan.getAsJsonObject("platform").addProperty("carbonIntensityGramsPerKWh", 380.0);
        IllegalArgumentException orphanRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("orphan.json"), orphan)));
        assertTrue(
                orphanRejection.getMessage().contains("carbonIntensityGramsPerKWh")
                        && orphanRejection.getMessage().contains("idlePowerWatts"),
                "rejection must cite both fields: " + orphanRejection.getMessage());

        JsonObject negative = configuration(workflow.toString(), 2);
        negative.getAsJsonObject("platform").addProperty("idlePowerWatts", 250.0);
        negative.getAsJsonObject("platform").addProperty("maxPowerWatts", 400.0);
        negative.getAsJsonObject("platform").addProperty("carbonIntensityGramsPerKWh", -0.5);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("negative-carbon.json"), negative)));

        JsonObject windows = configuration(workflow.toString(), 2);
        windows.getAsJsonObject("platform").addProperty("idlePowerWatts", 250.0);
        windows.getAsJsonObject("platform").addProperty("maxPowerWatts", 400.0);
        JsonArray someWindow = new JsonArray();
        JsonObject window = new JsonObject();
        window.addProperty("startSeconds", 0.0);
        window.addProperty("endSeconds", 60.0);
        window.addProperty("greenWatts", 300.0);
        someWindow.add(window);
        windows.getAsJsonObject("platform").add("carbonWindows", someWindow);
        IllegalArgumentException windowRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("windows.json"), windows)));
        assertTrue(
                windowRejection.getMessage().contains("carbonWindows"),
                "carbon windows must be rejected as unknown in the v1 workbench: " + windowRejection.getMessage());
    }

    @Test
    void platformFrequencyTiersRoundTripAndValidate() throws Exception {

        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject tiered = configuration(workflow.toString(), 2);
        JsonObject tieredPlatform = tiered.getAsJsonObject("platform");
        tieredPlatform.add("frequencyTiers", tiers(2000.0, 250.0, 400.0, 1000.0, 220.0, 280.0, 500.0, 210.0, 240.0));
        tieredPlatform.addProperty("frequencyPolicy", "EDP_HEURISTIC");
        WorkbenchConfig loaded = WorkbenchConfig.read(json(directory.resolve("tiered.json"), tiered));
        for (PlatformProfile.VmSpec spec : loaded.getPlatform().getVms()) {
            assertTrue(spec.hasFrequencyTiers(), "declared tiers must survive the read");
            List<PlatformProfile.FrequencyTier> tiers = spec.getFrequencyTiers();
            assertTrue(tiers.size() == 3, "uniform tier table on every VM");
            assertTrue(
                    tiers.get(0).getMips() == 2000.0
                            && tiers.get(0).getIdlePowerWatts() == 250.0
                            && tiers.get(0).getMaxPowerWatts() == 400.0,
                    "first tier is the default tier");
            assertTrue(tiers.get(1).getMips() == 1000.0 && tiers.get(2).getMips() == 500.0, "declared order kept");
            assertTrue(
                    tiers.get(2).getIdlePowerWatts() == 210.0 && tiers.get(2).getMaxPowerWatts() == 240.0,
                    "third tier power pair");
            assertTrue(
                    spec.getIdlePowerWatts() == 0.0 && spec.getMaxPowerWatts() == 0.0,
                    "tiered VM keeps flat power fields at 0 (one source of truth)");
        }
        for (WorkbenchConfig.Candidate candidate : loaded.getCandidates()) {
            assertTrue(
                    candidate.config.getFrequencyPolicy() == SimulationConfig.FrequencyPolicy.EDP_HEURISTIC,
                    "frequency policy must flow into every candidate SimulationConfig");
        }

        JsonObject v1 = configuration(workflow.toString(), 2);
        WorkbenchConfig v1Loaded = WorkbenchConfig.read(json(directory.resolve("v1.json"), v1));
        for (PlatformProfile.VmSpec spec : v1Loaded.getPlatform().getVms()) {
            assertTrue(!spec.hasFrequencyTiers(), "no frequencyTiers key -> v1 single-tier platform");
        }
        for (WorkbenchConfig.Candidate candidate : v1Loaded.getCandidates()) {
            assertTrue(
                    candidate.config.getFrequencyPolicy() == SimulationConfig.FrequencyPolicy.DEFAULT,
                    "absent policy resolution must be DEFAULT");
        }

        JsonObject both = configuration(workflow.toString(), 2);
        JsonObject bothPlatform = both.getAsJsonObject("platform");
        bothPlatform.add("frequencyTiers", tiers(2000.0, 250.0, 400.0, 1000.0, 220.0, 280.0, 500.0, 210.0, 240.0));
        bothPlatform.addProperty("idlePowerWatts", 250.0);
        bothPlatform.addProperty("maxPowerWatts", 400.0);
        IllegalArgumentException exclusive = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("exclusive.json"), both)));
        assertTrue(
                exclusive.getMessage().contains("frequencyTiers")
                        && exclusive.getMessage().contains("idlePowerWatts"),
                "mutual-exclusivity rejection must cite both field faces: " + exclusive.getMessage());

        JsonObject policyWithoutTiers = configuration(workflow.toString(), 2);
        policyWithoutTiers.getAsJsonObject("platform").addProperty("frequencyPolicy", "MIN_POWER");
        IllegalArgumentException inert = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("policy-without-tiers.json"), policyWithoutTiers)));
        assertTrue(
                inert.getMessage().contains("frequencyPolicy")
                        && inert.getMessage().contains("frequencyTiers"),
                "policy-without-tiers rejection must cite both fields: " + inert.getMessage());

        JsonObject ascending = configuration(workflow.toString(), 2);
        ascending
                .getAsJsonObject("platform")
                .add("frequencyTiers", tiers(500.0, 210.0, 240.0, 1000.0, 220.0, 280.0, 2000.0, 250.0, 400.0));
        IllegalArgumentException unordered = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("ascending.json"), ascending)));
        assertTrue(
                unordered.getMessage().contains("descending"),
                "non-descending tier column must be rejected: " + unordered.getMessage());

        JsonObject zeroMips = configuration(workflow.toString(), 2);
        zeroMips.getAsJsonObject("platform").add("frequencyTiers", tiers(0.0, 210.0, 240.0));
        IllegalArgumentException zero = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("zero-mips.json"), zeroMips)));
        assertTrue(zero.getMessage().contains("mips"), "tier mips must be positive: " + zero.getMessage());
        JsonObject missingField = configuration(workflow.toString(), 2);
        JsonArray half = new JsonArray();
        JsonObject tier = new JsonObject();
        tier.addProperty("mips", 1000.0);
        tier.addProperty("idlePowerWatts", 220.0);
        half.add(tier);
        missingField.getAsJsonObject("platform").add("frequencyTiers", half);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("half-tier.json"), missingField)));

        JsonObject unknownPolicy = configuration(workflow.toString(), 2);
        unknownPolicy
                .getAsJsonObject("platform")
                .add("frequencyTiers", tiers(2000.0, 250.0, 400.0, 1000.0, 220.0, 280.0, 500.0, 210.0, 240.0));
        unknownPolicy.getAsJsonObject("platform").addProperty("frequencyPolicy", "TURBO_BOOST");
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("unknown-policy.json"), unknownPolicy)));
        assertTrue(
                unknown.getMessage().contains("frequencyPolicy"),
                "unknown policy must cite the policy field: " + unknown.getMessage());
    }

    private static JsonArray tiers(double... mipsIdleMax) {
        JsonArray table = new JsonArray();
        for (int i = 0; i < mipsIdleMax.length; i += 3) {
            JsonObject tier = new JsonObject();
            tier.addProperty("mips", mipsIdleMax[i]);
            tier.addProperty("idlePowerWatts", mipsIdleMax[i + 1]);
            tier.addProperty("maxPowerWatts", mipsIdleMax[i + 2]);
            table.add(tier);
        }
        return table;
    }

    @Test
    void combinedExecutionModeRemainsTheDefaultAndPreservesCandidateIdentity() throws Exception {
        JsonObject root = base();
        workflow(directory, "other.dax", true);
        root.getAsJsonArray("workflowPaths").add("other.dax");
        algorithms(root, algorithm("fcfs", "FCFS", null), algorithm("minmin", "READY_BATCH_MINMIN", null));

        WorkbenchConfig loaded = read(root);
        List<String> combinedPaths = Arrays.asList(
                directory.resolve("workflow.dax").toAbsolutePath().normalize().toString(),
                directory.resolve("other.dax").toAbsolutePath().normalize().toString());
        assertEquals(2, loaded.getCandidates().size());
        for (int index = 0; index < loaded.getCandidates().size(); index++) {
            WorkbenchConfig.Candidate candidate = loaded.getCandidates().get(index);
            String algorithmId = index == 0 ? "fcfs" : "minmin";
            assertEquals(algorithmId, candidate.id);
            assertEquals(algorithmId, candidate.algorithmId);
            assertNull(candidate.workflowPath);
            assertEquals(combinedPaths, candidate.config.getWorkflowPaths());
            assertEquals(Arrays.asList(0.0, 0.0), candidate.config.getWorkflowArrivalSeconds());
            assertEquals(Parameters.CostModel.DATACENTER, candidate.config.getCostModel());
            assertEquals(0L, candidate.config.getDeadline());
            assertEquals(SimulationConfig.FrequencyPolicy.DEFAULT, candidate.config.getFrequencyPolicy());
        }
        assertEquals(3.0, loaded.getPlatform().getCosts().getCpuPerSecond(), 0.0);

        JsonObject explicit = root.deepCopy();
        explicit.addProperty("executionMode", "COMBINED_WORKFLOWS");
        WorkbenchConfig explicitlyCombined = read(explicit);
        assertEquals("fcfs", explicitlyCombined.getCandidates().get(0).id);
        assertEquals(
                combinedPaths, explicitlyCombined.getCandidates().get(0).config.getWorkflowPaths());
    }

    @Test
    void perWorkflowExpandsEveryInputAndAlgorithmWithSafeStableIdentities() throws Exception {
        workflow(directory, "alpha.dax", true);
        workflow(directory, "beta.dax", true);
        JsonObject root = configuration("alpha.dax", 2);
        root.getAsJsonArray("workflowPaths").add("beta.dax");
        root.addProperty("executionMode", "PER_WORKFLOW");
        algorithms(root, algorithm("fcfs", "FCFS", null), algorithm("minmin", "READY_BATCH_MINMIN", null));
        JsonArray arrivals = new JsonArray();
        arrivals.add(1.5);
        arrivals.add(2.5);
        root.add("workflowArrivalSeconds", arrivals);
        root.getAsJsonObject("simulation").addProperty("deadlineSeconds", 7);
        JsonObject deadlines = new JsonObject();
        deadlines.addProperty("beta.dax", 99);
        root.getAsJsonObject("simulation").add("deadlineSecondsByWorkflow", deadlines);

        WorkbenchConfig loaded = read(root);
        assertEquals(4, loaded.getCandidates().size());
        String[] expectedIds = {"alpha__fcfs", "alpha__minmin", "beta__fcfs", "beta__minmin"};
        String[] expectedAlgorithms = {"fcfs", "minmin", "fcfs", "minmin"};
        String[] expectedFiles = {"alpha.dax", "alpha.dax", "beta.dax", "beta.dax"};
        long[] expectedDeadlines = {7L, 7L, 99L, 99L};
        double[] expectedArrivals = {1.5, 1.5, 2.5, 2.5};
        for (int index = 0; index < expectedIds.length; index++) {
            WorkbenchConfig.Candidate candidate = loaded.getCandidates().get(index);
            String path = directory
                    .resolve(expectedFiles[index])
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            assertEquals(expectedIds[index], candidate.id);
            assertEquals(expectedAlgorithms[index], candidate.algorithmId);
            assertEquals(path, candidate.workflowPath);
            assertEquals(Arrays.asList(path), candidate.config.getWorkflowPaths());
            assertEquals(Arrays.asList(expectedArrivals[index]), candidate.config.getWorkflowArrivalSeconds());
            assertEquals(expectedDeadlines[index], candidate.config.getDeadline());
        }
    }

    @Test
    void perWorkflowRejectsUnsafeOrCollidingCandidateIdsAndUnknownExecutionModes() throws Exception {
        workflow(directory, "bad name.dax", true);
        JsonObject unsafe = configuration("bad name.dax", 2);
        unsafe.addProperty("executionMode", "PER_WORKFLOW");
        rejectRead(unsafe, "safe identifiers");

        workflow(directory.resolve("one"), "same.dax", true);
        workflow(directory.resolve("two"), "same.dax", true);
        JsonObject collision = configuration("one/same.dax", 2);
        collision.getAsJsonArray("workflowPaths").add("two/same.dax");
        collision.addProperty("executionMode", "PER_WORKFLOW");
        rejectRead(collision, "unique");

        JsonObject unknown = base();
        unknown.addProperty("executionMode", "SEPARATE");
        rejectRead(unknown, "executionMode");
    }

    @Test
    void simulationCostModelDefaultsToDatacenterAndInjectsTheDeclaredModel() throws Exception {
        JsonObject declared = base();
        declared.getAsJsonObject("simulation").addProperty("costModel", "VM");
        for (WorkbenchConfig.Candidate candidate : read(declared).getCandidates()) {
            assertEquals(Parameters.CostModel.VM, candidate.config.getCostModel());
        }

        JsonObject defaults = base();
        assertEquals(
                Parameters.CostModel.DATACENTER,
                read(defaults).getCandidates().get(0).config.getCostModel());

        JsonObject unknown = base();
        unknown.getAsJsonObject("simulation").addProperty("costModel", "PER_VM");
        rejectRead(unknown, "costModel");
    }

    @Test
    void perWorkflowDeadlinesRejectCombinedModeUnknownNamesAndInvalidValues() throws Exception {
        JsonObject combined = base();
        combined.getAsJsonObject("simulation").add("deadlineSecondsByWorkflow", new JsonObject());
        rejectRead(combined, "PER_WORKFLOW");

        JsonObject unknown = base();
        unknown.addProperty("executionMode", "PER_WORKFLOW");
        JsonObject unknownDeadline = new JsonObject();
        unknownDeadline.addProperty("unknown.dax", 10);
        unknown.getAsJsonObject("simulation").add("deadlineSecondsByWorkflow", unknownDeadline);
        rejectRead(unknown, "unknown workflow basename");

        JsonObject negative = base();
        negative.addProperty("executionMode", "PER_WORKFLOW");
        JsonObject negativeDeadline = new JsonObject();
        negativeDeadline.addProperty("workflow.dax", -1);
        negative.getAsJsonObject("simulation").add("deadlineSecondsByWorkflow", negativeDeadline);
        rejectRead(negative, "nonnegative");

        JsonObject fractional = base();
        fractional.addProperty("executionMode", "PER_WORKFLOW");
        JsonObject fractionalDeadline = new JsonObject();
        fractionalDeadline.addProperty("workflow.dax", 1.5);
        fractional.getAsJsonObject("simulation").add("deadlineSecondsByWorkflow", fractionalDeadline);
        rejectRead(fractional, "exact integer");

        JsonObject zero = base();
        zero.addProperty("executionMode", "PER_WORKFLOW");
        JsonObject zeroDeadline = new JsonObject();
        zeroDeadline.addProperty("workflow.dax", 0);
        zero.getAsJsonObject("simulation").add("deadlineSecondsByWorkflow", zeroDeadline);
        assertEquals(0L, read(zero).getCandidates().get(0).config.getDeadline());
    }

    @Test
    void algorithmFrequencyPolicyOverridesGlobalPolicyAndRequiresTiers() throws Exception {
        JsonObject root = base();
        JsonObject platform = root.getAsJsonObject("platform");
        platform.add("frequencyTiers", tiers(2000.0, 250.0, 400.0, 1000.0, 220.0, 280.0));
        platform.addProperty("frequencyPolicy", "MIN_POWER");
        JsonObject inherited = algorithm("inherited", "FCFS", null);
        JsonObject edp = algorithm("edp", "READY_BATCH_MINMIN", null);
        edp.addProperty("frequencyPolicy", "EDP_HEURISTIC");
        JsonObject plain = algorithm("plain", "READY_BATCH_MCT", null);
        plain.addProperty("frequencyPolicy", "DEFAULT");
        algorithms(root, inherited, edp, plain);

        WorkbenchConfig loaded = read(root);
        assertEquals(
                SimulationConfig.FrequencyPolicy.MIN_POWER,
                loaded.getCandidates().get(0).config.getFrequencyPolicy());
        assertEquals(
                SimulationConfig.FrequencyPolicy.EDP_HEURISTIC,
                loaded.getCandidates().get(1).config.getFrequencyPolicy());
        assertEquals(
                SimulationConfig.FrequencyPolicy.DEFAULT,
                loaded.getCandidates().get(2).config.getFrequencyPolicy());

        JsonObject tierless = base();
        JsonObject override = tierless.getAsJsonArray("algorithms").get(0).getAsJsonObject();
        override.addProperty("frequencyPolicy", "MIN_POWER");
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("tierless.json"), tierless)));
        assertContains(rejection, "algorithm.frequencyPolicy");
        assertContains(rejection, "frequencyTiers");
        assertContains(rejection, "fcfs");

        JsonObject unknown = base();
        unknown.getAsJsonArray("algorithms").get(0).getAsJsonObject().addProperty("frequencyPolicy", "TURBO");
        rejectRead(unknown, "algorithm.frequencyPolicy");
    }

    @Test
    void vmProfilesInjectIndependentPowerCostsAndFrequencyTierTables() throws Exception {
        JsonObject root = base();
        root.getAsJsonObject("platform").getAsJsonArray("vmMips").add(1000.0);
        root.getAsJsonObject("platform").addProperty("cpuCostPerSecond", 4.5);
        JsonObject dvfs = root.getAsJsonArray("algorithms").get(0).getAsJsonObject();
        dvfs.addProperty("frequencyPolicy", "MIN_POWER");
        JsonArray profiles = new JsonArray();

        JsonObject flat = vmProfile(0);
        flat.addProperty("idlePowerWatts", 100.0);
        flat.addProperty("maxPowerWatts", 200.0);
        flat.addProperty("cpuCostPerSecond", 1.25);
        profiles.add(flat);

        JsonObject firstTiered = vmProfile(1);
        firstTiered.add("frequencyTiers", tiers(2000.0, 150.0, 300.0, 1000.0, 100.0, 200.0));
        profiles.add(firstTiered);

        JsonObject secondTiered = vmProfile(2);
        secondTiered.addProperty("cpuCostPerSecond", 2.5);
        secondTiered.add("frequencyTiers", tiers(3000.0, 180.0, 360.0, 1500.0, 120.0, 240.0));
        profiles.add(secondTiered);
        root.getAsJsonObject("platform").add("vmProfiles", profiles);

        WorkbenchConfig loaded = read(root);
        List<PlatformProfile.VmSpec> vms = loaded.getPlatform().getVms();
        assertEquals(3, vms.size());
        assertEquals(100.0, vms.get(0).getIdlePowerWatts(), 0.0);
        assertEquals(200.0, vms.get(0).getMaxPowerWatts(), 0.0);
        assertEquals(1.25, vms.get(0).getCosts().getCpuPerSecond(), 0.0);
        assertTrue(!vms.get(0).hasFrequencyTiers());
        assertEquals(0.0, vms.get(1).getIdlePowerWatts(), 0.0);
        assertEquals(4.5, vms.get(1).getCosts().getCpuPerSecond(), 0.0);
        assertEquals(2000.0, vms.get(1).getFrequencyTiers().get(0).getMips(), 0.0);
        assertEquals(1000.0, vms.get(1).getFrequencyTiers().get(1).getMips(), 0.0);
        assertEquals(2.5, vms.get(2).getCosts().getCpuPerSecond(), 0.0);
        assertEquals(3000.0, vms.get(2).getFrequencyTiers().get(0).getMips(), 0.0);
        assertEquals(150.0, vms.get(1).getFrequencyTiers().get(0).getIdlePowerWatts(), 0.0);
        assertEquals(180.0, vms.get(2).getFrequencyTiers().get(0).getIdlePowerWatts(), 0.0);
        assertEquals(
                SimulationConfig.FrequencyPolicy.MIN_POWER,
                loaded.getCandidates().get(0).config.getFrequencyPolicy());

        JsonObject defaults = base();
        JsonArray defaultProfiles = new JsonArray();
        defaultProfiles.add(vmProfile(0));
        defaultProfiles.add(vmProfile(1));
        defaults.getAsJsonObject("platform").add("vmProfiles", defaultProfiles);
        for (PlatformProfile.VmSpec vm : read(defaults).getPlatform().getVms()) {
            assertEquals(3.0, vm.getCosts().getCpuPerSecond(), 0.0);
            assertTrue(!vm.hasPowerModel());
            assertTrue(!vm.hasFrequencyTiers());
        }
    }

    @Test
    void vmProfilesInheritGlobalPowerOrTiersWhenTheyDeclareOnlyCosts() throws Exception {
        JsonObject flat = base();
        flat.getAsJsonObject("platform").addProperty("idlePowerWatts", 40.0);
        flat.getAsJsonObject("platform").addProperty("maxPowerWatts", 80.0);
        JsonArray flatProfiles = new JsonArray();
        JsonObject priced = vmProfile(0);
        priced.addProperty("cpuCostPerSecond", 1.5);
        flatProfiles.add(priced);
        flatProfiles.add(vmProfile(1));
        flat.getAsJsonObject("platform").add("vmProfiles", flatProfiles);
        for (PlatformProfile.VmSpec vm : read(flat).getPlatform().getVms()) {
            assertEquals(40.0, vm.getIdlePowerWatts(), 0.0);
            assertEquals(80.0, vm.getMaxPowerWatts(), 0.0);
        }

        JsonObject tiered = base();
        tiered.getAsJsonObject("platform").add("frequencyTiers", tiers(2000.0, 150.0, 300.0, 1000.0, 100.0, 200.0));
        JsonArray tieredProfiles = new JsonArray();
        tieredProfiles.add(vmProfile(0));
        tieredProfiles.add(vmProfile(1));
        tiered.getAsJsonObject("platform").add("vmProfiles", tieredProfiles);
        for (PlatformProfile.VmSpec vm : read(tiered).getPlatform().getVms()) {
            assertEquals(2, vm.getFrequencyTiers().size());
            assertEquals(2000.0, vm.getFrequencyTiers().get(0).getMips(), 0.0);
            assertEquals(3.0, vm.getCosts().getCpuPerSecond(), 0.0);
        }
    }

    @Test
    void vmProfilesRejectEmptyDuplicateOutOfRangeAndUnknownDeclarations() throws Exception {
        JsonObject empty = base();
        empty.getAsJsonObject("platform").add("vmProfiles", new JsonArray());
        rejectRead(empty, "non-empty");

        JsonObject duplicate = base();
        JsonArray duplicates = new JsonArray();
        duplicates.add(vmProfile(0));
        duplicates.add(vmProfile(0));
        duplicate.getAsJsonObject("platform").add("vmProfiles", duplicates);
        rejectRead(duplicate, "unique");

        for (int invalidId : Arrays.asList(-1, 2)) {
            JsonObject outside = base();
            JsonArray profiles = new JsonArray();
            profiles.add(vmProfile(invalidId));
            outside.getAsJsonObject("platform").add("vmProfiles", profiles);
            rejectRead(outside, "vmMips");
        }

        JsonObject fractional = base();
        JsonArray fractionalProfiles = new JsonArray();
        JsonObject fractionalProfile = new JsonObject();
        fractionalProfile.addProperty("vmId", 0.5);
        fractionalProfiles.add(fractionalProfile);
        fractional.getAsJsonObject("platform").add("vmProfiles", fractionalProfiles);
        rejectRead(fractional, "exact integer");

        JsonObject unknown = base();
        JsonArray unknownProfiles = new JsonArray();
        JsonObject unknownProfile = vmProfile(0);
        unknownProfile.addProperty("mips", 500.0);
        unknownProfiles.add(unknownProfile);
        unknown.getAsJsonObject("platform").add("vmProfiles", unknownProfiles);
        rejectRead(unknown, "vmProfiles has unknown field: mips");

        JsonObject inverted = base();
        JsonArray invertedProfiles = new JsonArray();
        JsonObject invertedProfile = vmProfile(0);
        invertedProfile.addProperty("idlePowerWatts", 200.0);
        invertedProfile.addProperty("maxPowerWatts", 100.0);
        invertedProfiles.add(invertedProfile);
        inverted.getAsJsonObject("platform").add("vmProfiles", invertedProfiles);
        rejectRead(inverted, "maxPowerWatts");
    }

    @Test
    void vmProfilesRejectGlobalAndPerVmPowerOrTierDoubleSources() throws Exception {
        JsonObject flatMix = base();
        flatMix.getAsJsonObject("platform").addProperty("idlePowerWatts", 40.0);
        flatMix.getAsJsonObject("platform").addProperty("maxPowerWatts", 80.0);
        JsonArray flatProfiles = new JsonArray();
        JsonObject flatProfile = vmProfile(0);
        flatProfile.addProperty("idlePowerWatts", 30.0);
        flatProfiles.add(flatProfile);
        flatMix.getAsJsonObject("platform").add("vmProfiles", flatProfiles);
        IllegalArgumentException flatRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("flat-mix.json"), flatMix)));
        assertContains(flatRejection, "vmProfiles");
        assertContains(flatRejection, "idlePowerWatts");

        JsonObject tierMix = base();
        tierMix.getAsJsonObject("platform").add("frequencyTiers", tiers(2000.0, 150.0, 300.0, 1000.0, 100.0, 200.0));
        JsonArray tierProfiles = new JsonArray();
        JsonObject tierProfile = vmProfile(0);
        tierProfile.add("frequencyTiers", tiers(1800.0, 140.0, 280.0));
        tierProfiles.add(tierProfile);
        tierMix.getAsJsonObject("platform").add("vmProfiles", tierProfiles);
        IllegalArgumentException tierRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("tier-mix.json"), tierMix)));
        assertContains(tierRejection, "vmProfiles");
        assertContains(tierRejection, "frequencyTiers");

        JsonObject crossMix = base();
        crossMix.getAsJsonObject("platform").addProperty("idlePowerWatts", 40.0);
        crossMix.getAsJsonObject("platform").addProperty("maxPowerWatts", 80.0);
        JsonArray crossProfiles = new JsonArray();
        JsonObject crossProfile = vmProfile(0);
        crossProfile.add("frequencyTiers", tiers(1800.0, 140.0, 280.0));
        crossProfiles.add(crossProfile);
        crossMix.getAsJsonObject("platform").add("vmProfiles", crossProfiles);
        rejectRead(crossMix, "one source of truth");

        JsonObject withinProfile = base();
        JsonArray bothProfiles = new JsonArray();
        JsonObject both = vmProfile(0);
        both.addProperty("idlePowerWatts", 40.0);
        both.add("frequencyTiers", tiers(1800.0, 140.0, 280.0));
        bothProfiles.add(both);
        withinProfile.getAsJsonObject("platform").add("vmProfiles", bothProfiles);
        rejectRead(withinProfile, "one source of truth");
    }

    private static JsonObject vmProfile(int vmId) {
        JsonObject profile = new JsonObject();
        profile.addProperty("vmId", vmId);
        return profile;
    }

    @Test
    void resolvesInputsRelativeToConfigurationIncludingSpacesAndAcceptsAbsoluteInputs() throws Exception {
        Path input = workflow(directory.resolve("inputs"), "flow with spaces.dax", true);
        Path config =
                json(directory.resolve("configs/experiment.json"), configuration("../inputs/flow with spaces.dax", 2));
        assertDoesNotThrow(() -> WorkbenchConfig.read(config).validateInputs());

        json(config, configuration(input.toAbsolutePath().toString(), 2));
        assertDoesNotThrow(() -> WorkbenchConfig.read(config).validateInputs());
        Files.delete(input);
        IllegalArgumentException missing =
                assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(config));
        assertContains(missing, "does not exist");
        assertContains(missing, "flow with spaces.dax");
    }

    @Test
    void rejectsMissingDirectoryAndNonStringInputsBeforeSimulation() throws Exception {
        rejectRead(configuration("missing.dax", 1), "does not exist");
        Files.createDirectory(directory.resolve("input-directory"));
        rejectRead(configuration("input-directory", 1), "does not exist");
        JsonObject nonString = base();
        nonString.getAsJsonArray("workflowPaths").set(0, new JsonPrimitive(true));
        rejectRead(nonString, "workflowPaths must contain strings");
        JsonObject noInputs = base();
        noInputs.add("workflowPaths", new JsonArray());
        rejectRead(noInputs, "workflowPaths requires");
    }

    @Test
    void rejectsMalformedDocumentsUnsupportedSchemaAndWrongContainerTypes() throws Exception {
        Path config = directory.resolve("config.json");
        for (String malformed : Arrays.asList("[]", "null", "{")) {
            text(config, malformed);
            assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(config), malformed);
        }
        JsonObject wrongSchema = base();
        wrongSchema.addProperty("schema", "workbench-future-v99");
        rejectRead(wrongSchema, "schema");
        JsonObject wrongPlatform = base();
        wrongPlatform.add("platform", JsonNull.INSTANCE);
        rejectRead(wrongPlatform, "platform must be an object");
        JsonObject wrongAlgorithms = base();
        wrongAlgorithms.addProperty("algorithms", "FCFS");
        rejectRead(wrongAlgorithms, "algorithms must be an array");
        JsonObject wrongEntry = base();
        wrongEntry.getAsJsonArray("algorithms").set(0, new JsonPrimitive("FCFS"));
        rejectRead(wrongEntry, "algorithms must contain objects");
    }

    @Test
    void rejectsDuplicateKeysCommentsAndUnquotedNames() throws Exception {
        Path config = json(directory.resolve("strict.json"), base());
        String valid = text(config);
        for (String invalid : Arrays.asList(
                valid.replace("\"schema\":", "\"name\":\"duplicate\",\"schema\":"),
                "/*comment*/" + valid,
                valid.replace("\"schema\":", "schema:"),
                valid + " {}")) {
            text(config, invalid);
            assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(config));
        }
    }

    @Test
    void rejectsUnknownFieldsAtEveryUserConfigurationLevel() throws Exception {
        JsonObject root = base();
        root.addProperty("titel", "typo");
        rejectRead(root, "configuration has unknown field: titel");
        JsonObject platform = base();
        platform.getAsJsonObject("platform").addProperty("vmCount", 2);
        rejectRead(platform, "platform has unknown field: vmCount");
        JsonObject simulation = base();
        simulation.getAsJsonObject("simulation").addProperty("runtimeScael", 1);
        rejectRead(simulation, "simulation has unknown field: runtimeScael");
        JsonObject candidate = base();
        candidate.getAsJsonArray("algorithms").get(0).getAsJsonObject().addProperty("schedulre", "FCFS");
        rejectRead(candidate, "algorithm has unknown field: schedulre");
        JsonObject network = local(true);
        network.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("linkBw", 1);
        rejectRead(network, "networkTopology has unknown field: linkBw");
        JsonObject fixed = fixed();
        fixed.getAsJsonObject("simulation").getAsJsonObject("fixedEndpoint").addProperty("latency", 0);
        rejectRead(fixed, "fixedEndpoint has unknown field: latency");
        JsonObject matrix = matrix();
        matrix.getAsJsonObject("simulation")
                .getAsJsonArray("taskCostMatrix")
                .get(0)
                .getAsJsonObject()
                .addProperty("seconds", 1);
        rejectRead(matrix, "taskCostMatrix has unknown field: seconds");
    }

    @Test
    void rejectsCoercedNonfiniteAndNonpositiveNumericValues() throws Exception {
        for (JsonElement invalid : Arrays.<JsonElement>asList(
                new JsonPrimitive("1000"),
                new JsonPrimitive(true),
                JsonNull.INSTANCE,
                new JsonPrimitive(0),
                new JsonPrimitive(-1),
                new JsonPrimitive(new BigDecimal("1e999")))) {
            JsonObject root = base();
            root.getAsJsonObject("platform").getAsJsonArray("vmMips").set(0, invalid);
            rejectRead(root, "vmMips");
        }
        JsonObject scale = base();
        scale.getAsJsonObject("simulation").addProperty("runtimeScale", "1.0");
        rejectRead(scale, "runtimeScale");
        JsonObject cadence = base();
        cadence.getAsJsonObject("simulation").addProperty("minEventIntervalSeconds", 0);
        rejectRead(cadence, "minEventIntervalSeconds");
        JsonObject cpuCost = base();
        cpuCost.getAsJsonObject("platform").addProperty("cpuCostPerSecond", -1);
        rejectRead(cpuCost, "cpuCostPerSecond");
        JsonObject duration = matrix();
        duration.getAsJsonObject("simulation")
                .getAsJsonArray("taskCostMatrix")
                .get(0)
                .getAsJsonObject()
                .addProperty("executionSeconds", 0);
        rejectRead(duration, "executionSeconds");
        JsonObject finiteZero = base();
        finiteZero.getAsJsonObject("platform").addProperty("cpuCostPerSecond", 0);
        JsonArray arrivals = new JsonArray();
        arrivals.add(0);
        finiteZero.add("workflowArrivalSeconds", arrivals);
        assertDoesNotThrow(() -> read(finiteZero).validateInputs(), "zero is valid for costs and arrival times");
    }

    @Test
    void storageTransferRateAcceptsFinitePositiveFractionsAndRejectsTheRest() throws Exception {
        JsonObject fractional = base();
        fractional.getAsJsonObject("platform").addProperty("storageTransferMbPerSecond", 2.5);
        assertEquals(2.5, read(fractional).getPlatform().getStorage().getMaxTransferRateMbPerSecond(), 0.0);
        JsonObject defaulted = base();
        defaulted.getAsJsonObject("platform").remove("storageTransferMbPerSecond");
        assertEquals(15.0, read(defaulted).getPlatform().getStorage().getMaxTransferRateMbPerSecond(), 0.0);
        for (double invalid : new double[] {0.0, -1.0}) {
            JsonObject root = base();
            root.getAsJsonObject("platform").addProperty("storageTransferMbPerSecond", invalid);
            rejectRead(root, "storageTransferMbPerSecond");
        }
        JsonObject text = base();
        text.getAsJsonObject("platform").addProperty("storageTransferMbPerSecond", "fast");
        rejectRead(text, "storageTransferMbPerSecond");
    }

    @Test
    void rejectsFractionalAndOutOfRangeIntegerSettingsWithoutTruncation() throws Exception {
        JsonObject bandwidth = base();
        bandwidth.getAsJsonObject("platform").addProperty("bandwidthMbPerSecond", 1.5);
        rejectRead(bandwidth, "bandwidthMbPerSecond");
        JsonObject deadline = base();
        deadline.getAsJsonObject("simulation").addProperty("deadlineSeconds", 4.5);
        rejectRead(deadline, "deadlineSeconds");
        JsonObject k = local(true);
        k.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("k", 4.5);
        rejectRead(k, "k must be an exact integer");
        JsonObject cores = local(true);
        cores.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("coreSwitchCount", 2.5);
        rejectRead(cores, "coreSwitchCount");
        JsonObject placement = local(true);
        JsonObject hosts = new JsonObject();
        hosts.addProperty("0", 0.5);
        hosts.addProperty("1", 1);
        placement.getAsJsonObject("platform").getAsJsonObject("networkTopology").add("hostEdgePlacements", hosts);
        rejectRead(placement, "hostEdgePlacements");
        for (String idField : Arrays.asList("taskId", "vmId")) {
            JsonObject matrix = matrix();
            matrix.getAsJsonObject("simulation")
                    .getAsJsonArray("taskCostMatrix")
                    .get(0)
                    .getAsJsonObject()
                    .addProperty(idField, 0.5);
            rejectRead(matrix, idField);
        }
        for (JsonElement seed : Arrays.<JsonElement>asList(
                new JsonPrimitive(1.5),
                new JsonPrimitive("17"),
                new JsonPrimitive(new BigInteger("9223372036854775808")))) {
            JsonObject root = base();
            root.getAsJsonArray("seeds").set(0, seed);
            rejectRead(root, "seeds");
        }
        JsonObject oversized = base();
        oversized.getAsJsonObject("platform").addProperty("bandwidthMbPerSecond", 1000000001L);
        rejectRead(oversized, "out of range");
    }

    @Test
    void rejectsArrivalCountAndDomainErrorsAndDuplicateSeeds() throws Exception {
        JsonObject mismatch = base();
        JsonArray arrivals = new JsonArray();
        arrivals.add(0);
        arrivals.add(1);
        mismatch.add("workflowArrivalSeconds", arrivals);
        rejectRead(mismatch, "must match workflowPaths");
        for (JsonElement arrival : Arrays.<JsonElement>asList(
                new JsonPrimitive(-0.1),
                new JsonPrimitive("0"),
                new JsonPrimitive(new BigDecimal("1e999")),
                JsonNull.INSTANCE)) {
            JsonObject root = base();
            JsonArray one = new JsonArray();
            one.add(arrival);
            root.add("workflowArrivalSeconds", one);
            rejectRead(root, "workflowArrivalSeconds");
        }
        JsonObject duplicates = base();
        duplicates.getAsJsonArray("seeds").add(17L);
        rejectRead(duplicates, "distinct integers");
        JsonObject empty = base();
        empty.add("seeds", new JsonArray());
        rejectRead(empty, "seeds requires");
    }

    @Test
    void permitsComparisonsWithinEachLayerAndRejectsMixedDecisionLayers() throws Exception {
        JsonObject online = base();
        algorithms(online, algorithm("fcfs", "FCFS", null), algorithm("minmin", "READY_BATCH_MINMIN", null));
        assertDoesNotThrow(() -> read(online).validateInputs());
        JsonObject staticDag = base();
        algorithms(
                staticDag,
                algorithm("heft", null, "SHARED_STORAGE_HEFT"),
                algorithm("cpop", null, "SHARED_STORAGE_CPOP"));
        assertDoesNotThrow(() -> read(staticDag).validateInputs());
        JsonObject mixed = base();
        algorithms(mixed, algorithm("fcfs", "FCFS", null), algorithm("heft", null, "SHARED_STORAGE_HEFT"));
        rejectRead(mixed, "different decision layers");
        JsonObject independentAndDag = base();
        algorithms(
                independentAndDag,
                algorithm("minmin", null, "STATIC_MINMIN"),
                algorithm("heft", null, "SHARED_STORAGE_HEFT"));
        rejectRead(independentAndDag, "different decision layers");
        JsonObject onlineAndIndependent = base();
        algorithms(onlineAndIndependent, algorithm("fcfs", "FCFS", null), algorithm("minmin", null, "STATIC_MINMIN"));
        rejectRead(onlineAndIndependent, "different decision layers");
    }

    @Test
    void rejectsUnsafeDuplicateUnsupportedAndIncompatibleAlgorithmSelections() throws Exception {
        JsonObject unsafe = base();
        algorithms(unsafe, algorithm("../escape", "FCFS", null));
        rejectRead(unsafe, "safe identifiers");
        JsonObject duplicates = base();
        algorithms(duplicates, algorithm("same", "FCFS", null), algorithm("same", "READY_BATCH_MCT", null));
        rejectRead(duplicates, "unique");
        JsonObject invalidLabel = base();
        algorithms(invalidLabel, algorithm("candidate", "INVALID", null));
        rejectRead(invalidLabel, "maintained built-in algorithms");
        for (String removed : Arrays.asList("MINMIN", "RL_POLICY")) {
            JsonObject root = base();
            algorithms(root, algorithm("candidate", removed, null));
            rejectRead(root, "No enum constant");
        }
        JsonObject noPlanner = base();
        algorithms(noPlanner, algorithm("static", "STATIC", null));
        rejectRead(noPlanner, "requires a planning algorithm");
        JsonObject localWithShared = base();
        algorithms(localWithShared, algorithm("local", null, "LOCAL_HEFT"));
        rejectRead(localWithShared, "LOCAL");
        JsonObject noCandidates = base();
        noCandidates.add("algorithms", new JsonArray());
        rejectRead(noCandidates, "needs 1..500 runs");
    }

    @Test
    void requiresTopologyAndFatTreeMovementTogetherAndValidatesPhysicalPlacement() throws Exception {
        JsonObject valid = local(true);
        JsonObject hosts = new JsonObject();
        hosts.addProperty("0", 0);
        hosts.addProperty("1", 1);
        JsonObject topology = valid.getAsJsonObject("platform").getAsJsonObject("networkTopology");
        topology.addProperty("coreSwitchCount", 2);
        topology.add("hostEdgePlacements", hosts);
        assertDoesNotThrow(() -> read(valid).validateInputs());

        JsonObject missing = local(true);
        missing.getAsJsonObject("platform").remove("networkTopology");
        rejectRead(missing, "must be selected together");
        JsonObject unused = base();
        unused.getAsJsonObject("platform").add("networkTopology", topology());
        rejectRead(unused, "must be selected together");
        JsonObject odd = local(true);
        odd.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("k", 3);
        rejectRead(odd, "even integer");
        JsonObject excessive = local(true);
        excessive.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("coreSwitchCount", 5);
        rejectRead(excessive, "core switch count");
        JsonObject incomplete = valid.deepCopy();
        incomplete
                .getAsJsonObject("platform")
                .getAsJsonObject("networkTopology")
                .getAsJsonObject("hostEdgePlacements")
                .remove("1");
        rejectRead(incomplete, "must cover host");
        JsonObject unknown = valid.deepCopy();
        unknown.getAsJsonObject("platform")
                .getAsJsonObject("networkTopology")
                .getAsJsonObject("hostEdgePlacements")
                .addProperty("2", 2);
        rejectRead(unknown, "unknown host");
        JsonObject edgeOutside = valid.deepCopy();
        edgeOutside
                .getAsJsonObject("platform")
                .getAsJsonObject("networkTopology")
                .getAsJsonObject("hostEdgePlacements")
                .addProperty("0", 8);
        rejectRead(edgeOutside, "edge switch index");
        JsonObject nonCanonical = valid.deepCopy();
        nonCanonical
                .getAsJsonObject("platform")
                .getAsJsonObject("networkTopology")
                .getAsJsonObject("hostEdgePlacements")
                .addProperty("01", 1);
        rejectRead(nonCanonical, "Invalid host placement ID");

        JsonObject overloaded = localConfiguration("workflow.dax", 3, true);
        JsonObject allOnOneEdge = new JsonObject();
        for (int host = 0; host < 3; host++) {
            allOnOneEdge.addProperty(Integer.toString(host), 0);
        }
        overloaded
                .getAsJsonObject("platform")
                .getAsJsonObject("networkTopology")
                .add("hostEdgePlacements", allOnOneEdge);
        rejectRead(overloaded, "hosts at most");
    }

    @Test
    void fixedEndpointParametersRequireTheMatchingModelAndPositiveBandwidths() throws Exception {
        JsonObject valid = fixed();
        assertDoesNotThrow(() -> read(valid).validateInputs());
        JsonObject unused = fixed();
        unused.getAsJsonObject("simulation").addProperty("dataMovementModel", "LEGACY_WORKFLOWSIM_V1");
        rejectRead(unused, "fixedEndpoint parameters require");
        JsonObject missing = fixed();
        missing.getAsJsonObject("simulation").remove("fixedEndpoint");
        rejectRead(missing, "fixedEndpoint must be an object");
        JsonObject zero = fixed();
        zero.getAsJsonObject("simulation").getAsJsonObject("fixedEndpoint").addProperty("accessBandwidth", 0);
        rejectRead(zero, "accessBandwidth");
    }

    @Test
    void matrixMustCoverEveryParsedTaskAndVmAndRejectDuplicateOrMissingCells() throws Exception {
        JsonObject complete = matrix();
        assertDoesNotThrow(() -> read(complete).validateInputs());
        JsonObject missingVm = matrix();
        missingVm.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix").remove(1);
        rejectInputs(missingVm, "cover every parsed task and VM");
        JsonObject missingTask = matrix();
        JsonArray entries = missingTask.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix");
        entries.remove(3);
        entries.remove(2);
        rejectInputs(missingTask, "cover every parsed task and VM");
        JsonObject duplicate = matrix();
        JsonArray repeated = duplicate.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix");
        repeated.add(repeated.get(0).deepCopy());
        rejectRead(duplicate, "Duplicate task cost matrix entry");
        JsonObject empty = matrix();
        empty.getAsJsonObject("simulation").add("taskCostMatrix", new JsonArray());
        rejectRead(empty, "must contain at least one entry");
        JsonObject online = matrix();
        algorithms(online, algorithm("fcfs", "FCFS", null));
        rejectRead(online, "STATIC");
    }

    @Test
    void independentPlannersRejectEdgesWhileDagPlannersAcceptTheSameInput() throws Exception {
        JsonObject independent = base();
        algorithms(independent, algorithm("minmin", null, "STATIC_MINMIN"));
        rejectInputs(independent, "cannot accept DAG edges");
        JsonObject dag = base();
        algorithms(dag, algorithm("heft", null, "SHARED_STORAGE_HEFT"));
        assertDoesNotThrow(() -> read(dag).validateInputs());
        workflow(directory, "independent.dax", false);
        JsonObject allowed = configuration("independent.dax", 2);
        algorithms(allowed, algorithm("minmin", null, "STATIC_MINMIN"), algorithm("maxmin", null, "STATIC_MAXMIN"));
        assertDoesNotThrow(() -> read(allowed).validateInputs());
    }

    @Test
    void inputValidationRejectsCyclesUnsupportedFormatsAndAmbiguousLocalFiles() throws Exception {
        Path cycle = text(
                directory.resolve("cycle.dax"),
                "<adag version=\"3.3\">"
                        + "<job id=\"a\" runtime=\"1\"/><job id=\"b\" runtime=\"1\"/>"
                        + "<child ref=\"a\"><parent ref=\"b\"/></child>"
                        + "<child ref=\"b\"><parent ref=\"a\"/></child></adag>");
        rejectInputs(configuration(cycle.getFileName().toString(), 1), "cycle");
        text(directory.resolve("workflow.txt"), "not a supported workflow extension");
        rejectInputs(configuration("workflow.txt", 1), "Unsupported workflow input format");
        Path ambiguous = workflow(directory, "ambiguous.dax", true);
        text(ambiguous, text(ambiguous).replace("link=\"input\" size=\"1000000\"", "link=\"input\" size=\"2000000\""));
        rejectInputs(localConfiguration("ambiguous.dax", 2, false), "unambiguous file sizes");
    }

    private JsonObject base() throws Exception {
        workflow(directory, "workflow.dax", true);
        return configuration("workflow.dax", 2);
    }

    private JsonObject local(boolean fatTree) throws Exception {
        base();
        return localConfiguration("workflow.dax", 2, fatTree);
    }

    private JsonObject fixed() throws Exception {
        JsonObject root = base();
        JsonObject sim = root.getAsJsonObject("simulation");
        sim.addProperty("dataMovementModel", "FIXED_ENDPOINT_NO_CONTENTION_V1");
        JsonObject fixed = new JsonObject();
        fixed.addProperty("accessBandwidth", 10.0);
        fixed.addProperty("sourceBandwidth", 10.0);
        fixed.addProperty("latencySeconds", 0.0);
        sim.add("fixedEndpoint", fixed);
        return root;
    }

    private JsonObject matrix() throws Exception {
        JsonObject root = base();
        algorithms(root, algorithm("random", null, "RANDOM"));
        root.getAsJsonObject("simulation").add("taskCostMatrix", costs(2, 2, 1.0));
        return root;
    }

    private WorkbenchConfig read(JsonObject root) throws Exception {
        return WorkbenchConfig.read(json(directory.resolve("config.json"), root));
    }

    private void rejectRead(JsonObject root, String message) throws Exception {
        Path config = json(directory.resolve("config.json"), root);
        assertContains(assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(config)), message);
    }

    private void rejectInputs(JsonObject root, String message) throws Exception {
        WorkbenchConfig config = read(root);
        assertContains(assertThrows(IllegalArgumentException.class, config::validateInputs), message);
    }

    private static void assertContains(Throwable failure, String message) {
        assertTrue(
                failure.getMessage().toLowerCase(Locale.ROOT).contains(message.toLowerCase(Locale.ROOT)),
                "Expected diagnostic containing '" + message + "', got: " + failure.getMessage());
    }
}
