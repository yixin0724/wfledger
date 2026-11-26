package org.workflowsim.experiments.standard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.special.Gamma;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;

/** Weibull failure configuration of the standard study. */
public final class StandardFailureModel {

    public static final double SCHROEDER_GIBSON_SHAPE = 0.78;

    public static final double[] STANDARD_MTBF_TO_TASK_RATIOS = {10.0, 20.0, 100.0};

    public static final int DEFAULT_RETRY_BUDGET_PER_TASK = 50;

    private StandardFailureModel() {}

    public static double weibullMeanOverScale(double shape) {
        requirePositiveFinite(shape, "shape");
        return Math.exp(Gamma.logGamma(1.0 + 1.0 / shape));
    }

    public static double scaleSeconds(double mtbfToTaskRatio, double meanTaskSeconds, double shape) {
        requirePositiveFinite(mtbfToTaskRatio, "MTBF/task ratio");
        requirePositiveFinite(meanTaskSeconds, "mean task seconds");
        return mtbfToTaskRatio * meanTaskSeconds / weibullMeanOverScale(shape);
    }

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
