package org.workflowsim.data;

import java.math.BigDecimal;

public final class NetworkInputDemandTracker {
    private long references, localReferences;
    private BigDecimal requiredBytes = BigDecimal.ZERO, localBytes = BigDecimal.ZERO;

    public void record(double bytes, boolean local) {
        if (!Double.isFinite(bytes) || bytes < 0) {
            throw new IllegalArgumentException("Input reference bytes must be finite and nonnegative");
        }
        long next = Math.addExact(references, 1L),
                nextLocal = local ? Math.addExact(localReferences, 1L) : localReferences;
        BigDecimal amount = new BigDecimal(bytes);
        BigDecimal nextRequired = requiredBytes.add(amount),
                nextLocalBytes = local ? localBytes.add(amount) : localBytes;
        references = next;
        localReferences = nextLocal;
        requiredBytes = nextRequired;
        localBytes = nextLocalBytes;
    }

    public NetworkInputDemandSnapshot snapshot() {
        return NetworkInputDemandSnapshot.of(references, localReferences, requiredBytes, localBytes);
    }
}
