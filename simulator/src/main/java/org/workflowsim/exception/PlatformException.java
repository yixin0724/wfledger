package org.workflowsim.exception;

/**
 * Signals that a platform resource configuration or capacity constraint cannot be met.
 *
 * <p>This exception covers errors related to
 * {@link org.workflowsim.platform.PlatformProfile}, Host, VM, and storage resources,
 * including:</p>
 * <ul>
 *   <li>Infeasible VM-to-Host placement (insufficient PE count or capacity)</li>
 *   <li>Invalid Host configuration (for example negative MIPS or RAM)</li>
 *   <li>Failed compatibility check between the VM configuration and the Host</li>
 *   <li>Missing storage bandwidth or cost pricing information</li>
 *   <li>Failed creation of a CloudSim datacenter or storage entity</li>
 * </ul>
 *
 * <p>Callers should preflight resource constraints while building the platform
 * configuration, using the capacity validation facility of
 * {@link org.workflowsim.platform.PlatformProfile}.</p>
 *
 * <p><strong>Note:</strong> This exception extends {@link RuntimeException} because a
 * platform configuration error is a programming error or a misconfiguration and should
 * fail fast rather than require callers to catch it.</p>
 */
public final class PlatformException extends RuntimeException {

    /**
     * Creates the exception with a platform error description.
     *
     * @param message specific description of the platform error
     */
    public PlatformException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a platform error description and a root cause.
     *
     * @param message specific description of the platform error
     * @param cause underlying exception that caused the platform error
     */
    public PlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}
