package org.workflowsim.utils;

/** Session-scoped Reshi input registry; Parameters.reset() clears it between runs. */
public final class ReshiRegistry {

    private static ReshiConfig config;

    private ReshiRegistry() {}

    public static synchronized void configure(ReshiConfig value) {
        if (value == null) {
            throw new IllegalArgumentException("Reshi configuration cannot be null");
        }
        config = value;
    }

    public static synchronized ReshiConfig get() {
        if (config == null) {
            throw new IllegalStateException("Reshi requires ReshiRegistry.configure before the run");
        }
        return config;
    }

    public static synchronized void clear() {
        config = null;
    }
}
