/*
 *
 *   Copyright 2012-2013 University Of Southern California
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.workflowsim.failure;

import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;

import org.workflowsim.Task;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

public class FailureParameters {

    private static DistributionGenerator[][] generators;

    private static Map<Integer, DistributionGenerator[]> generatorsByVmId;

    public enum FTCluteringAlgorithm {
        FTCLUSTERING_NOOP,

        FTCLUSTERING_CRCH
    }

    public enum FTCMonitor {
        MONITOR_NONE,
        MONITOR_ALL,
        MONITOR_VM,
        MONITOR_JOB,
        MONITOR_VM_JOB
    }

    public enum FTCFailure {
        FAILURE_NONE,
        FAILURE_ALL,
        FAILURE_VM,
        FAILURE_JOB,
        FAILURE_VM_JOB
    }

    private static FTCluteringAlgorithm FTClusteringAlgorithm = FTCluteringAlgorithm.FTCLUSTERING_NOOP;

    private static FTCMonitor monitorMode = FTCMonitor.MONITOR_NONE;

    private static FTCFailure failureMode = FTCFailure.FAILURE_NONE;

    private static DistributionFamily distribution = DistributionFamily.WEIBULL;

    private static int maxTotalRetryJobs = -1;

    private static long rootSeed;

    private static final int INVALID = -1;

    public static void init(
            FTCluteringAlgorithm fMethod,
            FTCMonitor monitor,
            FTCFailure failure,
            DistributionGenerator[][] failureGenerators) {
        initInternal(fMethod, monitor, failure, failureGenerators, null, -1, 0L);
    }

    public static void init(
            FTCluteringAlgorithm fMethod,
            FTCMonitor monitor,
            FTCFailure failure,
            DistributionGenerator[][] failureGenerators,
            DistributionFamily dist,
            int retryBudget,
            long seed) {
        if (dist == null) {
            throw new IllegalArgumentException("Failure distribution cannot be null");
        }
        distribution = dist;
        initInternal(fMethod, monitor, failure, failureGenerators, null, retryBudget, seed);
    }

    public static void init(
            FTCluteringAlgorithm fMethod,
            FTCMonitor monitor,
            FTCFailure failure,
            Map<Integer, DistributionGenerator[]> failureGeneratorsByVmId,
            DistributionFamily dist,
            int retryBudget,
            long seed) {
        if (dist == null) {
            throw new IllegalArgumentException("Failure distribution cannot be null");
        }
        distribution = dist;
        initInternal(fMethod, monitor, failure, null, failureGeneratorsByVmId, retryBudget, seed);
    }

    private static void initInternal(
            FTCluteringAlgorithm fMethod,
            FTCMonitor monitor,
            FTCFailure failure,
            DistributionGenerator[][] failureGenerators,
            Map<Integer, DistributionGenerator[]> failureGeneratorsByVmId,
            int retryBudget,
            long seed) {
        if (fMethod == null || monitor == null || failure == null) {
            throw new IllegalArgumentException("Failure configuration values cannot be null");
        }
        if (retryBudget < -1) {
            throw new IllegalArgumentException("Retry budget must be -1 (legacy unbounded) or non-negative");
        }
        if (failure == FTCFailure.FAILURE_NONE && retryBudget > 0) {
            throw new IllegalArgumentException("Disabled failure mode cannot define a positive retry budget");
        }
        if (failure != FTCFailure.FAILURE_NONE && retryBudget == 0) {
            throw new IllegalArgumentException(
                    "Enabled failure mode requires a positive retry budget or " + "legacy unbounded mode");
        }
        validateGenerators(failure, failureGenerators, failureGeneratorsByVmId);
        FTClusteringAlgorithm = fMethod;
        monitorMode = monitor;
        failureMode = failure;
        generators = failureGenerators;
        generatorsByVmId = failureGeneratorsByVmId;
        maxTotalRetryJobs = retryBudget;
        rootSeed = seed;
    }

    public static void init(
            FTCluteringAlgorithm fMethod,
            FTCMonitor monitor,
            FTCFailure failure,
            DistributionGenerator[][] failureGenerators,
            DistributionFamily dist) {
        if (dist == null) {
            throw new IllegalArgumentException("Failure distribution cannot be null");
        }
        distribution = dist;
        initInternal(fMethod, monitor, failure, failureGenerators, null, -1, 0L);
    }

    public static void reset() {
        generators = null;
        generatorsByVmId = null;
        FTClusteringAlgorithm = FTCluteringAlgorithm.FTCLUSTERING_NOOP;
        monitorMode = FTCMonitor.MONITOR_NONE;
        failureMode = FTCFailure.FAILURE_NONE;
        distribution = DistributionFamily.WEIBULL;
        maxTotalRetryJobs = -1;
        rootSeed = 0L;
    }

    public static DistributionGenerator[][] getFailureGenerators() {
        requireGenerators();
        if (generatorsByVmId != null) {
            throw new IllegalStateException("This failure model uses VM-ID-keyed generator rows, not a dense matrix");
        }
        return generators;
    }

    public static int getFailureGeneratorsMaxFirstIndex() {
        requireGenerators();
        if (generatorsByVmId != null) {
            throw new IllegalStateException("VM-ID-keyed generator rows do not have a dense first dimension");
        }
        return generators.length;
    }

    public static int getFailureGeneratorsMaxSecondIndex() {
        requireGenerators();
        if (generatorsByVmId != null) {
            throw new IllegalStateException("VM-ID-keyed generator rows do not have a shared dense second dimension");
        }
        return generators[0].length;
    }

    public static DistributionGenerator getGenerator(int vmIndex, int taskDepth) {
        requireGenerators();
        if (generatorsByVmId != null) {
            DistributionGenerator[] row = generatorsByVmId.get(Integer.valueOf(vmIndex));
            if (vmIndex < 0 || row == null || taskDepth < 0 || taskDepth >= row.length) {
                throw missingGenerator(vmIndex, taskDepth);
            }
            return row[taskDepth];
        }
        if (vmIndex < 0 || vmIndex >= generators.length || taskDepth < 0 || taskDepth >= generators[vmIndex].length) {
            throw missingGenerator(vmIndex, taskDepth);
        }
        return generators[vmIndex][taskDepth];
    }

    public static DistributionGenerator getGeneratorForFailureScope(int vmId, int taskDepth) {
        switch (failureMode) {
            case FAILURE_ALL:
                return getGenerator(0, 0);
            case FAILURE_JOB:
                return getGenerator(0, taskDepth);
            case FAILURE_VM:
                return getGenerator(vmId, 0);
            case FAILURE_VM_JOB:
                return getGenerator(vmId, taskDepth);
            case FAILURE_NONE:
            default:
                throw new IllegalStateException(
                        "Failure generator is unavailable because failure mode is " + failureMode);
        }
    }

    public static void validateRuntimeCoverage(Collection<Task> tasks, Collection<Integer> vmIds) {
        if (failureMode == FTCFailure.FAILURE_NONE) {
            return;
        }
        if (tasks == null || vmIds == null) {
            throw new IllegalArgumentException("Failure coverage validation requires tasks and VM IDs");
        }
        TreeSet<Integer> observedDepths = collectObservedDepths(tasks);
        TreeSet<Integer> declaredVmIds = collectDeclaredVmIds(vmIds);

        switch (failureMode) {
            case FAILURE_ALL:
                validateCoverageCoordinate(0, 0, declaredVmIds, observedDepths);
                return;
            case FAILURE_JOB:
                requireObservedDepths(observedDepths);
                for (Integer depth : observedDepths) {
                    validateCoverageCoordinate(0, depth.intValue(), declaredVmIds, observedDepths);
                }
                return;
            case FAILURE_VM:
                requireDeclaredVmIds(declaredVmIds);
                for (Integer vmId : declaredVmIds) {
                    validateCoverageCoordinate(vmId.intValue(), 0, declaredVmIds, observedDepths);
                }
                return;
            case FAILURE_VM_JOB:
                requireDeclaredVmIds(declaredVmIds);
                requireObservedDepths(observedDepths);
                for (Integer vmId : declaredVmIds) {
                    for (Integer depth : observedDepths) {
                        validateCoverageCoordinate(vmId.intValue(), depth.intValue(), declaredVmIds, observedDepths);
                    }
                }
                return;
            case FAILURE_NONE:
            default:
                return;
        }
    }

    private static TreeSet<Integer> collectObservedDepths(Collection<Task> tasks) {
        TreeSet<Integer> observedDepths = new TreeSet<Integer>();
        for (Task task : tasks) {
            if (task == null || task.getDepth() <= 0) {
                throw new IllegalStateException(
                        "Failure coverage validation requires parsed tasks with positive DAG depths");
            }
            observedDepths.add(Integer.valueOf(task.getDepth()));
        }
        return observedDepths;
    }

    private static TreeSet<Integer> collectDeclaredVmIds(Collection<Integer> vmIds) {
        TreeSet<Integer> declaredVmIds = new TreeSet<Integer>();
        for (Integer vmId : vmIds) {
            if (vmId == null || vmId.intValue() < 0) {
                throw new IllegalStateException("Failure coverage validation requires non-negative VM IDs");
            }
            declaredVmIds.add(vmId);
        }
        return declaredVmIds;
    }

    private static void validateCoverageCoordinate(
            int vmId, int depth, TreeSet<Integer> declaredVmIds, TreeSet<Integer> observedDepths) {
        try {
            getGenerator(vmId, depth);
        } catch (IllegalStateException exception) {
            throw new IllegalStateException(
                    "Failure generator coverage validation failed before job dispatch: "
                            + "mode=" + failureMode + ", required=[vm=" + vmId + ", depth=" + depth
                            + "], declaredVmIds=" + declaredVmIds + ", observedDepths=" + observedDepths,
                    exception);
        }
    }

    private static void requireDeclaredVmIds(TreeSet<Integer> vmIds) {
        if (vmIds.isEmpty()) {
            throw new IllegalStateException(
                    "Failure generator coverage validation requires at least one VM ID for " + failureMode);
        }
    }

    private static void requireObservedDepths(TreeSet<Integer> depths) {
        if (depths.isEmpty()) {
            throw new IllegalStateException(
                    "Failure generator coverage validation requires at least one parsed task for " + failureMode);
        }
    }

    private static IllegalStateException missingGenerator(int vmIndex, int taskDepth) {
        String addressing = generatorsByVmId == null ? "dense VM-ID matrix" : "VM-ID-keyed rows";
        return new IllegalStateException("Failure generator " + addressing + " does not cover vm=" + vmIndex
                + ", taskDepth=" + taskDepth + " for mode " + failureMode);
    }

    public static FTCFailure getFailureGeneratorMode() {
        return failureMode;
    }

    public static FTCMonitor getMonitorMode() {
        return monitorMode;
    }

    public static FTCluteringAlgorithm getFTCluteringAlgorithm() {
        return FTClusteringAlgorithm;
    }

    public static DistributionFamily getFailureDistribution() {
        return distribution;
    }

    public static int getMaxTotalRetryJobs() {
        return maxTotalRetryJobs;
    }

    public static long getRootSeed() {
        return rootSeed;
    }

    private static void validateGenerators(
            FTCFailure failure,
            DistributionGenerator[][] failureGenerators,
            Map<Integer, DistributionGenerator[]> failureGeneratorsByVmId) {
        if (failure == FTCFailure.FAILURE_NONE) {
            if (failureGenerators != null && failureGenerators.length != 0) {
                throw new IllegalArgumentException("Disabled failure mode cannot define generators");
            }
            if (failureGeneratorsByVmId != null && !failureGeneratorsByVmId.isEmpty()) {
                throw new IllegalArgumentException("Disabled failure mode cannot define VM-ID-keyed generators");
            }
            return;
        }
        boolean hasDenseMatrix = failureGenerators != null && failureGenerators.length != 0;
        boolean hasVmIdKeyedRows = failureGeneratorsByVmId != null && !failureGeneratorsByVmId.isEmpty();
        if (hasDenseMatrix == hasVmIdKeyedRows) {
            throw new IllegalArgumentException("Enabled failure mode requires exactly one generator layout");
        }
        if (hasVmIdKeyedRows) {
            validateVmIdKeyedGeneratorRows(failure, failureGeneratorsByVmId);
            return;
        }
        validateDenseGeneratorMatrix(failureGenerators);
    }

    private static void validateVmIdKeyedGeneratorRows(
            FTCFailure failure, Map<Integer, DistributionGenerator[]> failureGeneratorsByVmId) {
        if (failure != FTCFailure.FAILURE_VM && failure != FTCFailure.FAILURE_VM_JOB) {
            throw new IllegalArgumentException("VM-ID-keyed generators require FAILURE_VM or FAILURE_VM_JOB");
        }
        for (Map.Entry<Integer, DistributionGenerator[]> entry : failureGeneratorsByVmId.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().intValue() < 0
                    || entry.getValue() == null
                    || entry.getValue().length == 0) {
                throw new IllegalArgumentException("VM-ID-keyed failure generator row is invalid");
            }
            for (int column = 0; column < entry.getValue().length; column++) {
                if (entry.getValue()[column] == null) {
                    throw new IllegalArgumentException(
                            "Failure generator for VM " + entry.getKey() + " at depth " + column + " is null");
                }
            }
        }
    }

    private static void validateDenseGeneratorMatrix(DistributionGenerator[][] failureGenerators) {
        for (int row = 0; row < failureGenerators.length; row++) {
            if (failureGenerators[row] == null || failureGenerators[row].length == 0) {
                throw new IllegalArgumentException("Failure generator row " + row + " is empty");
            }
            for (int column = 0; column < failureGenerators[row].length; column++) {
                if (failureGenerators[row][column] == null) {
                    throw new IllegalArgumentException("Failure generator [" + row + "][" + column + "] is null");
                }
            }
        }
    }

    private static void requireGenerators() {
        if (failureMode == FTCFailure.FAILURE_NONE) {
            throw new IllegalStateException("Failure generators are unavailable because failure mode is disabled");
        }
        boolean hasDenseMatrix = generators != null && generators.length != 0;
        boolean hasVmIdKeyedRows = generatorsByVmId != null && !generatorsByVmId.isEmpty();
        if (!hasDenseMatrix && !hasVmIdKeyedRows) {
            throw new IllegalStateException("Failure generators are not initialized for this simulation run");
        }
    }
}
