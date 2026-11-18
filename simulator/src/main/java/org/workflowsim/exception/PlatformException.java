package org.workflowsim.exception;

public final class PlatformException extends RuntimeException {

    public PlatformException(String message) {
        super(message);
    }

    public PlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}
