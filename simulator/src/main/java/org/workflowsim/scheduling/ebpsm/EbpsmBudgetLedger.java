package org.workflowsim.scheduling.ebpsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Per-workflow budget state of EBPSM: Algorithm 1 at start and Algorithm 3 (Budget Update) on every returned
 * attempt (Hilman, Rodriguez and Buyya, arXiv:1903.01113).
 *
 * <p>FGCS-R5 (2026-11-24): root cause — the scheduler read static per-task budgets from {@code EbpsmConfig}, and
 * Algorithm 3 existed only as an unused helper, so a task that finished cheaper than its sub-budget could never
 * upgrade its successors and a costlier one never charged them. The ledger keeps, per workflow, the sub-budget of
 * every unscheduled task ({@code T_u}), the sub-budget of every dispatched task, and the spare budget {@code sb}.
 *
 * <p>Algorithm 3 is read as follows: {@code sb} is the budget the last {@code distributeBudget} call left
 * undistributed (negative when its last allocation overshot {@code beta}). On a completion with actual cost
 * {@code C}, the pool {@code tf.budget + sb} either covers {@code C} (the remainder joins {@code beta_u}) or the
 * shortfall is a debt subtracted from {@code beta_u}; in both cases {@code beta_u = sum(T_u budgets) + tf.budget +
 * sb - C}, Algorithm 1 redistributes it over {@code T_u}, and its residual becomes the new {@code sb}. Hence the
 * conservation law {@code beta_w = spent + sum(T_u budgets) + sum(dispatched budgets) + sb} holds after every
 * step, which the tests check.
 *
 * <p>Failed attempts: the paper's platform bills VM usage per period regardless of the outcome and Eq. 6 constrains
 * the actual cost. A failed attempt is therefore charged its actual cost through the same update with a zero
 * sub-budget (its sub-budget returns with the Task to {@code T_u}, where the retry will need it).
 */
public final class EbpsmBudgetLedger {

    private final EbpsmBudgetDistributor distributor;
    private final List<EbpsmVmType> vmTypes;
    private final Map<Integer, WorkflowState> workflows = new TreeMap<Integer, WorkflowState>();
    private final Map<Integer, Integer> workflowOfTask = new TreeMap<Integer, Integer>();

    /**
     * Creates the ledger and runs Algorithm 1 once per workflow.
     *
     * @param distributor Algorithm 1 implementation
     * @param vmTypes VM types derived from the declared pool
     * @param tasksByWorkflow every task of every workflow, keyed by workflow index
     * @param budgetsByWorkflow user-defined budget {@code beta_w} per workflow index
     */
    public EbpsmBudgetLedger(
            EbpsmBudgetDistributor distributor,
            List<EbpsmVmType> vmTypes,
            Map<Integer, List<EbpsmTask>> tasksByWorkflow,
            Map<Integer, Double> budgetsByWorkflow) {
        if (distributor == null || vmTypes == null || tasksByWorkflow == null || budgetsByWorkflow == null) {
            throw new IllegalArgumentException("EBPSM ledger requires a distributor, VM types, tasks and budgets");
        }
        this.distributor = distributor;
        this.vmTypes = Collections.unmodifiableList(new ArrayList<EbpsmVmType>(vmTypes));
        for (Integer workflow : budgetsByWorkflow.keySet()) {
            if (!tasksByWorkflow.containsKey(workflow)) {
                throw new IllegalArgumentException("EBPSM budget declared for unknown workflow " + workflow);
            }
        }
        for (Map.Entry<Integer, List<EbpsmTask>> entry :
                new TreeMap<Integer, List<EbpsmTask>>(tasksByWorkflow).entrySet()) {
            Double budget = budgetsByWorkflow.get(entry.getKey());
            if (budget == null) {
                throw new IllegalArgumentException("No EBPSM budget declared for workflow " + entry.getKey());
            }
            WorkflowState state = new WorkflowState(budget);
            for (EbpsmTask task : entry.getValue()) {
                if (workflowOfTask.put(task.getTaskId(), entry.getKey()) != null) {
                    throw new IllegalArgumentException("EBPSM task " + task.getTaskId() + " is in two workflows");
                }
                state.tasks.put(task.getTaskId(), task);
                state.unscheduled.add(task.getTaskId());
            }
            workflows.put(entry.getKey(), state);
            redistribute(state, budget);
        }
    }

    /**
     * Returns the current sub-budget of a task (unscheduled or dispatched).
     *
     * @param taskId task ID
     * @return sub-budget
     */
    public double budgetOf(int taskId) {
        Double value = state(taskId).budgets.get(taskId);
        if (value == null) {
            throw new IllegalStateException("EBPSM task " + taskId + " has no sub-budget");
        }
        return value;
    }

