package org.workflowsim.data;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NetworkTraceMetrics {

    public static final String ACCOUNTING_VERSION = "EXACT_BINARY64_INPUT_DECIMAL_V1";

    private final String accountingVersion = ACCOUNTING_VERSION;
    private final TransferTraceValidator.Result validation;
    private final long droppedRecordCount;
    private final double engineTime;
    private final BigDecimal admittedPayloadBytes, completedDemandBytes, servicedBalanceDeltaBytes;
    private final BigDecimal modeledRateAreaBytes,
            completionResidualBytes,
            remainingLedgerBytes,
            rateAreaMinusBalanceDeltaBytes;
    private final Integer integrationEpochCount, completedFctSampleCount;
    private final Double meanEffectiveFctSeconds,
            p95EffectiveFctSeconds,
            maxEffectiveFctSeconds,
            meanNotificationLagSeconds;
    private final List<FlowSummary> flows;
    private final List<ResourceUsage> resources;

    public static final class FlowSummary {
        private final long externalTransferId, admissionOrdinal;
        private final boolean complete;
        private final double admissionTime;
        private final Double completionEffectiveTime,
                completionObservedTime,
                effectiveFctSeconds,
                observedFctSeconds,
                notificationLagSeconds;
        private final BigDecimal demandBytes,
                servicedBalanceDeltaBytes,
                modeledRateAreaBytes,
                completionResidualBytes,
                remainingBytes;

        private FlowSummary(FlowAcc a) {
            externalTransferId = a.id;
            admissionOrdinal = a.ordinal;
            complete = a.complete;
            admissionTime = a.start;
            completionEffectiveTime = a.end;
            completionObservedTime = a.observedEnd;
            effectiveFctSeconds = a.complete ? a.end - a.start : null;
            observedFctSeconds = a.complete ? a.observedEnd - a.start : null;
            notificationLagSeconds = a.complete ? a.observedEnd - a.end : null;
            demandBytes = a.demand;
            servicedBalanceDeltaBytes = a.debit;
            modeledRateAreaBytes = a.area;
            completionResidualBytes = a.residual;
            remainingBytes = a.remaining;
        }

        public long getExternalTransferId() {
            return externalTransferId;
        }

        public long getAdmissionOrdinal() {
            return admissionOrdinal;
        }

        public boolean isComplete() {
            return complete;
        }

        public double getAdmissionTime() {
            return admissionTime;
        }

        public Double getCompletionEffectiveTime() {
            return completionEffectiveTime;
        }

        public Double getCompletionObservedTime() {
            return completionObservedTime;
        }

        public Double getEffectiveFctSeconds() {
            return effectiveFctSeconds;
        }

        public Double getObservedFctSeconds() {
            return observedFctSeconds;
        }

        public Double getNotificationLagSeconds() {
            return notificationLagSeconds;
        }

        public BigDecimal getDemandBytes() {
            return demandBytes;
        }

        public BigDecimal getServicedBalanceDeltaBytes() {
            return servicedBalanceDeltaBytes;
        }

        public BigDecimal getModeledRateAreaBytes() {
            return modeledRateAreaBytes;
        }

        public BigDecimal getCompletionResidualBytes() {
            return completionResidualBytes;
        }

        public BigDecimal getRemainingBytes() {
            return remainingBytes;
        }
    }

    public static final class ResourceUsage {
        private final String resourceKey;
        private final Double lastDeclaredCapacityBytesPerSecond;
        private final BigDecimal rateAreaBytes, boundedRateAreaBytes, capacityAreaBytes;
        private final Double integrationEpochUtilization;

        private ResourceUsage(ResourceAcc a) {
            resourceKey = a.key;
            lastDeclaredCapacityBytesPerSecond = a.capacity;
            rateAreaBytes = a.area;
            boundedRateAreaBytes = a.boundedArea;
            capacityAreaBytes = a.capacityArea;
            integrationEpochUtilization = a.capacityArea.signum() == 0
                    ? null
                    : a.boundedArea
                            .divide(a.capacityArea, MathContext.DECIMAL128)
                            .doubleValue();
        }

        public String getResourceKey() {
            return resourceKey;
        }

        public Double getLastDeclaredCapacityBytesPerSecond() {
            return lastDeclaredCapacityBytesPerSecond;
        }

        public BigDecimal getRateAreaBytes() {
            return rateAreaBytes;
        }

        public BigDecimal getBoundedRateAreaBytes() {
            return boundedRateAreaBytes;
        }

        public BigDecimal getCapacityAreaBytes() {
            return capacityAreaBytes;
        }

        public Double getIntegrationEpochUtilization() {
            return integrationEpochUtilization;
        }
    }

    private NetworkTraceMetrics(
            TransferTraceSnapshot snapshot, TransferTraceValidator.Result validated, Accumulator acc) {
        validation = validated;
        droppedRecordCount = snapshot.getDroppedCount();
        engineTime = snapshot.getEngineTime();
        if (acc == null) {
            admittedPayloadBytes = null;
            completedDemandBytes = null;
            servicedBalanceDeltaBytes = null;
            modeledRateAreaBytes = null;
            completionResidualBytes = null;
            remainingLedgerBytes = null;
            rateAreaMinusBalanceDeltaBytes = null;
            integrationEpochCount = null;
            completedFctSampleCount = null;
            meanEffectiveFctSeconds = null;
            p95EffectiveFctSeconds = null;
            maxEffectiveFctSeconds = null;
            meanNotificationLagSeconds = null;
            flows = Collections.emptyList();
            resources = Collections.emptyList();
            return;
        }
        BigDecimal demand = BigDecimal.ZERO,
                finished = BigDecimal.ZERO,
                debit = BigDecimal.ZERO,
                area = BigDecimal.ZERO;
        BigDecimal residual = BigDecimal.ZERO,
                remaining = BigDecimal.ZERO,
                fctSum = BigDecimal.ZERO,
                lagSum = BigDecimal.ZERO;
        List<FlowSummary> flowRows = new ArrayList<FlowSummary>();
        List<Double> fcts = new ArrayList<Double>();
        for (FlowAcc a : acc.all.values()) {
            FlowSummary row = new FlowSummary(a);
            flowRows.add(row);
            demand = demand.add(a.demand);
            debit = debit.add(a.debit);
            area = area.add(a.area);
            residual = residual.add(a.residual);
            remaining = remaining.add(a.remaining);
            if (a.demand.compareTo(a.debit.add(a.residual).add(a.remaining)) != 0) {
                throw new IllegalStateException("Validated flow balance did not conserve demand: " + a.ordinal);
            }
            if (a.complete) {
                finished = finished.add(a.demand);
                fcts.add(row.effectiveFctSeconds);
                fctSum = fctSum.add(exact(row.effectiveFctSeconds));
                lagSum = lagSum.add(exact(row.notificationLagSeconds));
            }
        }
        admittedPayloadBytes = demand;
        completedDemandBytes = finished;
        servicedBalanceDeltaBytes = debit;
        modeledRateAreaBytes = area;
        completionResidualBytes = residual;
        remainingLedgerBytes = remaining;
        rateAreaMinusBalanceDeltaBytes = area.subtract(debit);
        integrationEpochCount = acc.epochs;
        completedFctSampleCount = fcts.size();
        Collections.sort(fcts);
        meanEffectiveFctSeconds = mean(fctSum, fcts.size());
        meanNotificationLagSeconds = mean(lagSum, fcts.size());
        maxEffectiveFctSeconds = fcts.isEmpty() ? null : fcts.get(fcts.size() - 1);
        p95EffectiveFctSeconds = fcts.isEmpty() ? null : fcts.get((int) ((95L * fcts.size() + 99L) / 100L) - 1);
        flows = Collections.unmodifiableList(flowRows);
        List<ResourceUsage> resourceRows = new ArrayList<ResourceUsage>();
        for (ResourceAcc a : acc.resources.values()) {
            resourceRows.add(new ResourceUsage(a));
        }
        resources = Collections.unmodifiableList(resourceRows);
    }

    public static NetworkTraceMetrics calculate(TransferTraceSnapshot snapshot, int maxTraceRecords) {
        TransferTraceValidator.Result validated = TransferTraceValidator.validate(snapshot, maxTraceRecords);
        if (!validated.isCompleteCapture()) {
            return new NetworkTraceMetrics(snapshot, validated, null);
        }
        Accumulator acc = new Accumulator();
        for (TransferTraceEvent event : snapshot.getEvents()) {
            acc.accept(event);
        }
        return new NetworkTraceMetrics(snapshot, validated, acc);
    }

    private static final class FlowAcc {
        final long id, ordinal;
        final double start;
        final BigDecimal demand;
        final List<String> resources;
        BigDecimal debit = BigDecimal.ZERO, area = BigDecimal.ZERO, residual = BigDecimal.ZERO, remaining;
        boolean complete;
        Double end, observedEnd;

        FlowAcc(TransferTraceEvent e) {
            id = e.getTransferId();
            ordinal = e.getAdmissionOrdinal();
            start = e.getEffectiveTime();
            demand = exact(e.getStart().getBytes());
            remaining = demand;
            resources = e.getStart().getOccupiedResources();
        }
    }

    private static final class ResourceAcc {
        final String key;
        Double capacity;
        BigDecimal area = BigDecimal.ZERO, boundedArea = BigDecimal.ZERO, capacityArea = BigDecimal.ZERO;

        ResourceAcc(String key) {
            this.key = key;
        }
    }

    private static final class Accumulator {
        final Map<Long, FlowAcc> all = new LinkedHashMap<Long, FlowAcc>(), active = new LinkedHashMap<Long, FlowAcc>();
        final Map<String, ResourceAcc> resources = new LinkedHashMap<String, ResourceAcc>();
        int serviceRowsRemaining, epochs;

        ResourceAcc resource(String key) {
            ResourceAcc r = resources.get(key);
            if (r == null) {
                r = new ResourceAcc(key);
                resources.put(key, r);
            }
            return r;
        }

        void accept(TransferTraceEvent e) {
            switch (e.getType()) {
                case CAPACITY:
                    resource(e.getCapacity().getResourceKey()).capacity =
                            e.getCapacity().getCapacityBytesPerSecond();
                    break;
                case START:
                    FlowAcc added = new FlowAcc(e);
                    all.put(added.ordinal, added);
                    active.put(added.ordinal, added);
                    for (String key : added.resources) {
                        resource(key);
                    }
                    break;
                case RATE_CHANGE:
                    break;
                case SERVICE_SEGMENT:
                    TransferTraceEvent.ServiceSegment s = e.getServiceSegment();
                    BigDecimal elapsed = exact(s.getElapsed());
                    if (serviceRowsRemaining == 0) {
                        serviceRowsRemaining = active.size();
                        epochs++;
                        for (ResourceAcc r : resources.values()) {
                            if (r.capacity != null) {
                                r.capacityArea =
                                        r.capacityArea.add(exact(r.capacity).multiply(elapsed));
                            }
                        }
                    }
                    FlowAcc flow = active.get(e.getAdmissionOrdinal());
                    BigDecimal service = exact(s.getRateBytesPerSecond()).multiply(elapsed);
                    flow.debit = flow.debit.add(exact(s.getRemainingBefore()).subtract(exact(s.getRemainingAfter())));
                    flow.area = flow.area.add(service);
                    flow.remaining = exact(s.getRemainingAfter());
                    for (String key : flow.resources) {
                        ResourceAcc r = resource(key);
                        r.area = r.area.add(service);
                        if (r.capacity != null) {
                            r.boundedArea = r.boundedArea.add(service);
                        }
                    }
                    serviceRowsRemaining--;
                    break;
                case COMPLETE:
                    FlowAcc done = active.remove(e.getAdmissionOrdinal());
                    done.complete = true;
                    done.residual = done.residual.add(exact(e.getComplete().getRemainingAfterService()));
                    done.remaining = BigDecimal.ZERO;
                    done.end = e.getEffectiveTime();
                    done.observedEnd = e.getObservedTime();
                    break;
                default:
                    throw new IllegalStateException("Unknown validated event type");
            }
        }
    }

    private static BigDecimal exact(double value) {
        return new BigDecimal(value);
    }

    private static Double mean(BigDecimal sum, int count) {
        return count == 0
                ? null
                : sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128).doubleValue();
    }

    public String getAccountingVersion() {
        return accountingVersion;
    }

    public TransferTraceValidator.Result getValidation() {
        return validation;
    }

    public boolean isAvailable() {
        return validation.isCompleteCapture();
    }

    public long getDroppedRecordCount() {
        return droppedRecordCount;
    }

    public double getEngineTime() {
        return engineTime;
    }

    public BigDecimal getAdmittedPayloadBytes() {
        return admittedPayloadBytes;
    }

    public BigDecimal getCompletedDemandBytes() {
        return completedDemandBytes;
    }

    public BigDecimal getServicedBalanceDeltaBytes() {
        return servicedBalanceDeltaBytes;
    }

    public BigDecimal getModeledRateAreaBytes() {
        return modeledRateAreaBytes;
    }

    public BigDecimal getCompletionResidualBytes() {
        return completionResidualBytes;
    }

    public BigDecimal getRemainingLedgerBytes() {
        return remainingLedgerBytes;
    }

    public BigDecimal getRateAreaMinusBalanceDeltaBytes() {
        return rateAreaMinusBalanceDeltaBytes;
    }

    public Integer getIntegrationEpochCount() {
        return integrationEpochCount;
    }

    public Integer getCompletedFctSampleCount() {
        return completedFctSampleCount;
    }

    public Double getMeanEffectiveFctSeconds() {
        return meanEffectiveFctSeconds;
    }

    public Double getP95EffectiveFctSeconds() {
        return p95EffectiveFctSeconds;
    }

    public Double getMaxEffectiveFctSeconds() {
        return maxEffectiveFctSeconds;
    }

    public Double getMeanNotificationLagSeconds() {
        return meanNotificationLagSeconds;
    }

    public List<FlowSummary> getFlows() {
        return flows;
    }

    public List<ResourceUsage> getResources() {
        return resources;
    }
}
