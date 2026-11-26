package org.workflowsim.scheduling.ebpsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class EbpsmBudgetLedger {

    private final EbpsmBudgetDistributor distributor;
    private final List<EbpsmVmType> vmTypes;
    private final Map<Integer, WorkflowState> workflows = new TreeMap<Integer, WorkflowState>();
    private final Map<Integer, Integer> workflowOfTask = new TreeMap<Integer, Integer>();

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

    public double budgetOf(int taskId) {
        Double value = state(taskId).budgets.get(taskId);
        if (value == null) {
            throw new IllegalStateException("EBPSM task " + taskId + " has no sub-budget");
        }
        return value;
    }

    public void markScheduled(int taskId) {
        WorkflowState state = state(taskId);
        if (!state.unscheduled.remove(taskId)) {
            throw new IllegalStateException("EBPSM task " + taskId + " is not unscheduled and cannot be dispatched");
        }
        state.dispatched.add(taskId);
    }

    public void completed(int taskId, double actualCost) {
        WorkflowState state = dispatchedState(taskId, actualCost);
        double budget = state.budgets.remove(taskId);
        state.dispatched.remove(taskId);
        state.spent += actualCost;
        update(state, budget, actualCost);
    }

    public void failed(int taskId, double actualCost) {
        WorkflowState state = dispatchedState(taskId, actualCost);
        state.dispatched.remove(taskId);
        state.unscheduled.add(taskId);
        state.spent += actualCost;
        update(state, 0.0, actualCost);
    }

    public double spareBudget(int workflow) {
        return workflowState(workflow).spare;
    }

    public double spentBudget(int workflow) {
        return workflowState(workflow).spent;
    }

    public double committedBudget(int workflow) {
        double sum = 0.0;
        for (double value : workflowState(workflow).budgets.values()) {
            sum += value;
        }
        return sum;
    }

    public double workflowBudget(int workflow) {
        return workflowState(workflow).budget;
    }

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
