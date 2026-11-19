package org.workflowsim.experiments.fgcs.verification;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Focused RQ1/RQ2 adversarial-evidence driver for coherent V2/V3 artifacts. */
public final class AdversarialEvidenceStudy {
    static final String JAVA_VALIDATOR = "JAVA_EXPERIMENT_ARTIFACT_VALIDATOR";
    static final String PYTHON_VALIDATOR = "PYTHON_VERIFY_NETWORK_LEDGER";

    private static final String ACCEPT = "ACCEPT";
    private static final String REJECT = "REJECT";

    private AdversarialEvidenceStudy() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: AdversarialEvidenceStudy [output-directory]");
        }
        Path output = args.length == 0 ? Paths.get("paper/tmp/experiments/verification") : Paths.get(args[0]);
        JsonObject report = run(output);
        JsonObject rq2 = report.getAsJsonObject("rq2");
        System.out.println("FGCS adversarial verification genuine="
                + report.getAsJsonObject("rq1").get("genuineCases")
                + " corruptions=" + rq2.get("corruptedCases") + " observations=" + rq2.get("validatorObservations")
                + " expectedRejections=" + rq2.get("expectedRejections") + " actualRejections="
                + rq2.get("actualRejections") + " rejectedCases=" + rq2.get("casesRejectedByAtLeastOneValidator")
                + " output=" + output.resolve("results.json").toAbsolutePath().normalize());
        if (rq2.get("unexpectedOutcomes").getAsInt() != 0
                || rq2.get("reasonMismatches").getAsInt() != 0) {
            throw new IllegalStateException(
                    "Adversarial outcomes diverged from the hand-derived protocol; inspect results.json");
        }
    }

    /** Runs simulations serially, writes every mutated bundle, and returns the persisted result document. */
    public static JsonObject run(Path outputDirectory) throws Exception {
        Path output = outputDirectory.toAbsolutePath().normalize();
        Path evidence = output.resolve("evidence");
        Files.createDirectories(evidence);
        Path repository = repositoryRoot();
        Path python = repository.resolve("scripts/verify-network-ledger.py");
        List<AdversarialResultsWriter.CaseResult> cases = new ArrayList<AdversarialResultsWriter.CaseResult>();
        boolean loggingDisabled = Log.isDisabled();
        Log.disable();
        try {
            Path workflow = writeChain(evidence.resolve("inputs/chain.dax"));
            Path v2 = generate(workflow, evidence.resolve("genuine-v2"), false);
            addGenuine(cases, output, v2, "V2", python, repository);
            Path v3 = generate(workflow, evidence.resolve("genuine-v3"), true);
            addGenuine(cases, output, v3, "V3", python, repository);

            addCorruption(
                    cases,
                    output,
                    v2,
                    "v2-parent-before-child-request",
                    "V2",
                    "Move a child's JOB_INPUT_REQUESTED immediately before its parent's successful "
                            + "TASK_FINISHED, set both observations equal, and resequence the lifecycle.",
                    "A request for logical Task C is valid only after every control parent of C has a "
                            + "successful terminal outcome; equal timestamps do not erase sequence causality.",
                    AdversarialEvidenceStudy::parentBeforeChild,
                    reject(
                            "The lifecycle replay must reject the request because its control parent "
                                    + "is not yet successful.",
                            "Control parent"),
                    reject(
                            "The lifecycle replay must reject the request because its control parent "
                                    + "is not yet successful.",
                            "control-parent"),
                    python,
                    repository);
            addCorruption(
                    cases,
                    output,
                    v2,
                    "v2-task-identity-mismatch",
                    "V2",
                    "Replace TASK_EXECUTION_MODELED.attributes.taskId with a different declared Task "
                            + "while retaining the event's jobId and taskIds envelope.",
                    "For singleton NONE-clustered execution, attributes.taskId must equal the sole "
                            + "taskIds member associated with that compute Job.",
                    AdversarialEvidenceStudy::taskIdentityMismatch,
                    reject("The CPU envelope must disagree with the final singleton Task identity.", "Task timing"),
                    reject("The CPU envelope must disagree with the final singleton Task identity.", "Task result"),
                    python,
                    repository);
            addCorruption(
                    cases,
                    output,
                    v2,
                    "v2-source-provenance-mismatch",
                    "V2",
                    "Change an input COPY_ADMITTED sourceReplica.origin.producerTaskId to another "
                            + "declared Task without changing the file or visible holder.",
                    "A selected source replica must preserve the root producer and attempt provenance "
                            + "of the visible replica chosen from the replayed catalog.",
                    AdversarialEvidenceStudy::sourceProvenanceMismatch,
                    reject("Independent catalog replay must reject fabricated producer provenance.", "provenance"),
                    reject("Independent catalog replay must reject fabricated producer provenance.", "provenance"),
                    python,
                    repository);
            addCorruption(
                    cases,
                    output,
                    v2,
                    "v2-reordered-decision-dispatch",
                    "V2",
                    "Swap SCHEDULING_DECISION and JOB_DISPATCHED for one compute Job, then renumber "
                            + "every main-event sequence and refresh the event summary.",
                    "The per-Job causal order requires SCHEDULING_DECISION before JOB_DISPATCHED "
                            + "even when both events have the same simulation timestamp.",
                    AdversarialEvidenceStudy::reorderDecisionDispatch,
                    accept("Current Java artifact validation covers sequence contiguity and anchors "
                            + "but not this equal-time decision/dispatch causal swap."),
                    reject(
                            "The Python context checks per-Job anchor sequence causality and must "
                                    + "reject the swap.",
                            "sequence causality"),
                    python,
                    repository);
            addCorruption(
                    cases,
                    output,
                    v2,
                    "v2-missing-simulation-terminal",
                    "V2",
                    "Delete SIMULATION_TERMINATED, renumber the remaining main events, and update "
                            + "eventCount/firstSequence/lastSequence.",
                    "A manifest declaring the events revision contract must retain exactly one "
                            + "terminal event at the end of the stream.",
                    AdversarialEvidenceStudy::missingTerminal,
                    reject(
                            "The declared Java events-v1 revision contract requires exactly one " + "terminal marker.",
                            "SIMULATION_TERMINATED"),
                    accept("The selected Python lifecycle context validates lifecycle/main-job "
                            + "causality but does not implement the general terminal-marker contract."),
                    python,
                    repository);
            addCorruption(
                    cases,
                    output,
                    v2,
                    "v2-makespan-metric-mismatch",
                    "V2",
                    "Advance result.simulationEndSeconds and the terminal event by 0.5 seconds "
                            + "while leaving manifest and sidecar makespanSeconds unchanged.",
                    "For manifest v4, the declared makespan metric must equal the terminal simulation "
                            + "end independently of matching metrics-sidecar copies.",
                    AdversarialEvidenceStudy::makespanMismatch,
                    reject(
                            "Java manifest-v4 metric recomputation must expose the stale makespan " + "metric.",
                            "makespanSeconds"),
                    accept("The selected Python lifecycle context does not recompute makespanSeconds "
                            + "from the terminal result."),
                    python,
                    repository);
            addCorruption(
                    cases,
                    output,
                    v2,
                    "v2-energy-metric-out-of-scope",
                    "V2",
                    "Increase energyMetrics.totalEnergyJoules by one joule without changing per-VM "
                            + "energy or busy intervals.",
                    "Energy conservation requires total energy to agree with per-VM power integrated "
                            + "over busy intervals; this probe measures whether the two selected "
                            + "validators check that law.",
                    AdversarialEvidenceStudy::energyMismatch,
                    accept("ExperimentArtifactValidator does not recompute energyMetrics; this is a "
                            + "measured boundary, not a valid energy certificate."),
                    accept("The Python lifecycle validator does not recompute energyMetrics; the "
                            + "separate study-evidence energy auditor is outside this matrix."),
                    python,
                    repository);

            addCorruption(
                    cases,
                    output,
                    v3,
                    "v3-output-settlement-missing",
                    "V3",
                    "Delete the final OUTPUT copy's COPY_SETTLED, resequence the lifecycle, and "
                            + "decrement capture.retainedRecords while keeping COMPLETE and zero drops.",
                    "A complete final V3 bundle must be quiescent and every successful output must "
                            + "drain to SOURCE; an active output upload cannot be certified as closed.",
                    AdversarialEvidenceStudy::missingOutputSettlement,
                    reject("The final V3 context must reject the undrained output as non-quiescent.", "quiescent"),
                    reject("The final V3 context must reject the undrained output as non-quiescent.", "quiescent"),
                    python,
                    repository);
            addCorruption(
                    cases,
                    output,
                    v3,
                    "v3-source-provenance-mismatch",
                    "V3",
                    "Change an INPUT COPY_ADMITTED sourceReplica.origin.producerTaskId to another "
                            + "declared Task without changing its SOURCE/VM holder or destination.",
                    "The bounded-storage replay must retain the visible source's original producer "
                            + "provenance independently of recomputed outer hashes.",
                    AdversarialEvidenceStudy::sourceProvenanceMismatch,
                    reject(
                            "Independent storage catalog replay must reject fabricated producer " + "provenance.",
                            "provenance"),
                    reject(
                            "Independent storage catalog replay must reject fabricated producer " + "provenance.",
                            "provenance"),
                    python,
                    repository);
        } finally {
            Log.setDisabled(loggingDisabled);
        }
        return AdversarialResultsWriter.write(output.resolve("results.json"), cases);
    }

    private static Path writeChain(Path output) throws IOException {
        StringBuilder dax = new StringBuilder("<adag version=\"3.3\" count=\"4\">\n");
        String[] runtimes = {"10", "20", "30", "40"};
        for (int task = 1; task <= 4; task++) {
            dax.append("<job id=\"j")
                    .append(task)
                    .append("\" name=\"task")
                    .append(task)
                    .append("\" runtime=\"")
                    .append(runtimes[task - 1])
                    .append("\">");
            dax.append("<uses name=\"f")
                    .append(task)
                    .append("\" link=\"output\" size=\"")
                    .append(task * 1000000)
                    .append("\"/>");
            if (task > 1) {
                dax.append("<uses name=\"f")
                        .append(task - 1)
                        .append("\" link=\"input\" size=\"")
                        .append((task - 1) * 1000000)
                        .append("\"/>");
            }
            dax.append("</job>\n");
        }
        for (int task = 2; task <= 4; task++) {
            dax.append("<child ref=\"j")
                    .append(task)
                    .append("\"><parent ref=\"j")
                    .append(task - 1)
                    .append("\"/></child>\n");
        }
        dax.append("</adag>\n");
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.write(output, dax.toString().getBytes(StandardCharsets.UTF_8));
        return output;
    }

    private static Path generate(Path workflow, Path output, boolean storage) throws Exception {
        SimulationConfig config = FgcStudySupport.base(workflow.toString(), 2)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(
                        storage
                                ? DataMovementModel.coherentStorageDataflowV3()
                                : DataMovementModel.coherentFileDataflowV2())
                .networkEvidence(
                        storage
                                ? NetworkEvidenceConfig.storageLifecycleV3(10000)
                                : NetworkEvidenceConfig.fileLifecycleV2(10000))
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SimulationReport report =
                new SimulationRunner().run(config, storage ? FgcPlatforms.storage(2, 1) : FgcPlatforms.flat(2, 1));
        ExperimentArtifactWriter.write(report, output, "result");
        return output.toAbsolutePath().normalize();
    }

    private static void addGenuine(
            List<AdversarialResultsWriter.CaseResult> cases,
            Path output,
            Path bundle,
            String family,
            Path python,
            Path repository)
            throws Exception {
        AdversarialResultsWriter.CaseResult testCase = new AdversarialResultsWriter.CaseResult(
                "genuine-" + family.toLowerCase(),
                family,
                true,
                "None: direct SimulationRunner/ExperimentArtifactWriter output.",
                "A complete successful small chain with consistent lifecycle, main events, result, "
                        + "metrics, and artifact references must validate.",
                relative(output, bundle.resolve("result.manifest.json")));
        Expectation expectation =
                accept("Unmodified writer output must satisfy the validator's declared complete-bundle contract.");
        evaluate(testCase, bundle.resolve("result.manifest.json"), expectation, expectation, python, repository);
        cases.add(testCase);
    }

    private static void addCorruption(
            List<AdversarialResultsWriter.CaseResult> cases,
            Path output,
            Path baseline,
            String id,
            String family,
            String description,
            String invariant,
            Mutation mutation,
            Expectation java,
            Expectation pythonExpectation,
            Path python,
            Path repository)
            throws Exception {
        Path directory = output.resolve("evidence/cases").resolve(id);
        AdversarialBundle.copyDirectory(baseline, directory);
        AdversarialBundle bundle = AdversarialBundle.open(directory);
        mutation.apply(bundle);
        bundle.commit();
        AdversarialResultsWriter.CaseResult testCase = new AdversarialResultsWriter.CaseResult(
                id, family, false, description, invariant, relative(output, bundle.manifestPath()));
        evaluate(testCase, bundle.manifestPath(), java, pythonExpectation, python, repository);
        cases.add(testCase);
    }

    private static void evaluate(
            AdversarialResultsWriter.CaseResult testCase,
            Path manifest,
            Expectation java,
            Expectation python,
            Path script,
            Path repository)
            throws Exception {
        Actual javaActual = validateJava(manifest);
        testCase.add(observation(JAVA_VALIDATOR, java, javaActual));
        Actual pythonActual = validatePython(manifest, script, repository);
        testCase.add(observation(PYTHON_VALIDATOR, python, pythonActual));
    }

    private static AdversarialResultsWriter.Observation observation(
            String validator, Expectation expected, Actual actual) {
        return new AdversarialResultsWriter.Observation(
                validator, expected.outcome, actual.outcome, expected.reason, actual.reason, expected.reasonProbe);
    }

    private static Actual validateJava(Path manifest) {
        try {
            ExperimentArtifactValidator.ValidationResult result = ExperimentArtifactValidator.validate(manifest);
            return new Actual(ACCEPT, "Accepted complete manifest-v4 bundle; events=" + result.getEventCount());
        } catch (IOException | IllegalArgumentException exception) {
            return new Actual(REJECT, describe(exception));
        }
    }

    private static Actual validatePython(Path manifest, Path script, Path repository) throws Exception {
        Process process = new ProcessBuilder("python3", "-B", script.toString(), manifest.toString(), "--json")
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<IOException>();
        Thread reader = new Thread(
                () -> {
                    try (InputStream input = process.getInputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            bytes.write(buffer, 0, count);
                        }
                    } catch (IOException exception) {
                        readFailure.set(exception);
                    }
                },
                "fgcs-python-validator-output");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Python validator timed out for " + manifest);
        }
        reader.join(5000L);
        if (reader.isAlive()) {
            throw new IOException("Cannot finish reading Python validator output for " + manifest);
        }
        if (readFailure.get() != null) {
            throw readFailure.get();
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
        int exit = process.exitValue();
        if (exit != 0 && exit != 1) {
            throw new IOException("Python validator execution failed with exit " + exit + ": " + text);
        }
        JsonObject report;
        try {
            report = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException(
                    "Python validator did not emit its JSON verdict for " + manifest + ": " + text, invalid);
        }
        if (exit == 0) {
            return new Actual(
                    ACCEPT,
                    "status=" + report.get("status").getAsString() + "; scope="
                            + report.get("scope").getAsString());
        }
        if (!"EVIDENCE_INVALID".equals(report.get("status").getAsString())) {
            throw new IOException("Python validator exit 1 without EVIDENCE_INVALID for " + manifest + ": " + text);
        }
        return new Actual(REJECT, report.get("error").getAsString());
    }

    private static void parentBeforeChild(AdversarialBundle bundle) throws IOException {
        JsonObject lifecycle = bundle.readLifecycle();
        JsonArray events = lifecycle.getAsJsonArray("events");
        JsonObject request = null;
        JsonObject parentFinish = null;
        outer:
        for (JsonElement taskElement : lifecycle.getAsJsonObject("filePlan").getAsJsonArray("tasks")) {
            JsonObject task = taskElement.getAsJsonObject();
            JsonArray parents = task.getAsJsonArray("parents");
            if (parents.size() == 0) {
                continue;
            }
            int child = task.get("taskId").getAsInt();
            int parent = parents.get(0).getAsInt();
            for (JsonElement element : events) {
                JsonObject event = element.getAsJsonObject();
                JsonObject payload = event.getAsJsonObject("payload");
                if ("JOB_INPUT_REQUESTED".equals(type(event))
                        && payload.getAsJsonArray("taskIds").get(0).getAsInt() == child) {
                    request = event;
                }
                if ("TASK_FINISHED".equals(type(event))
                        && payload.get("taskId").getAsInt() == parent
                        && payload.get("success").getAsBoolean()) {
                    parentFinish = event;
                }
                if (request != null && parentFinish != null) {
                    break outer;
                }
            }
        }
        require(request != null && parentFinish != null, "The chain must provide a parent/child request pair");
        int requestIndex = indexOfIdentity(events, request);
        events.remove(requestIndex);
        int parentIndex = indexOfIdentity(events, parentFinish);
        request.addProperty("observedTime", parentFinish.get("observedTime").getAsDouble());
        JsonArray reordered = new JsonArray();
        for (int index = 0; index <= events.size(); index++) {
            if (index == parentIndex) {
                reordered.add(request);
            }
            if (index < events.size()) {
                reordered.add(events.get(index));
            }
        }
        lifecycle.add("events", reordered);
        bundle.writeLifecycle(lifecycle);
    }

    private static void taskIdentityMismatch(AdversarialBundle bundle) throws IOException {
        List<JsonObject> events = bundle.readMainEvents();
        List<Integer> taskIds = new ArrayList<Integer>();
        for (JsonElement element : bundle.manifest().getAsJsonArray("workflowGraph")) {
            taskIds.add(element.getAsJsonObject().get("taskId").getAsInt());
        }
        boolean changed = false;
        for (JsonObject event : events) {
            JsonObject attributes = event.getAsJsonObject("attributes");
            if (!"TASK_EXECUTION_MODELED".equals(type(event)) || attributes == null || !attributes.has("taskId")) {
                continue;
            }
            int original = event.getAsJsonArray("taskIds").get(0).getAsInt();
            for (int candidate : taskIds) {
                if (candidate != original) {
                    attributes.addProperty("taskId", candidate);
                    changed = true;
                    break;
                }
            }
            if (changed) {
                break;
            }
        }
        require(changed, "The chain must provide a compute Task identity to corrupt");
        bundle.writeMainEvents(events);
    }

    private static void sourceProvenanceMismatch(AdversarialBundle bundle) throws IOException {
        JsonObject lifecycle = bundle.readLifecycle();
        List<Integer> taskIds = new ArrayList<Integer>();
        for (JsonElement element : lifecycle.getAsJsonObject("filePlan").getAsJsonArray("tasks")) {
            taskIds.add(element.getAsJsonObject().get("taskId").getAsInt());
        }
        boolean changed = false;
        for (JsonElement element : lifecycle.getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            JsonObject payload = event.getAsJsonObject("payload");
            if (!"COPY_ADMITTED".equals(type(event))
                    || (payload.has("purpose")
                            && !"INPUT".equals(payload.get("purpose").getAsString()))) {
                continue;
            }
            JsonObject origin = payload.getAsJsonObject("sourceReplica").getAsJsonObject("origin");
            if (origin.get("producerTaskId").isJsonNull()) {
                continue;
            }
            int original = origin.get("producerTaskId").getAsInt();
            for (int candidate : taskIds) {
                if (candidate != original) {
                    origin.addProperty("producerTaskId", candidate);
                    changed = true;
                    break;
                }
            }
            if (changed) {
                break;
            }
        }
        require(changed, "The chain must provide a task-produced input source replica");
        bundle.writeLifecycle(lifecycle);
    }

    private static void reorderDecisionDispatch(AdversarialBundle bundle) throws IOException {
        List<JsonObject> events = bundle.readMainEvents();
        boolean changed = false;
        for (int decisionIndex = 0; decisionIndex < events.size() && !changed; decisionIndex++) {
            JsonObject decision = events.get(decisionIndex);
            if (!"SCHEDULING_DECISION".equals(type(decision))
                    || decision.get("classType").getAsInt() != Parameters.ClassType.COMPUTE.value) {
                continue;
            }
            long jobId = decision.get("jobId").getAsLong();
            for (int dispatchIndex = 0; dispatchIndex < events.size(); dispatchIndex++) {
                JsonObject dispatch = events.get(dispatchIndex);
                if ("JOB_DISPATCHED".equals(type(dispatch))
                        && dispatch.get("classType").getAsInt() == Parameters.ClassType.COMPUTE.value
                        && dispatch.get("jobId").getAsLong() == jobId) {
                    events.set(decisionIndex, dispatch);
                    events.set(dispatchIndex, decision);
                    changed = true;
                    break;
                }
            }
        }
        require(changed, "The chain must provide a decision/dispatch pair");
        bundle.writeMainEvents(events);
    }

    private static void missingTerminal(AdversarialBundle bundle) throws IOException {
        List<JsonObject> events = bundle.readMainEvents();
        boolean changed = false;
        for (int index = events.size() - 1; index >= 0; index--) {
            if ("SIMULATION_TERMINATED".equals(type(events.get(index)))) {
                events.remove(index);
                changed = true;
                break;
            }
        }
        require(changed, "The genuine run must have a terminal event");
        bundle.writeMainEvents(events);
    }

    private static void makespanMismatch(AdversarialBundle bundle) throws IOException {
        JsonObject result = bundle.manifest().getAsJsonObject("result");
        double corruptedEnd = result.get("simulationEndSeconds").getAsDouble() + 0.5;
        result.addProperty("simulationEndSeconds", corruptedEnd);
        List<JsonObject> events = bundle.readMainEvents();
        int terminals = 0;
        for (JsonObject event : events) {
            if ("SIMULATION_TERMINATED".equals(type(event))) {
                event.addProperty("simulationTime", corruptedEnd);
                event.getAsJsonObject("attributes").addProperty("simulationEndSeconds", corruptedEnd);
                terminals++;
            }
        }
        require(terminals == 1, "The genuine run must have exactly one terminal event");
        bundle.writeMainEvents(events);
    }

    private static void energyMismatch(AdversarialBundle bundle) {
        JsonObject energy = bundle.manifest().getAsJsonObject("energyMetrics");
        double original = energy.get("totalEnergyJoules").getAsDouble();
        energy.addProperty("totalEnergyJoules", original + 1.0);
    }

    private static void missingOutputSettlement(AdversarialBundle bundle) throws IOException {
        JsonObject lifecycle = bundle.readLifecycle();
        JsonArray events = lifecycle.getAsJsonArray("events");
        Long outputOrdinal = null;
        for (JsonElement element : events) {
            JsonObject event = element.getAsJsonObject();
            JsonObject payload = event.getAsJsonObject("payload");
            if ("COPY_ADMITTED".equals(type(event))
                    && payload.has("purpose")
                    && "OUTPUT".equals(payload.get("purpose").getAsString())) {
                outputOrdinal = payload.get("copyOrdinal").getAsLong();
            }
        }
        require(outputOrdinal != null, "The V3 chain must contain an output upload");
        boolean changed = false;
        for (int index = events.size() - 1; index >= 0; index--) {
            JsonObject event = events.get(index).getAsJsonObject();
            if ("COPY_SETTLED".equals(type(event))
                    && event.getAsJsonObject("payload").get("copyOrdinal").getAsLong() == outputOrdinal) {
                events.remove(index);
                changed = true;
                break;
            }
        }
        require(changed, "The selected output upload must have a genuine settlement to remove");
        bundle.writeLifecycle(lifecycle);
    }

    private static int indexOfIdentity(JsonArray events, JsonObject sought) {
        Set<JsonObject> identities = Collections.newSetFromMap(new IdentityHashMap<JsonObject, Boolean>());
        identities.add(sought);
        for (int index = 0; index < events.size(); index++) {
            if (identities.contains(events.get(index).getAsJsonObject())) {
                return index;
            }
        }
        throw new IllegalArgumentException("Event is not present in its source array");
    }

    private static String type(JsonObject event) {
        return event.get("type").getAsString();
    }

    private static String relative(Path output, Path manifest) {
        return output.relativize(manifest.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("scripts/verify-network-ledger.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Cannot locate scripts/verify-network-ledger.py from the working directory");
    }

    private static Expectation reject(String reason, String reasonProbe) {
        return new Expectation(REJECT, reason, reasonProbe);
    }

    private static Expectation accept(String reason) {
        return new Expectation(ACCEPT, reason, null);
    }

    private static String describe(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = throwable; current != null && seen.add(current); current = current.getCause()) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(current.getClass().getSimpleName()).append(": ");
            text.append(current.getMessage() == null ? "no message" : current.getMessage());
        }
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private interface Mutation {
        void apply(AdversarialBundle bundle) throws Exception;
    }

    private static final class Expectation {
        private final String outcome;
        private final String reason;
        private final String reasonProbe;

        private Expectation(String outcome, String reason, String reasonProbe) {
            this.outcome = outcome;
            this.reason = reason;
            this.reasonProbe = reasonProbe;
        }
    }

    private static final class Actual {
        private final String outcome;
        private final String reason;

        private Actual(String outcome, String reason) {
            this.outcome = outcome;
            this.reason = reason;
        }
    }
}
