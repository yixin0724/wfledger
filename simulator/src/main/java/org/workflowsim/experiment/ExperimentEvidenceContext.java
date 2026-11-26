package org.workflowsim.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExperimentEvidenceContext {

    private final String studyId;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final Class<?> driverClass;
    private final String protocolId;
    private final Path protocolFile;
    private final Path datasetRoot;

    private ExperimentEvidenceContext(Builder builder) {
        this.studyId = builder.studyId;
        this.groupId = builder.groupId;
        this.artifactId = builder.artifactId;
        this.version = builder.version;
        this.driverClass = builder.driverClass;
        this.protocolId = builder.protocolId;
        this.protocolFile = builder.protocolFile;
        this.datasetRoot = builder.datasetRoot;
    }

    public static Builder builder(String studyId) {
        return new Builder(studyId);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("id", studyId);
        values.put("component", ExperimentProvenance.componentIdentity(driverClass, groupId, artifactId, version));

        boolean protocolAvailable = protocolFile != null && Files.isRegularFile(protocolFile);
        Map<String, Object> protocol = new LinkedHashMap<String, Object>();
        protocol.put("logicalId", protocolId);
        protocol.put("sha256", protocolAvailable ? ExperimentProvenance.fingerprint(protocolFile) : null);
        protocol.put("available", Boolean.valueOf(protocolAvailable));
        values.put("protocol", protocol);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("root", datasetRoot.toString());
        data.put("resolutionPolicy", "EXPLICIT_ABSOLUTE_DATASET_ROOT");
        values.put("dataset", data);
        return values;
    }

    public static final class Builder {
        private final String studyId;
        private String groupId;
        private String artifactId;
        private String version;
        private Class<?> driverClass;
        private String protocolId;
        private Path protocolFile;
        private Path datasetRoot;

        private Builder(String studyId) {
            this.studyId = requireText(studyId, "Study ID");
        }

        public Builder artifact(String groupId, String artifactId, String version) {
            this.groupId = requireText(groupId, "Study groupId");
            this.artifactId = requireText(artifactId, "Study artifactId");
            this.version = requireText(version, "Study version");
            return this;
        }

        public Builder driver(Class<?> driverClass) {
            if (driverClass == null) {
                throw new IllegalArgumentException("Study driver class is required");
            }
            this.driverClass = driverClass;
            return this;
        }

        public Builder protocol(String protocolId, Path protocolFile) {
            this.protocolId = requireText(protocolId, "Protocol ID");
            this.protocolFile =
                    protocolFile == null ? null : protocolFile.toAbsolutePath().normalize();
            return this;
        }

        public Builder datasetRoot(Path datasetRoot) {
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
            this.datasetRoot = normalized;
            return this;
        }

        public ExperimentEvidenceContext build() {
            if (groupId == null
                    || artifactId == null
                    || version == null
                    || driverClass == null
                    || protocolId == null
                    || datasetRoot == null) {
                throw new IllegalStateException("Study artifact, driver, protocol, and dataset root are required");
            }
            return new ExperimentEvidenceContext(this);
        }

        private static String requireText(String value, String label) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(label + " is required");
            }
            return value.trim();
        }
    }
}
