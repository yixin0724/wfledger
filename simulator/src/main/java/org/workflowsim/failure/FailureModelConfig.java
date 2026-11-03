package org.workflowsim.failure;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;

/**
 * Immutable failure model description used by a single simulation session.
 *
 * <p>Random distribution generators are created only when the session opens. Sampling therefore
 * happens after the run seed has been installed, and generator state cannot leak into the next
 * run.</p>
 */
public final class FailureModelConfig {

    private static final FailureModelConfig DISABLED = new FailureModelConfig(
            FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
            FailureParameters.FTCMonitor.MONITOR_NONE,
            FailureParameters.FTCFailure.FAILURE_NONE,
            DistributionFamily.WEIBULL,
            new DistributionSpec[0][0],
            Collections.<Integer, DistributionSpec[]>emptyMap(),
            0);

    private final FailureParameters.FTCluteringAlgorithm clusteringAlgorithm;
    private final FailureParameters.FTCMonitor monitorMode;
    private final FailureParameters.FTCFailure generatorMode;
    private final DistributionFamily distributionFamily;
    private final DistributionSpec[][] generatorSpecs;
    /** Generator rows of the modern VM-level model, keyed by real VM ID; exclusive with the dense matrix. */
    private final Map<Integer, DistributionSpec[]> generatorSpecsByVmId;

    private final int maxTotalRetryJobs;

    private FailureModelConfig(
            FailureParameters.FTCluteringAlgorithm clusteringAlgorithm,
            FailureParameters.FTCMonitor monitorMode,
            FailureParameters.FTCFailure generatorMode,
            DistributionFamily distributionFamily,
            DistributionSpec[][] generatorSpecs,
            Map<Integer, DistributionSpec[]> generatorSpecsByVmId,
            int maxTotalRetryJobs) {
        this.clusteringAlgorithm = clusteringAlgorithm;
        this.monitorMode = monitorMode;
        this.generatorMode = generatorMode;
        this.distributionFamily = distributionFamily;
        this.generatorSpecs = copySpecs(generatorSpecs);
        this.generatorSpecsByVmId = copySpecsByVmId(generatorSpecsByVmId);
        this.maxTotalRetryJobs = maxTotalRetryJobs;
    }

