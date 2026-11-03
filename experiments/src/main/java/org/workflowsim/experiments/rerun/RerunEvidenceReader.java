package org.workflowsim.experiments.rerun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.workflowsim.experiment.ExperimentArtifactValidator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Stage 1: reads the evidence triple of a single run directory and validates its structure
 * (see step one of "Execution flow" in the contract).
 *
 * <p>Location rule: {@code result.manifest.json} is preferred; when it is absent, the
 * single {@code *.manifest.json} in the directory is accepted, while multiple candidates
 * or none at all are rejected as {@code EVIDENCE_INVALID}. The schema preflight precedes
 * structural validation: v2/v3 are historically readable formats but do not support
 * rerun, so they are rejected explicitly with the reason stated; structure, sidecar hash,
 * and event count validation are delegated to the existing
 * {@link ExperimentArtifactValidator#validate(Path)}, so that validation rules unrelated
 * to this package keep a single source.</p>
 */
public final class RerunEvidenceReader {

    /** Manifest file name under the standard write convention (runId is result). */
    static final String DEFAULT_MANIFEST_NAME = "result.manifest.json";

    private static final String SCHEMA_V4 = "workflowsim-experiment-manifest-v4";
    private static final String SCHEMA_V3 = "workflowsim-experiment-manifest-v3";
    private static final String SCHEMA_V2 = "workflowsim-experiment-manifest-v2";

    private RerunEvidenceReader() {}

    /**
     * Reads and validates the evidence package of a single run directory.
     *
     * @param runDirectory evidence directory of a single run
     * @return evidence snapshot that passed every validation
     * @throws RerunFailureException when the directory or manifest is missing, the schema
     *         is not v4, or the evidence triple fails structural validation (verdict is
     *         {@code EVIDENCE_INVALID})
     * @throws IOException when filesystem reading fails (not a contract failure)
     * @throws IllegalArgumentException when runDirectory is null
     */
    public static RerunEvidence read(Path runDirectory) throws RerunFailureException, IOException {
        if (runDirectory == null) {
            throw new IllegalArgumentException("Run directory is required");
        }
        Path directory = runDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw fail("Run directory does not exist: " + directory);
        }
        Path manifest = locateManifest(directory);
        JsonObject root = parseManifest(manifest);
        String schema = schemaOf(root);
        if (!SCHEMA_V4.equals(schema)) {
            if (SCHEMA_V3.equals(schema) || SCHEMA_V2.equals(schema)) {
                throw fail("Rerun only supports manifest v4 (" + SCHEMA_V4 + "); found "
                        + schema + ". Historical v2/v3 evidence remains readable but cannot"
                        + " be rerun.");
            }
            throw fail("Unsupported manifest schema: " + schema);
        }
        ExperimentArtifactValidator.ValidationResult validated;
        try {
            validated = ExperimentArtifactValidator.validate(manifest);
        } catch (IOException e) {
            throw fail("Evidence structure validation failed: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw fail("Evidence structure validation failed: " + e.getMessage(), e);
        }
        JsonObject snapshot = validated.getManifestSnapshot();
        if (!SCHEMA_V4.equals(schemaOf(snapshot))) {
            throw fail("Validated manifest snapshot is not v4; evidence may have changed during reading");
        }
        return new RerunEvidence(
                directory,
                validated.getManifest(),
                validated.getMetrics(),
                validated.getEvents(),
                validated.getEventCount(),
                snapshot,
                validated.getNetworkLedger(),
                validated.getNetworkCaptureStatus(),
                validated.getFileLifecycle(),
                validated.getFileLifecycleCaptureStatus(),
                validated.getStorageLifecycle(),
                validated.getStorageLifecycleCaptureStatus());
    }

    private static Path locateManifest(Path directory) throws RerunFailureException, IOException {
        Path standard = directory.resolve(DEFAULT_MANIFEST_NAME);
        if (Files.isRegularFile(standard)) {
            return standard;
        }
        List<Path> candidates = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.manifest.json")) {
            for (Path candidate : stream) {
                candidates.add(candidate);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw fail(
                    "No manifest found in run directory: " + directory + " (expected " + DEFAULT_MANIFEST_NAME + ")");
        }
        throw fail("Ambiguous run directory: multiple manifests found", pathsAsStrings(candidates));
    }

    private static JsonObject parseManifest(Path manifest) throws RerunFailureException, IOException {
        byte[] bytes = Files.readAllBytes(manifest);
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw fail("Manifest is not a JSON object: " + manifest, e);
        }
    }

    private static String schemaOf(JsonObject root) throws RerunFailureException {
        if (!root.has("schema") || !root.get("schema").isJsonPrimitive()) {
            throw fail("Manifest is missing the schema field");
        }
        return root.get("schema").getAsString();
    }

    private static List<String> pathsAsStrings(List<Path> paths) {
        List<String> values = new ArrayList<String>();
        for (Path path : paths) {
            values.add(path.toString());
        }
        return values;
    }

    private static RerunFailureException fail(String message) {
        return new RerunFailureException(RerunVerdict.EVIDENCE_INVALID, message);
    }

    private static RerunFailureException fail(String message, Throwable cause) {
        return new RerunFailureException(RerunVerdict.EVIDENCE_INVALID, message, cause);
    }

    private static RerunFailureException fail(String message, List<String> details) {
        return new RerunFailureException(RerunVerdict.EVIDENCE_INVALID, message, details);
    }
}
