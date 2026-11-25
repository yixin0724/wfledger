package org.workflowsim.experiments.standard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.special.Gamma;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;

/**
 * Weibull failure configuration of the standard study.
 *
 * <p>STD-V1 (2026-11-25). Root cause: the historical FGCS failure injection used a periodic fixture whose rate had
 * no relation to the task durations. The standard study draws per-VM failure inter-arrival times from a Weibull
 * distribution with shape {@value #SCHROEDER_GIBSON_SHAPE}: Schroeder and Gibson ("A large-scale study of failures
 * in high-performance computing systems", DSN 2006; extended in IEEE TDSC 7(4) 2010) report that the time between
 * node failures is fit well by a Weibull distribution with a decreasing hazard rate and shape between 0.7 and 0.8;
 * 0.78 is the value adopted by the WorkflowSim fault-tolerant clustering line (Chen, Ferreira da Silva, Deelman,
 * Fahringer, "Dynamic and fault-tolerant clustering for scientific workflows", IEEE TCC 4(1) 2016), which cites that
 * study.
 *
 * <p>The scale is set from the declared ratio {@code rho = MTBF / E[task]}, with {@code E[task]} the mean compute
 * seconds of a task at the fleet's mean speed: {@code MTBF = scale * Gamma(1 + 1/shape)}, hence
 * {@code scale = rho * E[task] / Gamma(1 + 1/shape)}. The standard levels {@code rho in {10, 20, 100}} correspond to
 * {@code lambda * E[task] in {0.1, 0.05, 0.01}} with {@code lambda = 1 / MTBF}.
 *
 * <p>Application: {@code FAILURE_VM} with one VM-ID-keyed generator per VM (an independent arrival process per VM,
 * named stream {@code failure.vm<id>.depth0}), applied to every Job at every DAG depth on that VM. Under the
 * platform contract (FGCS-R5) an attempt fails at the first arrival inside its compute window and is retried by the
 * NOOP retry path; {@link #DEFAULT_RETRY_BUDGET_PER_TASK} bounds the total retries as a fail-fast safety budget.
 * The SHARED_STORAGE static planners require the failure model to be disabled, so this factor applies only to the
 * online layer, and the controlled I/O-inclusive SLR reference is unavailable when failures are enabled.
 */
public final class StandardFailureModel {

    /** Weibull shape of node inter-failure times (Schroeder and Gibson 2006). */
    public static final double SCHROEDER_GIBSON_SHAPE = 0.78;

    /** Declared MTBF / mean task runtime levels. */
    public static final double[] STANDARD_MTBF_TO_TASK_RATIOS = {10.0, 20.0, 100.0};

    /** Total retry budget per logical task (fail-fast safety bound, not a modelled limit). */
    public static final int DEFAULT_RETRY_BUDGET_PER_TASK = 50;

    private StandardFailureModel() {}

    /**
     * Returns {@code Gamma(1 + 1/shape)}.
     *
     * @param shape Weibull shape, finite positive
     * @return the Weibull mean in units of the scale
     */
    public static double weibullMeanOverScale(double shape) {
        requirePositiveFinite(shape, "shape");
        return Math.exp(Gamma.logGamma(1.0 + 1.0 / shape));
    }

    /**
     * Solves the Weibull scale for a target MTBF / mean-task ratio.
     *
     * @param mtbfToTaskRatio {@code rho}
     * @param meanTaskSeconds {@code E[task]}
     * @param shape Weibull shape
     * @return scale in seconds
     */
    public static double scaleSeconds(double mtbfToTaskRatio, double meanTaskSeconds, double shape) {
        requirePositiveFinite(mtbfToTaskRatio, "MTBF/task ratio");
        requirePositiveFinite(meanTaskSeconds, "mean task seconds");
        return mtbfToTaskRatio * meanTaskSeconds / weibullMeanOverScale(shape);
    }

    /**
     * Builds the failure configuration for the given VM IDs.
     *
     * @param vmIds VM IDs of the fleet, ascending
     * @param mtbfToTaskRatio {@code rho}
     * @param meanTaskSeconds {@code E[task]}
     * @param logicalTaskCount number of logical tasks (sizes the retry budget)
     * @return failure configuration
     */
    public static FailureModelConfig weibull(
            List<Integer> vmIds, double mtbfToTaskRatio, double meanTaskSeconds, int logicalTaskCount) {
        if (vmIds == null || vmIds.isEmpty() || logicalTaskCount <= 0) {
            throw new IllegalArgumentException("Failure model requires VM IDs and a positive task count");
        }
        double scale = scaleSeconds(mtbfToTaskRatio, meanTaskSeconds, SCHROEDER_GIBSON_SHAPE);
        Map<Integer, DistributionSpec[]> rows = new LinkedHashMap<Integer, DistributionSpec[]>();
        for (Integer vmId : vmIds) {
            rows.put(vmId, new DistributionSpec[] {
                DistributionSpec.of(DistributionFamily.WEIBULL, scale, SCHROEDER_GIBSON_SHAPE)
            });
        }
        return FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .distributionFamily(DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(rows)
                .maxTotalRetryJobs(Math.multiplyExact(DEFAULT_RETRY_BUDGET_PER_TASK, logicalTaskCount))
                .build();
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!(value > 0.0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and positive: " + value);
        }
    }
}
