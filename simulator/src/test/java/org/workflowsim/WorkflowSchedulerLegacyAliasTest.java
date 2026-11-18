package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;

import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.scheduling.FastestVmSchedulingAlgorithm;
import org.workflowsim.scheduling.FirstFitIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.LjfFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.SptFastestIdleSchedulingAlgorithm;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;

class WorkflowSchedulerLegacyAliasTest {

    @BeforeEach
    void resetLegacyState() {
        Parameters.reset();
        CloudSim.init(1, Calendar.getInstance(), false);
    }

    @Test
    void rejectsADeprecatedLabelWithoutTheLegacyDeclaration() throws Exception {
        initSchedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN);
        WorkflowScheduler scheduler = new WorkflowScheduler("legacy-alias-reject-test");

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> scheduler.getSchedulingPolicy());
        assertTrue(
                failure.getMessage().contains("deprecated"),
                "the rejection must say the label is deprecated, got: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("SPT-fastest-idle"),
                "the rejection must name the actual policy, got: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("Parameters.enableLegacySchedulingAliases()"),
                "the rejection must name the legacy declaration, got: " + failure.getMessage());
    }

    @Test
    void bindsTheHistoricalPoliciesAfterTheLegacyDeclaration() throws Exception {
        Parameters.enableLegacySchedulingAliases();
        WorkflowScheduler scheduler = new WorkflowScheduler("legacy-alias-bind-test");

        initSchedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN);
        assertTrue(scheduler.getSchedulingPolicy() instanceof SptFastestIdleSchedulingAlgorithm);
        initSchedulingAlgorithm(Parameters.SchedulingAlgorithm.MAXMIN);
        assertTrue(scheduler.getSchedulingPolicy() instanceof LjfFastestIdleSchedulingAlgorithm);
        initSchedulingAlgorithm(Parameters.SchedulingAlgorithm.MCT);
        assertTrue(scheduler.getSchedulingPolicy() instanceof FastestVmSchedulingAlgorithm);
        initSchedulingAlgorithm(Parameters.SchedulingAlgorithm.ROUNDROBIN);
        assertTrue(scheduler.getSchedulingPolicy() instanceof FirstFitIdleSchedulingAlgorithm);
    }

    @Test
    void resetClearsTheLegacyDeclaration() {
        Parameters.enableLegacySchedulingAliases();
        assertTrue(Parameters.isLegacySchedulingAliasesEnabled());

        Parameters.reset();

        assertFalse(Parameters.isLegacySchedulingAliasesEnabled());
    }

    private static void initSchedulingAlgorithm(Parameters.SchedulingAlgorithm algorithm) {
        Parameters.init(
                1,
                (String) null,
                null,
                null,
                new OverheadParameters(0, null, null, null, null, 0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                algorithm,
                Parameters.PlanningAlgorithm.INVALID,
                null,
                0);
    }
}
