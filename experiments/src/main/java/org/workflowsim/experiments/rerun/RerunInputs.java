package org.workflowsim.experiments.rerun;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public ResolvedInput get(int index) {
        return resolved.get(index);
    }

    public int size() {
        return resolved.size();
    }

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

        public String getRecordedPath() {
            return recordedPath;
        }

        public Path getResolvedPath() {
            return resolvedPath;
        }

        public String getSha256() {
            return sha256;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public int getTier() {
            return tier;
        }
    }
}
