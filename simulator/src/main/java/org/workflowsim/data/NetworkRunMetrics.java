package org.workflowsim.data;

import java.math.BigDecimal;
import java.math.MathContext;

public final class NetworkRunMetrics {

    public enum LocalityStatus {
        DISABLED,

        TRUNCATED_TRACE,

        INPUT_COUNTERS_UNAVAILABLE,

        AVAILABLE
    }

    private final String localityScope = "V1_GROUP_INPUT_REFERENCES";
    private final NetworkTraceMetrics transferMetrics;
    private final LocalityStatus localityStatus;
    private final Long inputReferenceCount, localInputReferenceCount;
    private final BigDecimal requiredInputReferenceBytes, localInputReferenceBytes, transferableInputReferenceBytes;
    private final BigDecimal admittedMinusTransferableReferenceBytes;
    private final Double localByteFraction, localReferenceFraction;

    private NetworkRunMetrics(NetworkRunEvidence evidence) {
        transferMetrics = evidence == null
                ? null
                : NetworkTraceMetrics.calculate(
                        evidence.getTraceSnapshot(), evidence.getConfig().getMaxTraceRecords());
        NetworkInputDemandSnapshot input = evidence == null ? null : evidence.getInputDemand();
        localityStatus = evidence == null
                ? LocalityStatus.DISABLED
                : !transferMetrics.isAvailable()
                        ? LocalityStatus.TRUNCATED_TRACE
                        : input == null ? LocalityStatus.INPUT_COUNTERS_UNAVAILABLE : LocalityStatus.AVAILABLE;
        if (localityStatus != LocalityStatus.AVAILABLE) {
            inputReferenceCount = null;
            localInputReferenceCount = null;
            requiredInputReferenceBytes = null;
            localInputReferenceBytes = null;
            transferableInputReferenceBytes = null;
            admittedMinusTransferableReferenceBytes = null;
            localByteFraction = null;
            localReferenceFraction = null;
        } else {
            inputReferenceCount = input.getReferenceCount();
            localInputReferenceCount = input.getLocalReferenceCount();
            requiredInputReferenceBytes = input.getRequiredReferenceBytes();
            localInputReferenceBytes = input.getLocalReferenceBytes();
            transferableInputReferenceBytes = input.getTransferableReferenceBytes();
            admittedMinusTransferableReferenceBytes =
                    transferMetrics.getAdmittedPayloadBytes().subtract(transferableInputReferenceBytes);
            localByteFraction = requiredInputReferenceBytes.signum() == 0
                    ? null
                    : localInputReferenceBytes
                            .divide(requiredInputReferenceBytes, MathContext.DECIMAL128)
                            .doubleValue();
            localReferenceFraction = inputReferenceCount == 0
                    ? null
                    : BigDecimal.valueOf(localInputReferenceCount)
                            .divide(BigDecimal.valueOf(inputReferenceCount), MathContext.DECIMAL128)
                            .doubleValue();
        }
    }

    public static NetworkRunMetrics calculate(NetworkRunEvidence evidence) {
        return new NetworkRunMetrics(evidence);
    }

    public String getLocalityScope() {
        return localityScope;
    }

    public NetworkTraceMetrics getTransferMetrics() {
        return transferMetrics;
    }

    public LocalityStatus getLocalityStatus() {
        return localityStatus;
    }

    public Long getInputReferenceCount() {
        return inputReferenceCount;
    }

    public Long getLocalInputReferenceCount() {
        return localInputReferenceCount;
    }

    public BigDecimal getRequiredInputReferenceBytes() {
        return requiredInputReferenceBytes;
    }

    public BigDecimal getLocalInputReferenceBytes() {
        return localInputReferenceBytes;
    }

    public BigDecimal getTransferableInputReferenceBytes() {
        return transferableInputReferenceBytes;
    }

    public BigDecimal getAdmittedMinusTransferableReferenceBytes() {
        return admittedMinusTransferableReferenceBytes;
    }

    public Double getLocalByteFraction() {
        return localByteFraction;
    }

    public Double getLocalReferenceFraction() {
        return localReferenceFraction;
    }
}
