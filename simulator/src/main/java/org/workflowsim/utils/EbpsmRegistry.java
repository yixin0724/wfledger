package org.workflowsim.utils;

public final class EbpsmRegistry {
    private static EbpsmConfig config;

    private EbpsmRegistry() {}

    public static synchronized void configure(EbpsmConfig value) {
        if (value == null) {
            throw new IllegalArgumentException("EBPSM configuration cannot be null");
        }
        config = value;
    }

    public static synchronized EbpsmConfig get() {
        if (config == null) {
            throw new IllegalStateException("EBPSM requires EbpsmRegistry.configure before the run");
        }
        return config;
    }

    public static synchronized void clear() {
        config = null;
    }
}
