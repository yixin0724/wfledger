package org.workflowsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records provenance and explicit normalization when a workflow input is
 * converted into WorkflowSim tasks.
 *
 * <p>The report describes only the input interpretation process; it is neither
 * simulation output nor an observed real-world execution. An experiment
 * manifest can use it to trace the input format, the declared schema version,
 * and the conversions applied to satisfy model constraints.</p>
 */
public final class WorkflowInputReport {

    /** Supported workflow input formats. */
    public enum Format {
        DAX_XML,
        WFCOMMONS_JSON
    }

    /** Normalization kinds the current input conversion may record. */
    public enum NormalizationKind {
        TASK_RUNTIME_FLOORED_TO_MINIMUM
    }

    private final String sourcePath;
    private final Format format;
    private final String declaredVersion;
    private final List<Normalization> normalizations = new ArrayList<>();
    private int taskCount;

    /**
     * Creates an input conversion report.
     *
     * @param sourcePath path of the parsed input file
     * @param format input format
     * @param declaredVersion format or schema version declared by the input;
     *        may be {@code null} when unknown
     */
    public WorkflowInputReport(String sourcePath, Format format, String declaredVersion) {
        if (sourcePath == null || sourcePath.trim().isEmpty() || format == null) {
            throw new IllegalArgumentException("Workflow input report requires a path and format");
        }
        this.sourcePath = sourcePath;
        this.format = format;
        this.declaredVersion = declaredVersion;
    }

    /** @return path of the parsed input file */
    public String getSourcePath() {
        return sourcePath;
    }

    /** @return input format */
    public Format getFormat() {
        return format;
    }

    /** @return format or schema version declared by the input; {@code null} when not declared */
    public String getDeclaredVersion() {
        return declaredVersion;
    }

    /** @return number of tasks converted successfully */
    public int getTaskCount() {
        return taskCount;
    }

    /**
     * Sets the number of tasks converted successfully.
     *
     * @param taskCount non-negative task count
     */
    public void setTaskCount(int taskCount) {
        if (taskCount < 0) {
            throw new IllegalArgumentException("Task count cannot be negative");
        }
        this.taskCount = taskCount;
    }

    /**
     * Records one intentional conversion of an input value.
     *
     * @param kind normalization kind
     * @param subject identifier of the converted input object
     * @param originalValue original value
     * @param normalizedValue value entering the model
     */
    public void recordNormalization(
            NormalizationKind kind, String subject, double originalValue, double normalizedValue) {
        normalizations.add(new Normalization(kind, subject, originalValue, normalizedValue));
    }

    /** @return unmodifiable list of normalization records */
    public List<Normalization> getNormalizations() {
        return Collections.unmodifiableList(normalizations);
    }

    /** Record of one intentional conversion of an input value. */
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

        /** @return normalization kind */
        public NormalizationKind getKind() {
            return kind;
        }

        /** @return identifier of the converted input object */
        public String getSubject() {
            return subject;
        }

        /** @return original value */
        public double getOriginalValue() {
            return originalValue;
        }

        /** @return model value after normalization */
        public double getNormalizedValue() {
            return normalizedValue;
        }
    }
}
