package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Vm;
import org.workflowsim.Task;
import org.workflowsim.utils.TaskExecutionModel;

/**
 * PSO fitness function (faithful port of {@code meysamhit/workflowsim-pso}).
 *
 * <p>{@code fitness = COST_WEIGHT * totalCost + (1 - COST_WEIGHT) * makespan}</p>
 *
 * <p>Cost model of the reference implementation (from the simplified cloud pricing of
 * Pandey et al., AINA 2010):</p>
 * <ul>
 *   <li>VM unit price {@code price = mips / 1000} (the faster, the more expensive);</li>
 *   <li>task cost = execution time * VM unit price; without a matrix the execution time
 *       is the single-PE {@code cloudletLength / mips}, with a matrix it is the
 *       effective seconds derived by {@link TaskExecutionModel};</li>
 *   <li>VM load = sum of the task execution times on that VM (<b>sequential execution
 *       model</b>);</li>
 *   <li>makespan = maximum of the per-VM loads.</li>
 * </ul>
 *
 * <p><b>Cost-term boundary</b>: only under the matrix-free raw-MI model,
 * {@code (length / mips) * (mips / 1000) = length / 1000}, so the total cost is
 * mathematically independent of the mapping. When a matrix is present the cost uses
 * {@code round(seconds * mips) / mips}, hence
 * {@code cost = sum(effectiveSeconds(task, vm) * mips(vm) / 1000)} can change with the
 * mapping. This does not make the price formula a calibrated cloud cost model;
 * floating-point rounding is not an economically meaningful cost difference either.</p>
 *
 * <p><b>Fidelity statement</b>: this fitness <b>ignores DAG dependency edges</b> (the VM
 * load is a sequential accumulation, with no dependency-aware completion-time
 * projection) -- this is the established behavior of the reference implementation (and
 * of the simplified cost model in the paper), deliberately preserved at porting time.
 * Real execution at runtime is still constrained by the engine's dependency release, so
 * the planning-side makespan estimate and the runtime-side makespan may differ; for
 * controlled experiments the runtime-side metric prevails.</p>
 *
 * @since WorkflowSim Toolkit 1.0 (paper reproduction round)
 */
public final class PsoFitnessFunction {

    private PsoFitnessFunction() {}

    /**
     * Computes the fitness of one particle position (smaller is better).
     *
     * @param position Task-to-VM index mapping (each index points into the {@code vms}
     *                 list)
     * @param tasks task list (one-to-one with position)
     * @param vms VM candidate list (already sorted by VM ID)
     * @param costWeight cost weight in [0,1]
     * @return weighted fitness; positive infinity for a PE-incompatible position
     * @throws IllegalArgumentException when a list, an index, or an execution cost is
     *         invalid
     */
    public static double evaluate(int[] position, List<Task> tasks, List<? extends Vm> vms, double costWeight) {
        if (tasks == null || tasks.isEmpty() || vms == null || vms.isEmpty()) {
            throw new IllegalArgumentException("PSO fitness requires non-empty task and VM lists");
        }
        if (position == null || position.length != tasks.size()) {
            throw new IllegalArgumentException("PSO position must match task count");
        }
        Map<Integer, List<Task>> vmTasks = new HashMap<Integer, List<Task>>();
        for (int i = 0; i < tasks.size(); i++) {
            int vmIndex = position[i];
            if (vmIndex < 0 || vmIndex >= vms.size()) {
                throw new IllegalArgumentException("PSO position value out of range: " + vmIndex);
            }
            if (tasks.get(i).getNumberOfPes() > vms.get(vmIndex).getNumberOfPes()) {
                return Double.POSITIVE_INFINITY;
            }
            List<Task> bucket = vmTasks.get(Integer.valueOf(vmIndex));
            if (bucket == null) {
                bucket = new ArrayList<Task>();
                vmTasks.put(Integer.valueOf(vmIndex), bucket);
            }
            bucket.add(tasks.get(i));
        }

        double totalCost = 0.0;
        double makespan = 0.0;
        for (Map.Entry<Integer, List<Task>> entry : vmTasks.entrySet()) {
            Vm vm = vms.get(entry.getKey().intValue());
            double vmLoad = 0.0;
            for (Task task : entry.getValue()) {
                double execTime = TaskExecutionModel.executionSeconds(task, vm.getId(), vm.getMips());
                vmLoad += execTime;
                totalCost += execTime * getPrice(vm);
            }
            makespan = Math.max(makespan, vmLoad);
        }
        return costWeight * totalCost + (1.0 - costWeight) * makespan;
    }

    /**
     * Simple pricing model: the faster the VM, the higher the unit price.
     *
     * @param vm target VM
     * @return price per time unit = mips / 1000
     */
    public static double getPrice(Vm vm) {
        return vm.getMips() / 1000.0;
    }
}
