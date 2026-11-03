package org.workflowsim.experiments.rerun;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 2 artifact: on-host location results for all inputs listed in the manifest (read-only).
 *
 * <p>Each {@link ResolvedInput} records the original path, the actual local path, the verified
 * sha256, and the matched location tier, for configuration rebuilding and reporting; the
 * location process never silently substitutes an input.</p>
 */
public final class RerunInputs {

    private final List<ResolvedInput> resolved;

    RerunInputs(List<ResolvedInput> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalArgumentException("At least one resolved input is required");
        }
        this.resolved = Collections.unmodifiableList(new ArrayList<ResolvedInput>(resolved));
    }

    public List<ResolvedInput> getResolved() {
        return resolved;
    }

    /** Return the i-th location result in the original manifest inputs[] order. */
    public ResolvedInput get(int index) {
        return resolved.get(index);
    }

    public int size() {
        return resolved.size();
    }

    /** Verified location result for a single input. */
    public static final class ResolvedInput {

        private final String recordedPath;
        private final Path resolvedPath;
        private final String sha256;
        private final long sizeBytes;
        private final int tier;

        ResolvedInput(String recordedPath, Path resolvedPath, String sha256, long sizeBytes, int tier) {
            if (recordedPath == null || resolvedPath == null || sha256 == null) {
                throw new IllegalArgumentException("All resolved input fields are required");
            }
            this.recordedPath = recordedPath;
            this.resolvedPath = resolvedPath;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.tier = tier;
        }

        /** Absolute path on the original machine, as recorded in the manifest. */
        public String getRecordedPath() {
            return recordedPath;
        }

        /** File actually used on this host whose hash check passed. */
        public Path getResolvedPath() {
            return resolvedPath;
        }

        public String getSha256() {
            return sha256;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        /**
         * Matched location tier: 1 = study inputs/ directory, 2 = the recorded absolute path
         * used as is, 3 = relocated by relativizing against the recorded workingDirectory.
         */
        public int getTier() {
            return tier;
        }
    }
}
