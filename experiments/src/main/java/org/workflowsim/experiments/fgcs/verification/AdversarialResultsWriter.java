package org.workflowsim.experiments.fgcs.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Compact, deterministic result projection; generated evidence stays outside the source tree. */
final class AdversarialResultsWriter {

    private AdversarialResultsWriter() {}

    static JsonObject write(Path output, List<CaseResult> cases) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", "FGCS_ADVERSARIAL_EVIDENCE_V1");
        root.addProperty("javaValidator", AdversarialEvidenceStudy.JAVA_VALIDATOR);
        root.addProperty("pythonValidator", AdversarialEvidenceStudy.PYTHON_VALIDATOR);
        root.addProperty("pythonCommand", "python3 -B scripts/verify-network-ledger.py <manifest> --json");
        root.add("rq1", summary(cases, true));
        root.add("rq2", summary(cases, false));
        JsonArray boundaries = new JsonArray();
        boundaries.add("The Java artifact validator checks contiguous main-event sequences but does not "
                + "reject the measured equal-time SCHEDULING_DECISION/JOB_DISPATCHED swap; the Python "
                + "lifecycle context rejects its causal-order violation.");
        boundaries.add("The Python lifecycle context does not enforce the events-v1 SIMULATION_TERMINATED "
                + "contract and does not recompute makespanSeconds; the Java manifest-v4 validator "
                + "performs both checks.");
        boundaries.add("Neither selected validator recomputes energyMetrics. The energy mismatch is an "
                + "expected-accept coverage probe, not a claim that the separate verify-study-evidence.py "
                + "energy auditor lacks coverage.");
        boundaries.add("V3 output-drain rejection certifies the declared ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3 "
                + "closure only; it is not a V1 fluid-rate-area, packet, or real-storage certification.");
        boundaries.add("Expected acceptance is a measured coverage boundary, not endorsement of corrupted "
                + "evidence. No case is generalized to every corruption, validator, schema, or physical model.");
        root.add("coverageBoundaries", boundaries);
        JsonArray rows = new JsonArray();
        for (CaseResult testCase : cases) {
            rows.add(testCase.toJson());
        }
        root.add("cases", rows);
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
                output.toAbsolutePath().normalize(),
                (new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    private static JsonObject summary(List<CaseResult> cases, boolean genuine) {
        int caseCount = 0;
        int observations = 0;
        int expectedRejections = 0;
        int actualRejections = 0;
        int expectedAcceptances = 0;
        int actualAcceptances = 0;
        int rejectedCases = 0;
        int unexpected = 0;
        int reasonMismatches = 0;
        for (CaseResult testCase : cases) {
            if (testCase.isGenuine() != genuine) {
                continue;
            }
            caseCount++;
            boolean caseRejected = false;
            for (Observation observation : testCase.observations) {
                observations++;
                expectedRejections += observation.expectedReject() ? 1 : 0;
                actualRejections += observation.actualReject() ? 1 : 0;
                expectedAcceptances += !observation.expectedReject() ? 1 : 0;
                actualAcceptances += !observation.actualReject() ? 1 : 0;
                caseRejected |= observation.actualReject();
                unexpected += observation.outcomeMatches() ? 0 : 1;
                reasonMismatches +=
                        observation.expectedReject() && observation.actualReject() && !observation.reasonMatched
                                ? 1
                                : 0;
            }
            rejectedCases += caseRejected ? 1 : 0;
        }
        JsonObject value = new JsonObject();
        String question = genuine
                ? "Do unmodified small coherent V2/V3 bundles from SimulationRunner pass both "
                        + "independent validators?"
                : "Which hand-derived semantic corruptions are rejected after every artifact hash "
                        + "and size is recomputed?";
        value.addProperty("researchQuestion", question);
        value.addProperty(genuine ? "genuineCases" : "corruptedCases", caseCount);
        value.addProperty("validatorObservations", observations);
        value.addProperty("expectedRejections", expectedRejections);
        value.addProperty("actualRejections", actualRejections);
        value.addProperty("expectedAcceptances", expectedAcceptances);
        value.addProperty("actualAcceptances", actualAcceptances);
        value.addProperty(
                genuine ? "casesAcceptedByBothValidators" : "casesRejectedByAtLeastOneValidator",
                genuine ? caseCount - rejectedCases : rejectedCases);
        value.addProperty("unexpectedOutcomes", unexpected);
        value.addProperty("reasonMismatches", reasonMismatches);
        return value;
    }

    static final class CaseResult {
        private final JsonObject value = new JsonObject();
        private final List<Observation> observations = new ArrayList<Observation>();
        private final boolean genuine;

        CaseResult(String id, String family, boolean genuine, String mutation, String invariant, String manifest) {
            this.genuine = genuine;
            value.addProperty("id", id);
            value.addProperty("artifactFamily", family);
            value.addProperty("kind", genuine ? "GENUINE" : "CORRUPTED");
            value.addProperty("mutation", mutation);
            value.addProperty("handDerivedInvariant", invariant);
            value.addProperty("manifest", manifest);
            value.addProperty("allArtifactHashesRecomputed", !genuine);
        }

        boolean isGenuine() {
            return genuine;
        }

        void add(Observation observation) {
            observations.add(observation);
        }

        JsonObject toJson() {
            JsonArray rows = new JsonArray();
            for (Observation observation : observations) {
                rows.add(observation.toJson());
            }
            value.add("observations", rows);
            return value;
        }
    }

    static final class Observation {
        private final String validator;
        private final String expected;
        private final String actual;
        private final String expectedReason;
        private final String actualReason;
        private final String reasonProbe;
        private final boolean reasonMatched;

        Observation(
                String validator,
                String expected,
                String actual,
                String expectedReason,
                String actualReason,
                String reasonProbe) {
            this.validator = validator;
            this.expected = expected;
            this.actual = actual;
            this.expectedReason = expectedReason;
            this.actualReason = actualReason;
            this.reasonProbe = reasonProbe;
            this.reasonMatched = "ACCEPT".equals(expected)
                    ? "ACCEPT".equals(actual)
                    : "REJECT".equals(actual) && actualReason.contains(reasonProbe);
        }

        boolean expectedReject() {
            return "REJECT".equals(expected);
        }

        boolean actualReject() {
            return "REJECT".equals(actual);
        }

        boolean outcomeMatches() {
            return expected.equals(actual);
        }

        JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("validator", validator);
            value.addProperty("expected", expected);
            value.addProperty("actual", actual);
            value.addProperty("expectedReason", expectedReason);
            value.addProperty("actualReason", actualReason);
            value.addProperty("reasonMatched", reasonMatched);
            if (reasonProbe != null) {
                value.addProperty("requiredReasonFragment", reasonProbe);
            }
            return value;
        }
    }
}