    /** Returns the "no failure" default model that ordinary experiments adopt explicitly. */
    public static FailureModelConfig disabled() {
        return DISABLED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public FailureParameters.FTCluteringAlgorithm getClusteringAlgorithm() {
        return clusteringAlgorithm;
    }

    public FailureParameters.FTCMonitor getMonitorMode() {
        return monitorMode;
    }

    public FailureParameters.FTCFailure getGeneratorMode() {
        return generatorMode;
    }

    public DistributionFamily getDistributionFamily() {
        return distributionFamily;
    }

    /**
     * Returns a copy of the legacy dense specification matrix and never exposes the internal
     * matrix.
     *
     * <p>When the modern VM-ID-keyed configuration of {@link #getGeneratorSpecsByVmId()} is used,
     * an empty matrix is returned.</p>
     */
    public DistributionSpec[][] getGeneratorSpecs() {
        return copySpecs(generatorSpecs);
    }

    /**
     * Returns a copy of the generator rows keyed by real VM ID.
     *
     * <p>This form applies only to {@code FAILURE_VM} and {@code FAILURE_VM_JOB}; it prevents
     * non-contiguous VM identifiers from being misinterpreted as indices of a two-dimensional
     * array. A legacy dense matrix configuration returns an empty map.</p>
     */
    public Map<Integer, DistributionSpec[]> getGeneratorSpecsByVmId() {
        return copySpecsByVmId(generatorSpecsByVmId);
    }

    /** @return whether the current model keys generator rows by real VM ID */
    public boolean usesVmIdKeyedGeneratorRows() {
        return !generatorSpecsByVmId.isEmpty();
    }

    /** @return total retry Jobs this run may create; 0 when the failure model is disabled */
    public int getMaxTotalRetryJobs() {
        return maxTotalRetryJobs;
    }

    /** Returns {@code true} when the session must allocate stateful random generators. */
    public boolean isEnabled() {
        return generatorMode != FailureParameters.FTCFailure.FAILURE_NONE;
    }

    /**
     * Installs the new generator matrices and the stop budget, both scoped by the current run
     * seed, into the legacy facade.
     */
    public void applyToFailureParameters(long rootSeed) {
        if (!isEnabled()) {
            FailureParameters.init(
                    clusteringAlgorithm,
                    monitorMode,
                    generatorMode,
                    (DistributionGenerator[][]) null,
                    distributionFamily,
                    maxTotalRetryJobs,
                    rootSeed);
        } else if (usesVmIdKeyedGeneratorRows()) {
            FailureParameters.init(
                    clusteringAlgorithm,
                    monitorMode,
                    generatorMode,
                    createGeneratorsByVmId(),
                    distributionFamily,
                    maxTotalRetryJobs,
                    rootSeed);
        } else {
            FailureParameters.init(
                    clusteringAlgorithm,
                    monitorMode,
                    generatorMode,
                    createGenerators(),
                    distributionFamily,
                    maxTotalRetryJobs,
                    rootSeed);
        }
    }

    private DistributionGenerator[][] createGenerators() {
        DistributionGenerator[][] generators = new DistributionGenerator[generatorSpecs.length][];
        for (int row = 0; row < generatorSpecs.length; row++) {
            generators[row] = new DistributionGenerator[generatorSpecs[row].length];
            for (int column = 0; column < generatorSpecs[row].length; column++) {
                generators[row][column] =
                        generatorSpecs[row][column].createGenerator("failure.row" + row + ".column" + column);
            }
        }
        return generators;
    }

    private Map<Integer, DistributionGenerator[]> createGeneratorsByVmId() {
        Map<Integer, DistributionGenerator[]> generators = new LinkedHashMap<Integer, DistributionGenerator[]>();
        for (Map.Entry<Integer, DistributionSpec[]> entry : generatorSpecsByVmId.entrySet()) {
            int vmId = entry.getKey().intValue();
            DistributionSpec[] specs = entry.getValue();
            DistributionGenerator[] row = new DistributionGenerator[specs.length];
            for (int depth = 0; depth < specs.length; depth++) {
                row[depth] = specs[depth].createGenerator("failure.vm" + vmId + ".depth" + depth);
            }
            generators.put(Integer.valueOf(vmId), row);
        }
        return generators;
    }

    private static DistributionSpec[][] copySpecs(DistributionSpec[][] source) {
        DistributionSpec[][] result = new DistributionSpec[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = Arrays.copyOf(source[row], source[row].length);
        }
        return result;
    }

    private static Map<Integer, DistributionSpec[]> copySpecsByVmId(Map<Integer, DistributionSpec[]> source) {
        Map<Integer, DistributionSpec[]> sorted = new TreeMap<Integer, DistributionSpec[]>();
        for (Map.Entry<Integer, DistributionSpec[]> entry : source.entrySet()) {
            sorted.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, DistributionSpec[]>(sorted));
    }

    /** The builder validates the configuration structure before any simulation entity is created. */
    public static final class Builder {

        private FailureParameters.FTCluteringAlgorithm clusteringAlgorithm =
                FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP;
        private FailureParameters.FTCMonitor monitorMode = FailureParameters.FTCMonitor.MONITOR_NONE;
        private FailureParameters.FTCFailure generatorMode = FailureParameters.FTCFailure.FAILURE_NONE;
        private DistributionFamily distributionFamily = DistributionFamily.WEIBULL;
        private DistributionSpec[][] generatorSpecs = new DistributionSpec[0][0];
        private Map<Integer, DistributionSpec[]> generatorSpecsByVmId =
                Collections.<Integer, DistributionSpec[]>emptyMap();
        private int maxTotalRetryJobs = -1;

        public Builder clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm value) {
            clusteringAlgorithm = value;
            return this;
        }

        public Builder monitorMode(FailureParameters.FTCMonitor value) {
            monitorMode = value;
            return this;
        }

        public Builder generatorMode(FailureParameters.FTCFailure value) {
            generatorMode = value;
            return this;
        }

        public Builder distributionFamily(DistributionFamily value) {
            distributionFamily = value;
            return this;
        }

        public Builder generatorSpecs(DistributionSpec[][] value) {
            generatorSpecs = value;
            return this;
        }

        /**
         * Declares the generator rows by real VM ID.
         *
         * <p>This form is only for models whose selection involves the VM dimension; it lets a
         * platform use non-contiguous VM IDs such as {@code 10} and {@code 20} without building
         * meaningless rows 0 through 19.</p>
         *
         * @param value mapping from VM ID to the specification row of each task depth
         * @return this builder
         */
        public Builder generatorSpecsByVmId(Map<Integer, DistributionSpec[]> value) {
            generatorSpecsByVmId = value;
            return this;
        }

        /**
         * Sets the maximum number of retry Jobs the failure model may create for this run.
         *
         * <p>An enabled failure model must set an explicit positive value, so that
         * high-failure-rate experiments have a recordable stop condition.</p>
         *
         * @param value positive upper bound on the total number of retry Jobs
         * @return this builder
         */
        public Builder maxTotalRetryJobs(int value) {
            maxTotalRetryJobs = value;
            return this;
        }

        public FailureModelConfig build() {
            if (clusteringAlgorithm == null
                    || monitorMode == null
                    || generatorMode == null
                    || distributionFamily == null
                    || generatorSpecs == null
                    || generatorSpecsByVmId == null) {
                throw new IllegalArgumentException("Failure configuration contains a required null value");
            }
            validateGeneratorLayout();
            return new FailureModelConfig(
                    clusteringAlgorithm,
                    monitorMode,
                    generatorMode,
                    distributionFamily,
                    generatorSpecs,
                    generatorSpecsByVmId,
                    generatorMode == FailureParameters.FTCFailure.FAILURE_NONE ? 0 : maxTotalRetryJobs);
        }

        /**
         * 2026-10-26 complexity extraction from build; statements verbatim; order unchanged.
         * Scope: validates only failure-model enablement and generator layout (a disabled model must
         * define neither generators nor a retry budget; an enabled model takes exactly one layout
         * whose mode and budget match).
         */
        private void validateGeneratorLayout() {
            boolean hasDenseMatrix = generatorSpecs.length != 0;
            boolean hasVmIdKeyedRows = !generatorSpecsByVmId.isEmpty();
            if (generatorMode == FailureParameters.FTCFailure.FAILURE_NONE) {
                if (hasDenseMatrix || hasVmIdKeyedRows) {
                    throw new IllegalArgumentException("A disabled failure model cannot define generators");
                }
                if (maxTotalRetryJobs != -1 && maxTotalRetryJobs != 0) {
                    throw new IllegalArgumentException("A disabled failure model must use retry budget 0");
                }
            } else {
                if (hasDenseMatrix == hasVmIdKeyedRows) {
                    throw new IllegalArgumentException("An enabled failure model requires exactly one generator "
                            + "layout: a dense matrix or VM-ID-keyed rows");
                }
                if (hasVmIdKeyedRows) {
                    if (generatorMode != FailureParameters.FTCFailure.FAILURE_VM
                            && generatorMode != FailureParameters.FTCFailure.FAILURE_VM_JOB) {
                        throw new IllegalArgumentException(
                                "VM-ID-keyed generator rows require FAILURE_VM or " + "FAILURE_VM_JOB mode");
                    }
                    validateVmIdKeyedGeneratorRows(generatorSpecsByVmId, distributionFamily);
                } else {
                    validateGeneratorMatrix(generatorSpecs, distributionFamily);
                }
                if (maxTotalRetryJobs <= 0) {
                    throw new IllegalArgumentException("An enabled failure model requires an explicit positive "
                            + "maxTotalRetryJobs safety budget");
                }
            }
        }

        private static void validateGeneratorMatrix(DistributionSpec[][] matrix, DistributionFamily expectedFamily) {
            if (matrix.length == 0) {
                throw new IllegalArgumentException("An enabled failure model requires a generator matrix");
            }
            if (expectedFamily == DistributionFamily.NORMAL) {
                throw new IllegalArgumentException("Failure inter-arrival distributions cannot use NORMAL "
                        + "because it can generate negative simulated time; use WEIBULL, GAMMA, or LOGNORMAL");
            }
            for (int row = 0; row < matrix.length; row++) {
                if (matrix[row] == null || matrix[row].length == 0) {
                    throw new IllegalArgumentException("Failure generator row " + row + " is empty");
                }
                for (int column = 0; column < matrix[row].length; column++) {
                    if (matrix[row][column] == null) {
                        throw new IllegalArgumentException("Failure generator [" + row + "][" + column + "] is null");
                    }
                    if (matrix[row][column].getFamily() != expectedFamily) {
                        throw new IllegalArgumentException("Failure generator [" + row + "][" + column
                                + "] has family " + matrix[row][column].getFamily()
                                + " but the failure model declares " + expectedFamily);
                    }
                }
            }
        }

        private static void validateVmIdKeyedGeneratorRows(
                Map<Integer, DistributionSpec[]> rows, DistributionFamily expectedFamily) {
            if (expectedFamily == DistributionFamily.NORMAL) {
                throw new IllegalArgumentException("Failure inter-arrival distributions cannot use NORMAL "
                        + "because it can generate negative simulated time; use WEIBULL, GAMMA, or LOGNORMAL");
            }
            for (Map.Entry<Integer, DistributionSpec[]> entry : rows.entrySet()) {
                Integer vmId = entry.getKey();
                DistributionSpec[] specs = entry.getValue();
                if (vmId == null || vmId.intValue() < 0) {
                    throw new IllegalArgumentException(
                            "VM-ID-keyed failure generator rows require non-negative VM IDs");
                }
                if (specs == null || specs.length == 0) {
                    throw new IllegalArgumentException("Failure generator row for VM " + vmId + " is empty");
                }
                for (int depth = 0; depth < specs.length; depth++) {
                    DistributionSpec spec = specs[depth];
                    if (spec == null) {
                        throw new IllegalArgumentException(
                                "Failure generator for VM " + vmId + " at depth " + depth + " is null");
                    }
                    if (spec.getFamily() != expectedFamily) {
                        throw new IllegalArgumentException("Failure generator for VM " + vmId
                                + " at depth " + depth + " has family " + spec.getFamily()
                                + " but the failure model declares " + expectedFamily);
                    }
                }
            }
        }
    }
}
