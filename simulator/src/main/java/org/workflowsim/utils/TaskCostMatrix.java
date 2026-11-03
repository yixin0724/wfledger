package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task-by-VM heterogeneous execution cost matrix -- a platform capability added in the paper
 * reproduction round.
 *
 * <p><b>Motivation</b>: the native WorkflowSim execution model is "a single MI divided by a
 * uniform MIPS scale" (execution seconds = cloudletLength / vmMips), so each VM's cost can only be
 * a linear scaling of the same task's cost. Classic DAG scheduling papers (HEFT/CPOP/DLS/ETF/PEFT
 * and others) generally use an <b>arbitrary task-by-processor cost matrix</b> (for example
 * Topcuoglu 2002 Table I: every task has an independent cost on every processor and does not
 * satisfy linear scaling). Without this matrix, an exact numerical reproduction of such papers is
 * blocked entirely.</p>
 *
 * <p><b>Semantics</b>: matrix entry {@code (taskId, vmId) -> execution seconds}. STATIC dispatch
 * converts by {@code MI = round(execution seconds * vm.mips)} before job submission, so that the
 * runtime execution time matches the matrix entry (subject to CloudSim integer-MI rounding, see
 * the WorkflowDatacenter conversion Javadoc). Planners read the matrix directly for cost
 * estimation.</p>
 *
 * <p><b>Contract</b>:</p>
 * <ul>
 *   <li>The matrix is an optional configuration: when it is not configured the platform keeps the
 *       existing MI/mips scaling behavior and the reference numbers are unchanged;</li>
 *   <li>Configuring the matrix requires STATIC dispatch (the online schedulers perform no
 *       conversion) and the NONE clustering method (jobs must correspond one-to-one with logical
 *       tasks for the conversion to have a unique VM target);</li>
 *   <li>The matrix must cover every task ID x every VM ID appearing in the simulation: a missing
 *       entry fails fast at the application stage and never falls back silently;</li>
 *   <li>Entries must be positive finite seconds.</li>
 * </ul>
 *
 * <p>This class is an immutable value object: construction deep-copies it into immutable maps.</p>
 */
public final class TaskCostMatrix {

    private final Map<Integer, Map<Integer, Double>> costSecondsByTaskAndVm;

    private TaskCostMatrix(Map<Integer, Map<Integer, Double>> source) {
        Map<Integer, Map<Integer, Double>> deepCopy = new LinkedHashMap<Integer, Map<Integer, Double>>();
        for (Map.Entry<Integer, Map<Integer, Double>> entry : source.entrySet()) {
            deepCopy.put(
                    entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(entry.getValue())));
        }
        this.costSecondsByTaskAndVm = Collections.unmodifiableMap(deepCopy);
    }

    /**
     * Return a new builder.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Return the execution seconds of a task on a VM.
     *
     * @param taskId logical task ID (cloudlet ID)
     * @param vmId VM ID
     * @return execution seconds
     * @throws IllegalArgumentException when the entry is missing (fail-fast, no silent fallback)
     */
    public double getCostSeconds(int taskId, int vmId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        if (perVm == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId);
        }
        Double seconds = perVm.get(Integer.valueOf(vmId));
        if (seconds == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId + " on VM " + vmId);
        }
        return seconds.doubleValue();
    }

    /**
     * Return the set of task IDs covered by the matrix (immutable, build order).
     *
     * @return task ID set
     */
    public java.util.Set<Integer> getTaskIds() {
        return costSecondsByTaskAndVm.keySet();
    }

    /**
     * Return the execution seconds of the given task on every registered VM (immutable snapshot).
     *
     * <p>This accessor exists for evidence writing and audit tools; callers cannot modify the matrix
     * internal state through the return value.</p>
     *
     * @param taskId logical task ID
     * @return immutable map from VM ID to execution seconds
     * @throws IllegalArgumentException when the task has no matrix entry
     */
    public Map<Integer, Double> getCostsForTask(int taskId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        if (perVm == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(perVm));
    }

    /**
     * Return an immutable deep copy of the full task-by-VM matrix.
     *
     * @return immutable snapshot from task ID to the per-VM cost map
     */
    public Map<Integer, Map<Integer, Double>> asMap() {
        Map<Integer, Map<Integer, Double>> copy = new LinkedHashMap<Integer, Map<Integer, Double>>();
        for (Map.Entry<Integer, Map<Integer, Double>> entry : costSecondsByTaskAndVm.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Return whether a task-by-VM pair has a registered entry.
     *
     * @param taskId logical task ID (cloudlet ID)
     * @param vmId VM ID
     * @return true when a registered entry exists
     */
    public boolean contains(int taskId, int vmId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        return perVm != null && perVm.containsKey(Integer.valueOf(vmId));
    }

    /**
     * Return whether the matrix covers the given task IDs and VM IDs.
     *
     * @param taskIds task IDs that must be covered
     * @param vmIds VM IDs that must be covered
     * @return true when all are covered
     */
    public boolean covers(Iterable<Integer> taskIds, Iterable<Integer> vmIds) {
        for (Integer taskId : taskIds) {
            Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(taskId);
            if (perVm == null) {
                return false;
            }
            for (Integer vmId : vmIds) {
                if (!perVm.containsKey(vmId)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Mutable builder for TaskCostMatrix. */
    public static final class Builder {

        private final Map<Integer, Map<Integer, Double>> entries = new LinkedHashMap<Integer, Map<Integer, Double>>();

        private Builder() {}

        /**
         * Register one entry.
         *
         * @param taskId logical task ID (cloudlet ID)
         * @param vmId VM ID
         * @param costSeconds execution seconds of that task on that VM; must be a positive finite
         *     value
         * @return this builder
         * @throws IllegalArgumentException when the seconds are non-positive/non-finite, or the
         *     entry is registered twice
         */
        public Builder put(int taskId, int vmId, double costSeconds) {
            if (!(costSeconds > 0.0) || Double.isNaN(costSeconds) || Double.isInfinite(costSeconds)) {
                throw new IllegalArgumentException("Task cost matrix entry must be positive finite seconds: " + "task "
                        + taskId + " on VM " + vmId + " got " + costSeconds);
            }
            Map<Integer, Double> perVm = entries.get(Integer.valueOf(taskId));
            if (perVm == null) {
                perVm = new LinkedHashMap<Integer, Double>();
                entries.put(Integer.valueOf(taskId), perVm);
            }
            if (perVm.put(Integer.valueOf(vmId), Double.valueOf(costSeconds)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate task cost matrix entry for task " + taskId + " on VM " + vmId);
            }
            return this;
        }

        /**
         * Build the immutable matrix.
         *
         * @return immutable TaskCostMatrix
         * @throws IllegalArgumentException when the matrix is empty
         */
        public TaskCostMatrix build() {
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("Task cost matrix must contain at least one entry");
            }
            return new TaskCostMatrix(entries);
        }
    }
}
