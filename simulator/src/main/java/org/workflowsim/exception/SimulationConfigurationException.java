package org.workflowsim.exception;

public final class SimulationConfigurationException extends RuntimeException {

    public SimulationConfigurationException(String message) {
        super(message);
    }

    public SimulationConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
