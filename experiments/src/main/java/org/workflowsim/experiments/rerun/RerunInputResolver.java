package org.workflowsim.experiments.rerun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Stage 2: input resolution (see the "input resolution rules" contract).
 *
 * <p>{@code inputs[].path} in the manifest is an absolute path from the original machine and
 * is unreliable across environments. Resolution follows a fixed three-tier order; as soon as
 * one tier hits (the file exists), sha256 and sizeBytes are verified immediately: a passing
 * check is accepted, a failing check terminates with {@code INPUT_HASH_MISMATCH} and never
 * degrades silently to the next candidate or substitutes another input. When no tier hits,
 * {@code INPUT_UNRESOLVED} is reported together with every candidate path that was tried.</p>
 *
 * <ol>
 *   <li>{@code inputs/<file name>} under the study output directory that owns the run
 *       directory ({@code <study>/runs/<run>} layout);</li>
 *   <li>the recorded absolute path as is;</li>
 *   <li>the recorded path made relative to
 *       {@code provenance.execution.workingDirectory} and then rebased onto the current
 *       rebasing root (the current working directory by default).</li>
 * </ol>
 */
public final class RerunInputResolver {

    private static final String RUNS_DIRECTORY_NAME = "runs";
    private static final String STUDY_INPUTS_DIRECTORY_NAME = "inputs";

    private RerunInputResolver() {}

    /**
     * Resolves every input recorded in the evidence package, using the current working
     * directory as the rebasing root.
     *
     * @throws RerunFailureException {@code INPUT_UNRESOLVED} or {@code INPUT_HASH_MISMATCH}
     *         (with the list of tried candidates); {@code EVIDENCE_INVALID} when an input
     *         entry lacks a required field
     * @throws IOException when a filesystem read fails
     */
    public static RerunInputs resolve(RerunEvidence evidence) throws RerunFailureException, IOException {
        return resolve(
                evidence.getManifest(),
                evidence.getRunDirectory(),
                Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());
    }

    /** Explicit-rebasing-root variant, visible for tests. */
    static RerunInputs resolve(JsonObject manifest, Path runDirectory, Path rebasingRoot)
            throws RerunFailureException, IOException {
        List<RerunInputs.ResolvedInput> resolved = new ArrayList<RerunInputs.ResolvedInput>();
        String recordedWorkingDirectory = stringAt(manifest, "provenance", "execution", "workingDirectory");
        for (JsonElement element : manifest.getAsJsonArray("inputs")) {
            JsonObject input = element.getAsJsonObject();
            String recordedPath = requireString(input, "path");
            String expectedSha256 = requireString(input, "sha256");
            long expectedSize = requireLong(input, "sizeBytes");
            resolved.add(resolveOne(
                    recordedPath, expectedSha256, expectedSize, recordedWorkingDirectory, runDirectory, rebasingRoot));
        }
        return new RerunInputs(resolved);
    }

    private static RerunInputs.ResolvedInput resolveOne(
            String recordedPath,
            String expectedSha256,
            long expectedSize,
            String recordedWorkingDirectory,
            Path runDirectory,
            Path rebasingRoot)
            throws RerunFailureException, IOException {
        List<Path> candidates = candidatePaths(recordedPath, recordedWorkingDirectory, runDirectory, rebasingRoot);
        List<String> tried = new ArrayList<String>();
        for (int tier = 0; tier < candidates.size(); tier++) {
            Path candidate = candidates.get(tier);
            if (candidate == null) {
                continue;
            }
            tried.add("tier" + (tier + 1) + ": " + candidate);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            String actualSha256 = sha256Hex(candidate);
            long actualSize = Files.size(candidate);
            if (!expectedSha256.equals(actualSha256) || expectedSize != actualSize) {
                List<String> details = new ArrayList<String>(tried);
                details.add("expected sha256=" + expectedSha256 + " sizeBytes=" + expectedSize);
                details.add("actual   sha256=" + actualSha256 + " sizeBytes=" + actualSize);
                throw new RerunFailureException(
                        RerunVerdict.INPUT_HASH_MISMATCH,
                        "Input located but sha256/sizeBytes mismatch: " + candidate
                                + " (recorded as " + recordedPath + "). Refusing to substitute"
                                + " a different file.",
                        details);
            }
            return new RerunInputs.ResolvedInput(recordedPath, candidate, actualSha256, actualSize, tier + 1);
        }
        throw new RerunFailureException(
                RerunVerdict.INPUT_UNRESOLVED, "Input could not be located: " + recordedPath, tried);
    }

    /**
     * Builds the three-tier candidate list in contract order; an unavailable tier leaves null
     * in its slot so that tier numbering stays stable.
     */
    private static List<Path> candidatePaths(
            String recordedPath, String recordedWorkingDirectory, Path runDirectory, Path rebasingRoot) {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(studyInputsCandidate(recordedPath, runDirectory));
        candidates.add(Paths.get(recordedPath));
        candidates.add(rebasedCandidate(recordedPath, recordedWorkingDirectory, rebasingRoot));
        return candidates;
    }

    private static Path studyInputsCandidate(String recordedPath, Path runDirectory) {
        Path parent = runDirectory.getParent();
        if (parent == null || !RUNS_DIRECTORY_NAME.equals(String.valueOf(parent.getFileName()))) {
            return null;
        }
        Path studyRoot = parent.getParent();
        if (studyRoot == null) {
            return null;
        }
        String fileName = Paths.get(recordedPath).getFileName().toString();
        return studyRoot.resolve(STUDY_INPUTS_DIRECTORY_NAME).resolve(fileName);
    }

    private static Path rebasedCandidate(String recordedPath, String recordedWorkingDirectory, Path rebasingRoot) {
        if (recordedWorkingDirectory == null || recordedWorkingDirectory.isEmpty()) {
            return null;
        }
        String prefix =
                recordedWorkingDirectory.endsWith("/") ? recordedWorkingDirectory : recordedWorkingDirectory + "/";
        if (!recordedPath.startsWith(prefix) || recordedPath.length() == prefix.length()) {
            return null;
        }
        return rebasingRoot.resolve(recordedPath.substring(prefix.length()));
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    private static String stringAt(JsonObject root, String... path) {
        JsonObject current = root;
        for (int i = 0; i < path.length - 1; i++) {
            if (current == null
                    || !current.has(path[i])
                    || !current.get(path[i]).isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject(path[i]);
        }
        if (current == null || !current.has(path[path.length - 1])) {
            return null;
        }
        JsonElement element = current.get(path[path.length - 1]);
        return element.isJsonNull() ? null : element.getAsString();
    }

    private static String requireString(JsonObject input, String field) throws RerunFailureException {
        if (!input.has(field) || !input.get(field).isJsonPrimitive()) {
            throw new RerunFailureException(
                    RerunVerdict.EVIDENCE_INVALID, "inputs[] entry is missing required field: " + field);
        }
        return input.get(field).getAsString();
    }

    private static long requireLong(JsonObject input, String field) throws RerunFailureException {
        if (!input.has(field) || !input.get(field).isJsonPrimitive()) {
            throw new RerunFailureException(
                    RerunVerdict.EVIDENCE_INVALID, "inputs[] entry is missing required field: " + field);
        }
        return input.get(field).getAsLong();
    }
}
