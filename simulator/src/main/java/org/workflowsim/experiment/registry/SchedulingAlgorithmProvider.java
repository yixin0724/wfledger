package org.workflowsim.experiment.registry;

import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

public interface SchedulingAlgorithmProvider {

    String label();

    BaseSchedulingAlgorithm create();

    AlgorithmDeclaration declaration();
}
