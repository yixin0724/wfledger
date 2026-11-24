package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;

import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.scheduling.StaticSchedulingAlgorithm;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;

class WorkflowSchedulerExplicitBindingTest {

    @BeforeEach
    void resetLegacyState() {
        Parameters.reset();
        CloudSim.init(1, Calendar.getInstance(), false);
    }

    @Test
    void everyEnumValueHasAnExplicitPolicyBinding() throws Exception {
        WorkflowScheduler scheduler = new WorkflowScheduler("explicit-binding-test");
        for (Parameters.SchedulingAlgorithm label : Parameters.SchedulingAlgorithm.values()) {
            initSchedulingAlgorithm(label);
            assertNotNull(
                    scheduler.getSchedulingPolicy(), "enum value " + label + " must have an explicit policy binding");
        }
    }

    @Test
    void invalidIsTheExplicitNoDecisionBindingShell() throws Exception {
        initSchedulingAlgorithm(Parameters.SchedulingAlgorithm.INVALID);
        WorkflowScheduler scheduler = new WorkflowScheduler("explicit-invalid-test");

        assertTrue(scheduler.getSchedulingPolicy() instanceof StaticSchedulingAlgorithm);
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
