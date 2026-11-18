package org.workflowsim.experiment.registry;

import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

public final class EnergyTestSchedulerProvider implements SchedulingAlgorithmProvider {

    @Override
    public String label() {
        return "energy-test-required";
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        throw new IllegalStateException("The energy test provider never schedules");
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .powerModelRequired(true)
                .description("energy power-required test provider")
                .build();
    }
}
