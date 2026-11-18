package org.workflowsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WorkflowInputReport {

    public enum Format {
        DAX_XML,
        WFCOMMONS_JSON
    }

    public enum NormalizationKind {
        TASK_RUNTIME_FLOORED_TO_MINIMUM
    }

    private final String sourcePath;
    private final Format format;
    private final String declaredVersion;
    private final List<Normalization> normalizations = new ArrayList<>();
    private int taskCount;

    public WorkflowInputReport(String sourcePath, Format format, String declaredVersion) {
        if (sourcePath == null || sourcePath.trim().isEmpty() || format == null) {
            throw new IllegalArgumentException("Workflow input report requires a path and format");
        }
        this.sourcePath = sourcePath;
        this.format = format;
        this.declaredVersion = declaredVersion;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public Format getFormat() {
        return format;
    }

    public String getDeclaredVersion() {
        return declaredVersion;
    }

    public int getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(int taskCount) {
        if (taskCount < 0) {
            throw new IllegalArgumentException("Task count cannot be negative");
        }
        this.taskCount = taskCount;
    }

    public void recordNormalization(
            NormalizationKind kind, String subject, double originalValue, double normalizedValue) {
        normalizations.add(new Normalization(kind, subject, originalValue, normalizedValue));
    }

    public List<Normalization> getNormalizations() {
        return Collections.unmodifiableList(normalizations);
    }

    public static final class Normalization {

        private final NormalizationKind kind;
        private final String subject;
        private final double originalValue;
        private final double normalizedValue;

        private Normalization(NormalizationKind kind, String subject, double originalValue, double normalizedValue) {
            this.kind = kind;
            this.subject = subject;
            this.originalValue = originalValue;
            this.normalizedValue = normalizedValue;
        }

        public NormalizationKind getKind() {
            return kind;
        }

        public String getSubject() {
            return subject;
        }

        public double getOriginalValue() {
            return originalValue;
        }

        public double getNormalizedValue() {
            return normalizedValue;
        }
    }
}
