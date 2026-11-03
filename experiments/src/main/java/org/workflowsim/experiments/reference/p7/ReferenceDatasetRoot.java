package org.workflowsim.experiments.reference.p7;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Explicit dataset-root contract shared by the P7/reference run entry points. */
final class ReferenceDatasetRoot {

    private ReferenceDatasetRoot() {}

    /**
     * Validate and normalize an explicit, absolute dataset directory.
     *
     * @param datasetRoot the {@code datasets} directory supplied by the caller
     * @return the normalized absolute directory
     * @throws IllegalArgumentException if the directory is null, relative, missing, or not a
     *         directory
     */
    static Path require(Path datasetRoot) {
        if (datasetRoot == null) {
            throw new IllegalArgumentException("Dataset root is required");
        }
        if (!datasetRoot.isAbsolute()) {
            throw new IllegalArgumentException("Dataset root must be absolute: " + datasetRoot);
        }
        Path normalized = datasetRoot.normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Dataset root is not a directory: " + normalized);
        }
        return normalized;
    }

    /**
     * Resolve one declared relative input file inside a validated dataset root.
     *
     * @param datasetRoot explicit, absolute dataset root
     * @param relativePath relative file name declared by the frozen scenario
     * @return validated normalized absolute regular file
     * @throws IllegalArgumentException if the path escapes the dataset root, is invalid, or does
     *         not exist
     */
    static Path resolveFile(Path datasetRoot, String relativePath) {
        Path normalizedRoot = require(datasetRoot);
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Dataset-relative input path is required");
        }
        Path relative;
        try {
            relative = Paths.get(relativePath);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Invalid dataset-relative input path: " + relativePath, exception);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Dataset input path must be relative: " + relativePath);
        }
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Dataset input path escapes dataset root: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Dataset input file does not exist: " + resolved);
        }
        return resolved;
    }
}
