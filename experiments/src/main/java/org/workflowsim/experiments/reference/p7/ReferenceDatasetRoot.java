package org.workflowsim.experiments.reference.p7;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

final class ReferenceDatasetRoot {

    private ReferenceDatasetRoot() {}

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
