package org.workflowsim.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransferContentionEngine {

    public static final class CompletionObservation {
        private final long transferId;
        private final double effectiveTime, observedTime, remainingAfterService;

        private CompletionObservation(long id, double effective, double observed, double remaining) {
            transferId = id;
            effectiveTime = effective;
            observedTime = observed;
            remainingAfterService = remaining;
        }

        public long getTransferId() {
            return transferId;
        }

        public double getEffectiveTime() {
            return effectiveTime;
        }

        public double getObservedTime() {
            return observedTime;
        }

        public double getRemainingAfterService() {
            return remainingAfterService;
        }
    }

    public static final class AdvanceResult {
        private final List<Long> completedTransferIds;
        private final Double nextCompletionTime;
        private final List<CompletionObservation> completionObservations;

        private AdvanceResult(List<Long> completedTransferIds, Double nextCompletionTime) {
            this(completedTransferIds, nextCompletionTime, Collections.<CompletionObservation>emptyList());
        }

        private AdvanceResult(
                List<Long> completedTransferIds, Double nextCompletionTime, List<CompletionObservation> observations) {
            this.completedTransferIds = completedTransferIds;
            this.nextCompletionTime = nextCompletionTime;
            this.completionObservations = Collections.unmodifiableList(observations);
        }

        public List<CompletionObservation> getCompletionObservations() {
            return completionObservations;
        }

        public List<Long> getCompletedTransferIds() {
            return completedTransferIds;
        }

        public Double getNextCompletionTime() {
            return nextCompletionTime;
        }
    }

    private static final class Transfer {
        private final double bytes;
        private final List<String> occupiedResources;
        private final double nominalRateBytesPerSecond;
        private double remainingBytes;
        private double rateBytesPerSecond;
        private double coherentAdmissionTime;

        private long traceAdmissionOrdinal;
        private double tracePreviousRate;
        private double traceRemainingBefore;
        private double traceRemainingAfterService;

        private Transfer(double bytes, List<String> occupiedResources, double nominalRateBytesPerSecond) {
            this.bytes = bytes;
            this.occupiedResources = occupiedResources;
            this.nominalRateBytesPerSecond = nominalRateBytesPerSecond;
            this.remainingBytes = bytes;
            this.rateBytesPerSecond = nominalRateBytesPerSecond;
        }

        private boolean completed() {

            double tolerance = Math.min(bytes * 0.5, Math.max(1.0e-9 * bytes, 4.0 * Math.ulp(bytes)));
            return remainingBytes <= tolerance;
        }
    }

    private static final class TraceNode {
        final TransferTraceEvent event;
        final TraceNode previous;

        TraceNode(TransferTraceEvent event, TraceNode previous) {
            this.event = event;
            this.previous = previous;
        }
    }

    private static final class TraceState {
        private final int maxEvents;
        private TraceNode head;
        private int size;
        private long droppedCount;
        private long nextAdmissionOrdinal = 1L;
        private double intervalStart;

        private TraceState(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        private long reserveSequence() {
            if (size == maxEvents) {
                droppedCount++;
                return 0L;
            }
            return size + 1L;
        }

        private void capacity(String resource, double capacity, double engineTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                head = new TraceNode(TransferTraceEvent.capacity(sequence, engineTime, resource, capacity), head);
                size++;
            }
        }

        private void start(long id, Transfer transfer, double now) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                head = new TraceNode(
                        TransferTraceEvent.start(
                                sequence,
                                now,
                                id,
                                transfer.traceAdmissionOrdinal,
                                transfer.bytes,
                                transfer.occupiedResources,
                                transfer.nominalRateBytesPerSecond,
                                transfer.rateBytesPerSecond),
                        head);
                size++;
            }
        }

        private void rateChange(long id, Transfer transfer, double effectiveTime, double observedTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                head = new TraceNode(
                        TransferTraceEvent.rateChange(
                                sequence,
                                effectiveTime,
                                observedTime,
                                id,
                                transfer.traceAdmissionOrdinal,
                                transfer.tracePreviousRate,
                                transfer.rateBytesPerSecond),
                        head);
                size++;
            }
        }

        private void service(long id, Transfer transfer, double effectiveTime, double observedTime, double elapsed) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                head = new TraceNode(
                        TransferTraceEvent.serviceSegment(
                                sequence,
                                intervalStart,
                                effectiveTime,
                                observedTime,
                                id,
                                transfer.traceAdmissionOrdinal,
                                elapsed,
                                transfer.rateBytesPerSecond,
                                transfer.traceRemainingBefore,
                                transfer.traceRemainingAfterService),
                        head);
                size++;
            }
        }

        private void complete(long id, Transfer transfer, double effectiveTime, double observedTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                head = new TraceNode(
                        TransferTraceEvent.complete(
                                sequence,
                                effectiveTime,
                                observedTime,
                                id,
                                transfer.traceAdmissionOrdinal,
                                transfer.traceRemainingAfterService),
                        head);
                size++;
            }
        }
    }

    private final Map<String, Double> endpointCapacitiesBytesPerSecond = new LinkedHashMap<String, Double>();

    private final Map<Long, Transfer> activeTransfers = new LinkedHashMap<Long, Transfer>();

    private double lastAdvanceTime = 0.0;
    private final TraceState traceState;
    private final boolean coherentChecked;
    private final boolean shareResources;

    public TransferContentionEngine() {
        this(0);
    }

    public TransferContentionEngine(int maxTraceEvents) {
        this(maxTraceEvents, false, true);
    }

    private TransferContentionEngine(int budget, boolean checked, boolean sharing) {
        if (budget < 0) {
            throw new IllegalArgumentException("Trace event budget cannot be negative: " + budget);
        }
        traceState = budget == 0 ? null : new TraceState(budget);
        coherentChecked = checked;
        shareResources = sharing;
    }

    public static TransferContentionEngine coherentV2(boolean sharing) {
        return new TransferContentionEngine(0, true, sharing);
    }

    public double getCurrentTime() {
        return lastAdvanceTime;
    }

    public TransferServiceSnapshot snapshotState() {
        if (!coherentChecked) {
            throw new IllegalStateException("Service snapshots require a checked coherentV2 engine");
        }
        Map<Long, TransferServiceSnapshot.Flow> flows = new LinkedHashMap<Long, TransferServiceSnapshot.Flow>();
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            Transfer transfer = entry.getValue();
            flows.put(
                    entry.getKey(),
                    new TransferServiceSnapshot.Flow(
                            entry.getKey(),
                            transfer.bytes,
                            transfer.remainingBytes,
                            transfer.rateBytesPerSecond,
                            transfer.nominalRateBytesPerSecond,
                            transfer.coherentAdmissionTime,
                            transfer.occupiedResources));
        }
        return new TransferServiceSnapshot(lastAdvanceTime, shareResources, endpointCapacitiesBytesPerSecond, flows);
    }

    public TransferContentionEngine fork() {
        TransferContentionEngine copy = new TransferContentionEngine(
                traceState == null ? 0 : traceState.maxEvents, coherentChecked, shareResources);
        copy.endpointCapacitiesBytesPerSecond.putAll(endpointCapacitiesBytesPerSecond);
        copy.lastAdvanceTime = lastAdvanceTime;
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            Transfer old = entry.getValue(),
                    fresh =
                            new Transfer(
                                    old.bytes,
                                    new ArrayList<String>(old.occupiedResources),
                                    old.nominalRateBytesPerSecond);
            fresh.remainingBytes = old.remainingBytes;
            fresh.rateBytesPerSecond = old.rateBytesPerSecond;
            fresh.coherentAdmissionTime = old.coherentAdmissionTime;
            fresh.traceAdmissionOrdinal = old.traceAdmissionOrdinal;
            fresh.tracePreviousRate = old.tracePreviousRate;
            fresh.traceRemainingBefore = old.traceRemainingBefore;
            fresh.traceRemainingAfterService = old.traceRemainingAfterService;
            copy.activeTransfers.put(entry.getKey(), fresh);
        }
        if (traceState != null) {
            copy.traceState.head = traceState.head;
            copy.traceState.size = traceState.size;
            copy.traceState.droppedCount = traceState.droppedCount;
            copy.traceState.nextAdmissionOrdinal = traceState.nextAdmissionOrdinal;
            copy.traceState.intervalStart = traceState.intervalStart;
        }
        return copy;
    }

    private void commitChecked(TransferContentionEngine staged) {

        activeTransfers.clear();
        activeTransfers.putAll(staged.activeTransfers);
        lastAdvanceTime = staged.lastAdvanceTime;
    }

    public TransferTraceSnapshot getTraceSnapshot() {
        if (traceState == null) {
            return new TransferTraceSnapshot(
                    TransferTraceSnapshot.Status.DISABLED,
                    Collections.<TransferTraceEvent>emptyList(),
                    0L,
                    lastAdvanceTime);
        }
        List<TransferTraceEvent> ordered = new ArrayList<TransferTraceEvent>(traceState.size);
        for (TraceNode node = traceState.head; node != null; node = node.previous) {
            ordered.add(node.event);
        }
        Collections.reverse(ordered);
        return new TransferTraceSnapshot(
                traceState.droppedCount == 0L
                        ? TransferTraceSnapshot.Status.COMPLETE
                        : TransferTraceSnapshot.Status.TRUNCATED,
                ordered,
                traceState.droppedCount,
                lastAdvanceTime);
    }

    Object traceHeadIdentity() {
        return traceState == null ? null : traceState.head;
    }

    public long getActiveTransferAdmissionOrdinal(long transferId) {
        if (traceState == null) {
            throw new IllegalStateException("Admission ordinals require enabled transfer trace capture");
        }
        Transfer transfer = activeTransfers.get(transferId);
        if (transfer == null) {
            throw new IllegalStateException("No active transfer admission for id: " + transferId);
        }
        return transfer.traceAdmissionOrdinal;
    }

    public void setEndpointCapacity(String endpoint, double capacityBytesPerSecond) {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("Endpoint key cannot be null or empty");
        }
        if (!(capacityBytesPerSecond > 0.0) || Double.isInfinite(capacityBytesPerSecond)) {
            throw new IllegalArgumentException(
                    "Endpoint capacity must be positive and finite: " + capacityBytesPerSecond);
        }
        if (!activeTransfers.isEmpty()) {
            throw new IllegalStateException("Cannot change capacity while transfers are active");
        }
        if (coherentChecked && capacityBytesPerSecond < Double.MIN_NORMAL) {
            throw unsupported("Subnormal physical capacity");
        }
        endpointCapacitiesBytesPerSecond.put(endpoint, capacityBytesPerSecond);
        if (traceState != null) {
            traceState.capacity(endpoint, capacityBytesPerSecond, lastAdvanceTime);
        }
    }

    public AdvanceResult addTransfer(
            long transferId,
            long bytes,
            String sourceEndpoint,
            String destinationEndpoint,
            double nominalRateBytesPerSecond,
            double now) {
        return addTransfer(
                transferId, (double) bytes, sourceEndpoint, destinationEndpoint, nominalRateBytesPerSecond, now);
    }

    public AdvanceResult addTransfer(
            long transferId,
            double bytes,
            String sourceEndpoint,
            String destinationEndpoint,
            double nominalRateBytesPerSecond,
            double now) {
        if (sourceEndpoint == null || destinationEndpoint == null) {
            throw new IllegalArgumentException("Transfer endpoints cannot be null");
        }
        return addTransfer(
                transferId,
                bytes,
                java.util.Arrays.asList(sourceEndpoint, destinationEndpoint),
                nominalRateBytesPerSecond,
                now);
    }

    public AdvanceResult addTransfer(
            long transferId, long bytes, List<String> occupiedResources, double nominalRateBytesPerSecond, double now) {
        return addTransfer(transferId, (double) bytes, occupiedResources, nominalRateBytesPerSecond, now);
    }

    public AdvanceResult addTransfer(
            long transferId,
            double bytes,
            List<String> occupiedResources,
            double nominalRateBytesPerSecond,
            double now) {
        if (coherentChecked) {
            TransferContentionEngine staged = fork();
            AdvanceResult result =
                    staged.addInternal(transferId, bytes, occupiedResources, nominalRateBytesPerSecond, now);
            commitChecked(staged);
            return result;
        }
        return addInternal(transferId, bytes, occupiedResources, nominalRateBytesPerSecond, now);
    }

    private AdvanceResult addInternal(
            long transferId,
            double bytes,
            List<String> occupiedResources,
            double nominalRateBytesPerSecond,
            double now) {
        if (activeTransfers.containsKey(transferId)) {
            throw new IllegalArgumentException("Duplicate transfer id: " + transferId);
        }
        if (!(bytes > 0.0) || !Double.isFinite(bytes)) {
            throw new IllegalArgumentException("Transfer bytes must be positive and finite: " + bytes);
        }
        if (!(nominalRateBytesPerSecond > 0.0) || Double.isInfinite(nominalRateBytesPerSecond)) {
            throw new IllegalArgumentException(
                    "Nominal rate must be positive and finite: " + nominalRateBytesPerSecond);
        }
        double nominalDuration = bytes / nominalRateBytesPerSecond;
        if (!(nominalDuration > 0.0) || !Double.isFinite(nominalDuration)) {
            throw new IllegalArgumentException("Positive transfer duration is not representable: " + nominalDuration);
        }
        if (occupiedResources == null) {
            throw new IllegalArgumentException("Occupied resources cannot be null");
        }
        for (String resource : occupiedResources) {
            if (resource == null) {
                throw new IllegalArgumentException("Occupied resource keys cannot contain null");
            }
        }
        validateCoherentAdmission(occupiedResources, nominalRateBytesPerSecond, now, nominalDuration);
        AdvanceResult result = advanceInternal(now);
        activeTransfers.put(
                transferId, new Transfer(bytes, new ArrayList<String>(occupiedResources), nominalRateBytesPerSecond));
        if (coherentChecked) {
            activeTransfers.get(transferId).coherentAdmissionTime = now;
        }
        if (traceState != null) {
            activeTransfers.get(transferId).traceAdmissionOrdinal = traceState.nextAdmissionOrdinal++;
            captureCurrentRates();
        }
        recomputeRates();
        if (traceState != null) {
            Transfer admitted = activeTransfers.get(transferId);
            traceState.start(transferId, admitted, now);
            recordRateChanges(now, admitted);
        }
        return new AdvanceResult(
                result.getCompletedTransferIds(), earliestCompletion(now), result.getCompletionObservations());
    }

    private void validateCoherentAdmission(
            List<String> occupiedResources, double nominalRateBytesPerSecond, double now, double nominalDuration) {
        if (coherentChecked) {
            if (nominalRateBytesPerSecond < Double.MIN_NORMAL || occupiedResources.isEmpty()) {
                throw unsupported("Positive service requires a normal nominal rate and physical path");
            }
            for (String resource : occupiedResources) {
                if (!endpointCapacitiesBytesPerSecond.containsKey(resource)) {
                    throw unsupported("Unknown physical resource: " + resource);
                }
            }
            double finish = now + nominalDuration;
            if (!Double.isFinite(finish) || finish <= now) {
                throw unsupported("Unrepresentable absolute isolated finish");
            }
        }
    }

    public AdvanceResult advance(double now) {
        if (coherentChecked) {
            TransferContentionEngine staged = fork();
            AdvanceResult result = staged.advanceInternal(now);
            commitChecked(staged);
            return result;
        }
        return advanceInternal(now);
    }

    private AdvanceResult advanceInternal(double now) {
        if (!Double.isFinite(now) || now < lastAdvanceTime) {
            throw new IllegalArgumentException(
                    "Time must be finite and not move backwards: " + now + " < " + lastAdvanceTime);
        }
        List<Long> completed = new ArrayList<Long>();
        List<CompletionObservation> observations = coherentChecked
                ? new ArrayList<CompletionObservation>()
                : Collections.<CompletionObservation>emptyList();
        if (coherentChecked) {
            checkCoherentState();
        }
        integrateUntil(now, completed, observations);
        lastAdvanceTime = now;
        if (coherentChecked) {
            checkCoherentState();
        }
        return new AdvanceResult(completed, earliestCompletion(now), observations);
    }

    private void integrateUntil(double now, List<Long> completed, List<CompletionObservation> observations) {
        while (!activeTransfers.isEmpty() && lastAdvanceTime < now) {
            double nextDuration = Double.POSITIVE_INFINITY;
            Transfer earliest = null;
            for (Transfer transfer : activeTransfers.values()) {
                double duration = transfer.remainingBytes / transfer.rateBytesPerSecond;
                if (duration < nextDuration) {
                    nextDuration = duration;
                    earliest = transfer;
                }
            }
            double elapsed = Math.min(now - lastAdvanceTime, nextDuration);
            if (coherentChecked
                    && (!(elapsed > 0) || !Double.isFinite(elapsed) || lastAdvanceTime + elapsed <= lastAdvanceTime)) {
                throw unsupported("Service step cannot advance the finite binary64 clock");
            }
            if (traceState != null) {
                traceState.intervalStart = lastAdvanceTime;
            }
            serviceTransfersByElapsed(elapsed);
            if (earliest != null && elapsed >= nextDuration) {

                earliest.remainingBytes = 0.0;
            }
            lastAdvanceTime = Math.min(now, lastAdvanceTime + elapsed);
            recordServiceTrace(elapsed, now);
            List<Long> settled = collectSettled();
            settleTransfers(settled, observations, now, completed);
            if (elapsed == 0.0 && settled.isEmpty()) {
                throw new IllegalStateException("Transfer integration made no progress");
            }
        }
    }

    private void serviceTransfersByElapsed(double elapsed) {
        for (Transfer transfer : activeTransfers.values()) {
            if (traceState != null) {
                transfer.traceRemainingBefore = transfer.remainingBytes;
            }
            if (coherentChecked && !Double.isFinite(transfer.rateBytesPerSecond * elapsed)) {
                throw unsupported("Service rate-area multiplication overflow");
            }
            transfer.remainingBytes = Math.max(0.0, transfer.remainingBytes - transfer.rateBytesPerSecond * elapsed);
            if (traceState != null || coherentChecked) {
                transfer.traceRemainingAfterService = transfer.remainingBytes;
            }
        }
    }

    private void recordServiceTrace(double elapsed, double now) {
        if (traceState != null && elapsed > 0.0) {

            for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
                traceState.service(entry.getKey(), entry.getValue(), lastAdvanceTime, now, elapsed);
            }
        }
    }

    private List<Long> collectSettled() {
        List<Long> settled = new ArrayList<Long>();
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            if (entry.getValue().completed()) {
                settled.add(entry.getKey());
            }
        }
        return settled;
    }

    private void settleTransfers(
            List<Long> settled, List<CompletionObservation> observations, double now, List<Long> completed) {
        if (traceState != null) {
            for (Long id : settled) {
                traceState.complete(id, activeTransfers.get(id), lastAdvanceTime, now);
            }
        }
        if (coherentChecked) {
            for (Long id : settled) {
                Transfer transfer = activeTransfers.get(id);
                checkCoherentCompletion(transfer, lastAdvanceTime);
                observations.add(
                        new CompletionObservation(id, lastAdvanceTime, now, transfer.traceRemainingAfterService));
            }
        }
        for (Long id : settled) {
            activeTransfers.remove(id);
        }
        completed.addAll(settled);
        if (!settled.isEmpty()) {
            if (traceState != null) {
                captureCurrentRates();
            }
            recomputeRates();
            if (traceState != null) {
                recordRateChanges(now, null);
            }
        }
    }

    public int activeTransferCount() {
        return activeTransfers.size();
    }

    public double currentRateBytesPerSecond(long transferId) {
        Transfer transfer = activeTransfers.get(transferId);
        if (transfer == null) {
            throw new IllegalArgumentException("Unknown active transfer: " + transferId);
        }
        return transfer.rateBytesPerSecond;
    }

    private void captureCurrentRates() {
        for (Transfer transfer : activeTransfers.values()) {
            transfer.tracePreviousRate = transfer.rateBytesPerSecond;
        }
    }

    private void recordRateChanges(double observedTime, Transfer admitted) {
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            Transfer transfer = entry.getValue();
            if (transfer != admitted && transfer.tracePreviousRate != transfer.rateBytesPerSecond) {
                traceState.rateChange(entry.getKey(), transfer, lastAdvanceTime, observedTime);
            }
        }
    }

    private static final class FillResource {
        private double remainingCapacity;
        private long growingOccupancy;
        private double incrementLimit;

        private FillResource(double capacity) {
            remainingCapacity = capacity;
        }
    }

    private void recomputeRates() {
        if (coherentChecked && !shareResources) {
            for (Transfer transfer : activeTransfers.values()) {
                double rate = transfer.nominalRateBytesPerSecond;
                Map<String, Integer> weights = new LinkedHashMap<String, Integer>();
                for (String key : transfer.occupiedResources) {
                    Integer count = weights.get(key);
                    weights.put(key, count == null ? 1 : count + 1);
                }
                for (Map.Entry<String, Integer> entry : weights.entrySet()) {
                    rate = Math.min(rate, endpointCapacitiesBytesPerSecond.get(entry.getKey()) / entry.getValue());
                }
                transfer.rateBytesPerSecond = rate;
            }
            checkCoherentState();
            return;
        }
        List<Transfer> growing = new ArrayList<Transfer>(activeTransfers.values());
        Map<String, FillResource> resources = new LinkedHashMap<String, FillResource>();
        for (Transfer transfer : growing) {
            transfer.rateBytesPerSecond = 0.0;
            for (String key : transfer.occupiedResources) {
                Double capacity = endpointCapacitiesBytesPerSecond.get(key);
                if (capacity != null && !resources.containsKey(key)) {
                    resources.put(key, new FillResource(capacity));
                }
            }
        }
        fillProgressively(growing, resources);
        for (Transfer transfer : activeTransfers.values()) {
            if (!(transfer.rateBytesPerSecond > 0.0) || !Double.isFinite(transfer.rateBytesPerSecond)) {
                throw new IllegalStateException("Effective transfer rate must remain positive and finite");
            }
        }
        if (coherentChecked) {
            checkCoherentState();
        }
    }

    private void fillProgressively(List<Transfer> growing, Map<String, FillResource> resources) {
        while (!growing.isEmpty()) {
            for (FillResource resource : resources.values()) {
                resource.growingOccupancy = 0L;
            }
            double delta = Double.POSITIVE_INFINITY;
            for (Transfer transfer : growing) {
                delta = Math.min(delta, transfer.nominalRateBytesPerSecond - transfer.rateBytesPerSecond);
                for (String key : transfer.occupiedResources) {
                    FillResource resource = resources.get(key);
                    if (resource != null) {
                        resource.growingOccupancy++;
                    }
                }
            }
            for (FillResource resource : resources.values()) {
                resource.incrementLimit = resource.growingOccupancy == 0L
                        ? Double.POSITIVE_INFINITY
                        : resource.remainingCapacity / resource.growingOccupancy;
                delta = Math.min(delta, resource.incrementLimit);
            }
            List<Transfer> survivors = new ArrayList<Transfer>();
            for (Transfer transfer : growing) {
                boolean frozen = transfer.nominalRateBytesPerSecond - transfer.rateBytesPerSecond <= delta;
                for (String key : transfer.occupiedResources) {
                    FillResource resource = resources.get(key);
                    if (resource != null && resource.incrementLimit <= delta) {
                        frozen = true;
                    }
                }
                transfer.rateBytesPerSecond =
                        Math.min(transfer.nominalRateBytesPerSecond, transfer.rateBytesPerSecond + delta);
                if (!frozen) {
                    survivors.add(transfer);
                }
            }
            for (FillResource resource : resources.values()) {
                resource.remainingCapacity =
                        Math.max(0.0, resource.remainingCapacity - delta * resource.growingOccupancy);
            }
            if (survivors.size() == growing.size()) {
                throw new IllegalStateException("Max-min solver made no progress");
            }
            growing = survivors;
        }
    }

    private void checkCoherentState() {
        Map<String, BigDecimal> totals = new LinkedHashMap<String, BigDecimal>();
        Map<String, Long> terms = new LinkedHashMap<String, Long>();
        for (Transfer transfer : activeTransfers.values()) {
            double rate = checkCoherentFlowBalance(transfer);
            accumulateCoherentFlowResources(transfer, rate, totals, terms);
        }
        if (shareResources) {
            for (String key : totals.keySet()) {
                checkCapacity(totals.get(key), endpointCapacitiesBytesPerSecond.get(key), terms.get(key));
            }
        }
    }

    private double checkCoherentFlowBalance(Transfer transfer) {
        double rate = transfer.rateBytesPerSecond, remaining = transfer.remainingBytes;
        if (!Double.isFinite(rate)
                || rate < Double.MIN_NORMAL
                || rate > transfer.nominalRateBytesPerSecond
                || !Double.isFinite(remaining)
                || remaining <= 0
                || remaining > transfer.bytes) {
            throw unsupported("Invalid active flow balance or normal allocation");
        }
        double duration = remaining / rate, finish = lastAdvanceTime + duration;
        if (!Double.isFinite(duration) || duration <= 0 || !Double.isFinite(finish) || finish <= lastAdvanceTime) {
            throw unsupported("Active completion prediction is not representable");
        }
        return rate;
    }

    private void accumulateCoherentFlowResources(
            Transfer transfer, double rate, Map<String, BigDecimal> totals, Map<String, Long> terms) {
        Map<String, BigDecimal> single = new LinkedHashMap<String, BigDecimal>();
        Map<String, Long> singleTerms = new LinkedHashMap<String, Long>();
        for (String key : transfer.occupiedResources) {
            Double cap = endpointCapacitiesBytesPerSecond.get(key);
            if (cap == null || !Double.isFinite(cap) || cap < Double.MIN_NORMAL) {
                throw unsupported("Unregistered or unsupported physical capacity");
            }
            addRate(single, singleTerms, key, rate);
            if (shareResources) {
                addRate(totals, terms, key, rate);
            }
        }
        for (String key : single.keySet()) {
            checkCapacity(single.get(key), endpointCapacitiesBytesPerSecond.get(key), singleTerms.get(key));
        }
    }

    private static void checkCoherentCompletion(Transfer transfer, double effective) {
        double residual = transfer.traceRemainingAfterService,
                tolerance =
                        Math.min(transfer.bytes * .5, Math.max(transfer.bytes * 1e-9, 4 * Math.ulp(transfer.bytes)));
        if (!Double.isFinite(residual)
                || residual < 0
                || residual > tolerance
                || effective <= transfer.coherentAdmissionTime) {
            throw unsupported("Invalid numerical completion settlement");
        }
        double lower =
                transfer.coherentAdmissionTime + (transfer.bytes - residual) / transfer.nominalRateBytesPerSecond;
        double allowed = Math.min(
                8 * Math.max(Math.ulp(transfer.coherentAdmissionTime), Math.max(Math.ulp(effective), Math.ulp(lower))),
                1e-12 * Math.max(transfer.coherentAdmissionTime, Math.max(effective, lower)));
        if (!Double.isFinite(lower) || (lower > effective && lower - effective > allowed)) {
            throw unsupported("Completion violates isolated-rate scalar lower bound");
        }
    }

    private static void addRate(Map<String, BigDecimal> totals, Map<String, Long> terms, String key, double rate) {
        BigDecimal previous = totals.get(key);
        totals.put(key, (previous == null ? BigDecimal.ZERO : previous).add(new BigDecimal(rate)));
        Long count = terms.get(key);
        terms.put(key, count == null ? 1 : Math.addExact(count, 1));
    }

    private static void checkCapacity(BigDecimal sum, double capacity, long count) {
        BigDecimal cap = new BigDecimal(capacity);
        BigDecimal tolerance = new BigDecimal(Math.ulp(capacity))
                .multiply(BigDecimal.valueOf(8))
                .multiply(BigDecimal.valueOf(count + 1))
                .min(cap.multiply(new BigDecimal("1e-12")));
        if (sum.compareTo(cap.add(tolerance)) > 0) {
            throw unsupported("Allocation exceeds the declared scalar capacity profile");
        }
    }

    private static IllegalArgumentException unsupported(String message) {
        return new IllegalArgumentException("Unsupported coherent V2 service state: " + message);
    }

    private Double earliestCompletion(double now) {
        Double earliest = null;
        for (Transfer transfer : activeTransfers.values()) {
            double predicted = now + transfer.remainingBytes / transfer.rateBytesPerSecond;
            if (earliest == null || predicted < earliest) {
                earliest = predicted;
            }
        }
        return earliest;
    }
}
