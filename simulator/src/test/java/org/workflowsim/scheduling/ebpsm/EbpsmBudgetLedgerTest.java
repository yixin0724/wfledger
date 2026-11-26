package org.workflowsim.scheduling.ebpsm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class EbpsmBudgetLedgerTest {

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
