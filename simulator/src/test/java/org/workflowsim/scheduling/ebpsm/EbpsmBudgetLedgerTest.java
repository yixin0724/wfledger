package org.workflowsim.scheduling.ebpsm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Hand-derived checks of Algorithm 3 (Budget Update) on the diamond fixture of {@link EbpsmBudgetDistributorTest}
 * with {@code beta_w = 25}: Algorithm 1 gives t1 9 (fast), t3 3, t2 7, t4 5 and spare {@code sb = 1}.
 *
 * <p>FGCS-R5 (2026-11-24): every test also checks the conservation law
 * {@code beta_w = spent + committed sub-budgets + sb}.
 */
class EbpsmBudgetLedgerTest {

    /**
     * t1 finishes with actual cost 6 < 9. {@code T_u = {t2, t3, t4}} holds 7 + 3 + 5 = 15; the pool
     * {@code tf.budget + sb = 10} covers 6, so {@code beta_u = 15 + 4 = 19}. Redistribution over {@code T_u}: t2 and
     * t3 are level 0 (EFTs 6 and 2), so S = t3, t2, t4; the cheapest allocation is 15 (residual 4); SFTD upgrades t3
     * (+3, residual 1) and cannot afford t2 (+5) or t4 (+4). Result: t3 6, t2 7, t4 5, sb 1; 6 + 18 + 1 = 25.
     */
    @Test
    void cheaperCompletionReleasesSpareBudgetToUnscheduledTasks() {
        EbpsmBudgetLedger ledger = ledger();
        ledger.markScheduled(1);
        ledger.completed(1, 6.0);

        assertEquals(6.0, ledger.budgetOf(3), 1.0e-9);
        assertEquals(7.0, ledger.budgetOf(2), 1.0e-9);
        assertEquals(5.0, ledger.budgetOf(4), 1.0e-9);
        assertEquals(1.0, ledger.spareBudget(0), 1.0e-9);
        assertConserved(ledger);
    }

    /**
     * Debt case: t1 finishes with actual cost 14 > pool 10, so debt 4 and {@code beta_u = 15 - 4 = 11}. Algorithm 1
     * over S = t3, t2, t4: 3 (beta 8), 7 (beta 1), 5 (beta -4). Result: t3 3, t2 7, t4 5, sb -4; 14 + 15 - 4 = 25.
     */
    @Test
    void costlierCompletionChargesTheDebtToUnscheduledTasks() {
        EbpsmBudgetLedger ledger = ledger();
        ledger.markScheduled(1);
        ledger.completed(1, 14.0);

        assertEquals(3.0, ledger.budgetOf(3), 1.0e-9);
        assertEquals(7.0, ledger.budgetOf(2), 1.0e-9);
        assertEquals(5.0, ledger.budgetOf(4), 1.0e-9);
        assertEquals(-4.0, ledger.spareBudget(0), 1.0e-9);
        assertConserved(ledger);
    }

    /**
     * A failed attempt of t1 costing 3 is charged with a zero sub-budget and t1 returns to {@code T_u}: T_u holds
     * 9 + 7 + 3 + 5 = 24, pool {@code 0 + sb = 1} < 3, debt 2, {@code beta_u = 22}. Algorithm 1 over all four tasks:
     * cheapest 20, residual 2, no upgrade fits (t1 +4, t3 +3, t2 +5, t4 +4). Result: 5, 3, 7, 5, sb 2;
     * 3 + 20 + 2 = 25.
     */
    @Test
    void failedAttemptIsChargedAndItsTaskRejoinsTheUnscheduledSet() {
        EbpsmBudgetLedger ledger = ledger();
        ledger.markScheduled(1);
        ledger.failed(1, 3.0);

        assertEquals(5.0, ledger.budgetOf(1), 1.0e-9);
        assertEquals(3.0, ledger.budgetOf(3), 1.0e-9);
        assertEquals(7.0, ledger.budgetOf(2), 1.0e-9);
        assertEquals(5.0, ledger.budgetOf(4), 1.0e-9);
        assertEquals(2.0, ledger.spareBudget(0), 1.0e-9);
        assertEquals(3.0, ledger.spentBudget(0), 1.0e-9);
        assertConserved(ledger);
        ledger.markScheduled(1);
    }

    @Test
    void rejectsACompletionOfAnUndispatchedTask() {
        EbpsmBudgetLedger ledger = ledger();
        assertThrows(IllegalStateException.class, () -> ledger.completed(2, 1.0));
    }

    private static void assertConserved(EbpsmBudgetLedger ledger) {
        assertEquals(
                ledger.workflowBudget(0),
                ledger.spentBudget(0) + ledger.committedBudget(0) + ledger.spareBudget(0),
                1.0e-9);
    }

    private static EbpsmBudgetLedger ledger() {
        Map<Integer, java.util.List<EbpsmTask>> tasks = new TreeMap<Integer, java.util.List<EbpsmTask>>();
        tasks.put(0, EbpsmBudgetDistributorTest.diamond());
        EbpsmBudgetLedger ledger = new EbpsmBudgetLedger(
                new EbpsmBudgetDistributor(1.0),
                EbpsmBudgetDistributorTest.types(),
                tasks,
                Collections.singletonMap(0, 25.0));
        assertEquals(9.0, ledger.budgetOf(1), 1.0e-9);
        assertEquals(1.0, ledger.spareBudget(0), 1.0e-9);
        return ledger;
    }
}
