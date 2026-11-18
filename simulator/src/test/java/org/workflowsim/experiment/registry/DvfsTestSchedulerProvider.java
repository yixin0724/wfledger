package org.workflowsim.experiment.registry;

import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

public final class DvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    @Override
    public String label() {
        return "dvfs-test-required";
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        throw new IllegalStateException("The DVFS test provider never schedules");
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .frequencyTiersRequired(true)
                .description("dvfs frequency-tiers-required test provider")
                .build();
    }
}
