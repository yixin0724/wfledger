package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TransferTraceEvent {

    public enum Type {
        CAPACITY,

        START,

        RATE_CHANGE,

        SERVICE_SEGMENT,

        COMPLETE
    }

    public static final class Capacity {
        private final String resourceKey;
        private final double capacityBytesPerSecond;

        private Capacity(String resourceKey, double capacityBytesPerSecond) {
            this.resourceKey = resourceKey;
            this.capacityBytesPerSecond = capacityBytesPerSecond;
        }

        public String getResourceKey() {
            return resourceKey;
        }

        public double getCapacityBytesPerSecond() {
            return capacityBytesPerSecond;
        }
    }

    public static final class Start {
        private final double bytes;
        private final List<String> occupiedResources;
        private final double nominalRateBytesPerSecond;
        private final double initialRateBytesPerSecond;

        private Start(
                double bytes,
                List<String> occupiedResources,
                double nominalRateBytesPerSecond,
                double initialRateBytesPerSecond) {
            this.bytes = bytes;
            this.occupiedResources = Collections.unmodifiableList(new ArrayList<String>(occupiedResources));
            this.nominalRateBytesPerSecond = nominalRateBytesPerSecond;
            this.initialRateBytesPerSecond = initialRateBytesPerSecond;
        }

        public double getBytes() {
            return bytes;
        }

        public List<String> getOccupiedResources() {
            return occupiedResources;
        }

        public double getNominalRateBytesPerSecond() {
            return nominalRateBytesPerSecond;
        }

        public double getInitialRateBytesPerSecond() {
            return initialRateBytesPerSecond;
        }
    }

    public static final class RateChange {
        private final double previousRateBytesPerSecond;
        private final double rateBytesPerSecond;

        private RateChange(double previousRateBytesPerSecond, double rateBytesPerSecond) {
            this.previousRateBytesPerSecond = previousRateBytesPerSecond;
            this.rateBytesPerSecond = rateBytesPerSecond;
        }

        public double getPreviousRateBytesPerSecond() {
            return previousRateBytesPerSecond;
        }

        public double getRateBytesPerSecond() {
            return rateBytesPerSecond;
        }
    }

    public static final class ServiceSegment {
        private final double intervalStart;
        private final double intervalEnd;
        private final double elapsed;
        private final double rateBytesPerSecond;
        private final double remainingBefore;
        private final double remainingAfter;

        private ServiceSegment(
                double intervalStart,
                double intervalEnd,
                double elapsed,
                double rateBytesPerSecond,
                double remainingBefore,
                double remainingAfter) {
            this.intervalStart = intervalStart;
            this.intervalEnd = intervalEnd;
            this.elapsed = elapsed;
            this.rateBytesPerSecond = rateBytesPerSecond;
            this.remainingBefore = remainingBefore;
            this.remainingAfter = remainingAfter;
        }

        public double getIntervalStart() {
            return intervalStart;
        }

        public double getIntervalEnd() {
            return intervalEnd;
        }

        public double getElapsed() {
            return elapsed;
        }

        public double getRateBytesPerSecond() {
            return rateBytesPerSecond;
        }

        public double getRemainingBefore() {
            return remainingBefore;
        }

        public double getRemainingAfter() {
            return remainingAfter;
        }
    }

    public static final class Complete {
        private final double remainingAfterService;

        private Complete(double remainingAfterService) {
            this.remainingAfterService = remainingAfterService;
        }

        public double getRemainingAfterService() {
            return remainingAfterService;
        }
    }

    private final long sequence;
    private final Type type;
    private final double effectiveTime;
    private final double observedTime;
    private final Long transferId;
    private final Long admissionOrdinal;
    private final Capacity capacity;
    private final Start start;
    private final RateChange rateChange;
    private final ServiceSegment serviceSegment;
    private final Complete complete;

    private TransferTraceEvent(
            long sequence,
            Type type,
            double effectiveTime,
            double observedTime,
            Long transferId,
            Long admissionOrdinal,
            Capacity capacity,
            Start start,
            RateChange rateChange,
            ServiceSegment serviceSegment,
            Complete complete) {
        this.sequence = sequence;
        this.type = type;
        this.effectiveTime = effectiveTime;
        this.observedTime = observedTime;
        this.transferId = transferId;
        this.admissionOrdinal = admissionOrdinal;
        this.capacity = capacity;
        this.start = start;
        this.rateChange = rateChange;
        this.serviceSegment = serviceSegment;
        this.complete = complete;
    }

    static TransferTraceEvent capacity(long sequence, double engineTime, String resourceKey, double capacity) {
        return new TransferTraceEvent(
                sequence,
                Type.CAPACITY,
                engineTime,
                engineTime,
                null,
                null,
                new Capacity(resourceKey, capacity),
                null,
                null,
                null,
                null);
    }

    static TransferTraceEvent start(
            long sequence,
            double now,
            long transferId,
            long admissionOrdinal,
            double bytes,
            List<String> resources,
            double nominalRate,
            double initialRate) {
        return new TransferTraceEvent(
                sequence,
                Type.START,
                now,
                now,
                transferId,
                admissionOrdinal,
                null,
                new Start(bytes, resources, nominalRate, initialRate),
                null,
                null,
                null);
    }

    static TransferTraceEvent rateChange(
            long sequence,
            double effectiveTime,
            double observedTime,
            long transferId,
            long admissionOrdinal,
            double previousRate,
            double rate) {
        return new TransferTraceEvent(
                sequence,
                Type.RATE_CHANGE,
                effectiveTime,
                observedTime,
                transferId,
                admissionOrdinal,
                null,
                null,
                new RateChange(previousRate, rate),
                null,
                null);
    }

    static TransferTraceEvent serviceSegment(
            long sequence,
            double intervalStart,
            double intervalEnd,
            double observedTime,
            long transferId,
            long admissionOrdinal,
            double elapsed,
            double rate,
            double remainingBefore,
            double remainingAfter) {
        return new TransferTraceEvent(
                sequence,
                Type.SERVICE_SEGMENT,
                intervalEnd,
                observedTime,
                transferId,
                admissionOrdinal,
                null,
                null,
                null,
                new ServiceSegment(intervalStart, intervalEnd, elapsed, rate, remainingBefore, remainingAfter),
                null);
    }

    static TransferTraceEvent complete(
            long sequence,
            double effectiveTime,
            double observedTime,
            long transferId,
            long admissionOrdinal,
            double remainingAfterService) {
        return new TransferTraceEvent(
                sequence,
                Type.COMPLETE,
                effectiveTime,
                observedTime,
                transferId,
                admissionOrdinal,
                null,
                null,
                null,
                null,
                new Complete(remainingAfterService));
    }

    public long getSequence() {
        return sequence;
    }

    public Type getType() {
        return type;
    }

    public double getEffectiveTime() {
        return effectiveTime;
    }

    public double getObservedTime() {
        return observedTime;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getAdmissionOrdinal() {
        return admissionOrdinal;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public Start getStart() {
        return start;
    }

    public RateChange getRateChange() {
        return rateChange;
    }

    public ServiceSegment getServiceSegment() {
        return serviceSegment;
    }

    public Complete getComplete() {
        return complete;
    }
}