    /**
     * Moves a task from {@code T_u} to the dispatched set.
     *
     * @param taskId dispatched task
     */
    public void markScheduled(int taskId) {
        WorkflowState state = state(taskId);
        if (!state.unscheduled.remove(taskId)) {
            throw new IllegalStateException("EBPSM task " + taskId + " is not unscheduled and cannot be dispatched");
        }
        state.dispatched.add(taskId);
    }

    /**
     * Applies Algorithm 3 for a successful attempt.
     *
     * @param taskId completed task {@code t_f}
     * @param actualCost actual Eq. 5 cost of the attempt
     */
    public void completed(int taskId, double actualCost) {
        WorkflowState state = dispatchedState(taskId, actualCost);
        double budget = state.budgets.remove(taskId);
        state.dispatched.remove(taskId);
        state.spent += actualCost;
        update(state, budget, actualCost);
    }

    /**
     * Charges a failed attempt and returns its task to {@code T_u} with its sub-budget.
     *
     * @param taskId task of the failed attempt
     * @param actualCost actual Eq. 5 cost of the failed attempt
     */
    public void failed(int taskId, double actualCost) {
        WorkflowState state = dispatchedState(taskId, actualCost);
        state.dispatched.remove(taskId);
        state.unscheduled.add(taskId);
        state.spent += actualCost;
        update(state, 0.0, actualCost);
    }

    /**
     * @param workflow workflow index
     * @return spare budget {@code sb}
     */
    public double spareBudget(int workflow) {
        return workflowState(workflow).spare;
    }

    /**
     * @param workflow workflow index
     * @return actual cost charged so far, failed attempts included
     */
    public double spentBudget(int workflow) {
        return workflowState(workflow).spent;
    }

    /**
     * @param workflow workflow index
     * @return sum of the sub-budgets of unscheduled and dispatched tasks, in ascending task-ID order
     */
    public double committedBudget(int workflow) {
        double sum = 0.0;
        for (double value : workflowState(workflow).budgets.values()) {
            sum += value;
        }
        return sum;
    }

    /**
     * @param workflow workflow index
     * @return user-defined budget {@code beta_w}
     */
    public double workflowBudget(int workflow) {
        return workflowState(workflow).budget;
    }

    /**
     * @param taskId task ID
     * @return workflow index of the task
     */
    public int workflowOf(int taskId) {
        Integer workflow = workflowOfTask.get(taskId);
        if (workflow == null) {
            throw new IllegalStateException("EBPSM task " + taskId + " belongs to no budgeted workflow");
        }
        return workflow;
    }

    private void update(WorkflowState state, double completedBudget, double actualCost) {
        double unscheduled = 0.0;
        for (Integer id : state.unscheduled) {
            unscheduled += state.budgets.get(id);
        }
        double pool = completedBudget + state.spare;
        double betaU;
        if (actualCost <= pool) {
            betaU = unscheduled + (pool - actualCost);
        } else {
            betaU = unscheduled - (actualCost - pool);
        }
        redistribute(state, betaU);
    }

    private void redistribute(WorkflowState state, double beta) {
        List<EbpsmTask> residual = new ArrayList<EbpsmTask>();
        for (Integer id : state.unscheduled) {
            residual.add(state.tasks.get(id));
        }
        EbpsmBudgetDistributor.Allocation allocation = distributor.distribute(residual, vmTypes, beta);
        for (Map.Entry<Integer, Double> entry : allocation.getBudgets().entrySet()) {
            state.budgets.put(entry.getKey(), entry.getValue());
        }
        state.spare = allocation.getResidual();
    }

    private WorkflowState dispatchedState(int taskId, double actualCost) {
        if (!(actualCost >= 0.0) || !Double.isFinite(actualCost)) {
            throw new IllegalArgumentException("EBPSM actual cost must be finite and non-negative: " + actualCost);
        }
        WorkflowState state = state(taskId);
        if (!state.dispatched.contains(taskId)) {
            throw new IllegalStateException("EBPSM task " + taskId + " returned without being dispatched");
        }
        return state;
    }

    private WorkflowState state(int taskId) {
        return workflowState(workflowOf(taskId));
    }

    private WorkflowState workflowState(int workflow) {
        WorkflowState state = workflows.get(workflow);
        if (state == null) {
            throw new IllegalArgumentException("Unknown EBPSM workflow " + workflow);
        }
        return state;
    }

    private static final class WorkflowState {
        private final double budget;
        private final Map<Integer, EbpsmTask> tasks = new LinkedHashMap<Integer, EbpsmTask>();
        private final Map<Integer, Double> budgets = new TreeMap<Integer, Double>();
        private final TreeSet<Integer> unscheduled = new TreeSet<Integer>();
        private final TreeSet<Integer> dispatched = new TreeSet<Integer>();
        private double spare;
        private double spent;

        WorkflowState(double budget) {
            if (!(budget > 0.0) || !Double.isFinite(budget)) {
                throw new IllegalArgumentException("EBPSM workflow budget must be positive and finite: " + budget);
            }
            this.budget = budget;
        }
    }
}
