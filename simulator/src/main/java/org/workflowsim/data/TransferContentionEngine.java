package org.workflowsim.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluid link-contention model with fair sharing of endpoints (R2).
 *
 * <p>Each transfer is described by its set of occupied resource keys (endpoint or
 * link, string keys), its byte count, and its contention-free nominal rate. A
 * resource with a registered capacity (the VM endpoint is {@code vm.getBw()}
 * converted to bytes/second; for Fat-tree links see
 * {@code org.workflowsim.network.FatTreeTopology}) applies progressive-filling
 * max-min fair allocation across all of its active transfers, bounded jointly by
 * the nominal rate ceiling and the capacity of every resource it occupies; an
 * unregistered resource (such as SOURCE) has no ceiling. When a flow is limited
 * by another bottleneck, its unused share continues to be allocated to other
 * flows. Integration redistributes rates at every internal completion instant, so
 * even when a caller advances across several completion instants in one step,
 * capacity reclamation is not delayed.</p>
 *
 * <p>The two-endpoint overload is the special case of the resource-set overload
 * on {@code [source, destination]}, with bit-for-bit identical behavior.
 * A repeated key within a resource set is counted once per occurrence (the path
 * key set supplied by the caller should contain no duplicates).</p>
 *
 * <p>This class does not depend on CloudSim: the caller calls
 * {@link #addTransfer} when a transfer starts, and calls {@link #advance} at
 * each clock advance point to obtain the completed transfers and the next
 * predicted completion instant. State iteration follows insertion order, and all
 * computation is deterministic.</p>
 *
 * <p>Honest boundary: this is an abstract fluid model -- no packets, no packet
 * loss, no queueing delay, no link topology, and no protocol behavior; endpoint
 * capacity is a fair-sharing approximation of the VM network interface. It
 * answers scheduling trade-off questions such as "how much do concurrent
 * transfers slow each other down", not the observable details of a real
 * network.</p>
 */
public final class TransferContentionEngine {

    /** Always-available completion clocks for the explicitly checked V2 service path, not a trace record. */
    public static final class CompletionObservation {
        private final long transferId;
        private final double effectiveTime, observedTime, remainingAfterService;

        private CompletionObservation(long id, double effective, double observed, double remaining) {
            transferId = id;
            effectiveTime = effective;
            observedTime = observed;
            remainingAfterService = remaining;
        }
        /** @return completed caller identity */
        public long getTransferId() {
            return transferId;
        }
        /** @return internal fluid settlement time */
        public double getEffectiveTime() {
            return effectiveTime;
        }
        /** @return outer advance observation */
        public double getObservedTime() {
            return observedTime;
        }
        /** @return pre-settlement numerical balance residual, not traffic */
        public double getRemainingAfterService() {
            return remainingAfterService;
        }
    }

    /** Settlement result of one {@link #advance} call. */
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
        /** @return immutable checked-V2 completion clocks; empty for original constructors */
        public List<CompletionObservation> getCompletionObservations() {
            return completionObservations;
        }

        /**
         * Transfer IDs completed in this advance: ordered by completion instant, with
         * insertion order for the same instant.
         */
        public List<Long> getCompletedTransferIds() {
            return completedTransferIds;
        }

        /**
         * Earliest predicted completion instant of the remaining active transfers
         * (absolute simulation time); {@code null} when no transfer is active.
         */
        public Double getNextCompletionTime() {
            return nextCompletionTime;
        }
    }

    /** Fluid state of one active transfer. */
    private static final class Transfer {
        private final double bytes;
        private final List<String> occupiedResources;
        private final double nominalRateBytesPerSecond;
        private double remainingBytes;
        private double rateBytesPerSecond;
        private double coherentAdmissionTime;

        // Enabled-only primitive bookkeeping; OFF allocates no per-flow trace object.
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
            // A fixed absolute byte tolerance can exceed a small flow's entire payload.
            // Keep relative round-off protection, account for representable spacing, and
            // never classify an untouched positive flow as completed (including subnormals).
            double tolerance = Math.min(bytes * 0.5, Math.max(1.0e-9 * bytes, 4.0 * Math.ulp(bytes)));
            return remainingBytes <= tolerance;
        }
    }

    /** Allocated only for a positive record budget; never calls user code. */
    private static final class TraceState {
        private final int maxEvents;
        private final List<TransferTraceEvent> events = new ArrayList<TransferTraceEvent>();
        private long droppedCount;
        private long nextAdmissionOrdinal = 1L;
        private double intervalStart;

        private TraceState(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        // Call only for true events. Reserve before DTO construction or resource copying.
        private long reserveSequence() {
            if (events.size() == maxEvents) {
                droppedCount++;
                return 0L;
            }
            return events.size() + 1L;
        }

        private void capacity(String resource, double capacity, double engineTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.capacity(sequence, engineTime, resource, capacity));
            }
        }

        private void start(long id, Transfer transfer, double now) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.start(
                        sequence,
                        now,
                        id,
                        transfer.traceAdmissionOrdinal,
                        transfer.bytes,
                        transfer.occupiedResources,
                        transfer.nominalRateBytesPerSecond,
                        transfer.rateBytesPerSecond));
            }
        }

        private void rateChange(long id, Transfer transfer, double effectiveTime, double observedTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.rateChange(
                        sequence,
                        effectiveTime,
                        observedTime,
                        id,
                        transfer.traceAdmissionOrdinal,
                        transfer.tracePreviousRate,
                        transfer.rateBytesPerSecond));
            }
        }

        private void service(long id, Transfer transfer, double effectiveTime, double observedTime, double elapsed) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.serviceSegment(
                        sequence,
                        intervalStart,
                        effectiveTime,
                        observedTime,
                        id,
                        transfer.traceAdmissionOrdinal,
                        elapsed,
                        transfer.rateBytesPerSecond,
                        transfer.traceRemainingBefore,
                        transfer.traceRemainingAfterService));
            }
        }

        private void complete(long id, Transfer transfer, double effectiveTime, double observedTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.complete(
                        sequence,
                        effectiveTime,
                        observedTime,
                        id,
                        transfer.traceAdmissionOrdinal,
                        transfer.traceRemainingAfterService));
            }
        }
    }

    /** Endpoint capacity table; an unregistered endpoint is treated as having infinite capacity. */
    private final Map<String, Double> endpointCapacitiesBytesPerSecond = new LinkedHashMap<String, Double>();
    /** Active transfer table, iterated in insertion order to guarantee determinism. */
    private final Map<Long, Transfer> activeTransfers = new LinkedHashMap<Long, Transfer>();

    private double lastAdvanceTime = 0.0;
    private final TraceState traceState;
    private final boolean coherentChecked;
    private final boolean shareResources;

    /** Construct the original, history-free engine with capture disabled. */
    public TransferContentionEngine() {
        this(0);
    }

    /**
     * Construct an engine with optional bounded internal trace capture.
     * A positive budget retains a deterministic prefix. Exactly filling it is
     * still COMPLETE; only a subsequent true event makes capture TRUNCATED.
     * Once full, true events are counted without allocating their DTOs or trace
     * resource copies. The budget bounds records, not bytes: START resource
     * lists and caller-retained snapshots can use additional memory.
     *
     * @param maxTraceEvents zero disables capture; positive values bound retained events
     * @throws IllegalArgumentException if the budget is negative
     */
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

    /**
     * Checked transactional V2 service, without optional history. Only inter-flow sharing differs
     * between modes; isolated service still enforces each route's single-flow bottlenecks.
     * @param sharing whether physical resources are shared between flows
     * @return independent checked service engine
     */
    public static TransferContentionEngine coherentV2(boolean sharing) {
        return new TransferContentionEngine(0, true, sharing);
    }

    /** @return current committed service clock without advancing it */
    public double getCurrentTime() {
        return lastAdvanceTime;
    }

    /**
     * Observe only the checked coherent V2 engine's committed current service state.
     * This passive read copies active balances, rates, paths, and declared capacities;
     * it does not advance, reallocate, fork, or touch capture state or admission ordinals.
     * No observation history is retained, and trace capture is not required.
     *
     * @return deeply immutable current state at the actual last advance time
     * @throws IllegalStateException for original unchecked constructors, even if their
     *         currently active paths happen to use only declared resources
     */
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

    /** @return deep independent service-state copy; immutable retained trace DTOs may be shared */
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
            copy.traceState.events.addAll(traceState.events);
            copy.traceState.droppedCount = traceState.droppedCount;
            copy.traceState.nextAdmissionOrdinal = traceState.nextAdmissionOrdinal;
            copy.traceState.intervalStart = traceState.intervalStart;
        }
        return copy;
    }

    private void commitChecked(TransferContentionEngine staged) {
        // Only the new history-free checked path commits staged transitions. V1 never enters here.
        activeTransfers.clear();
        activeTransfers.putAll(staged.activeTransfers);
        lastAdvanceTime = staged.lastAdvanceTime;
    }

    /**
     * Return a deeply immutable point-in-time trace view. Capture completeness
     * does not indicate that every active transfer has finished. This query
     * neither advances the clock nor emits an event.
     *
     * @return trace status, immutable event prefix, dropped count, and known engine time
     */
    public TransferTraceSnapshot getTraceSnapshot() {
        if (traceState == null) {
            return new TransferTraceSnapshot(
                    TransferTraceSnapshot.Status.DISABLED,
                    Collections.<TransferTraceEvent>emptyList(),
                    0L,
                    lastAdvanceTime);
        }
        return new TransferTraceSnapshot(
                traceState.droppedCount == 0L
                        ? TransferTraceSnapshot.Status.COMPLETE
                        : TransferTraceSnapshot.Status.TRUNCATED,
                traceState.events,
                traceState.droppedCount,
                lastAdvanceTime);
    }

    /**
     * Read a live admission's actual ordinal without advancing, copying history or emitting records.
     * Available after a successful admission even when its START was beyond the retained prefix.
     * @param transferId caller's currently active transfer ID
     * @return actual positive engine ordinal, not the caller ID
     * @throws IllegalStateException if capture is disabled or the transfer is no longer active
     */
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

    /**
     * Register the capacity of one endpoint.
     *
     * @param endpoint endpoint key (for example {@code "VM:3"})
     * @param capacityBytesPerSecond capacity in bytes/second, must be positive and finite
     */
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

    /**
     * Register a new transfer and integrate the existing active transfers forward to
     * {@code now}.
     *
     * @param transferId transfer ID guaranteed unique and never reused by the caller
     * @param bytes transfer byte count, must be positive
     * @param sourceEndpoint source endpoint key (unregistered capacity is treated as infinite)
     * @param destinationEndpoint destination endpoint key
     * @param nominalRateBytesPerSecond contention-free nominal rate in bytes/second, must be
     *        positive and finite
     * @param now current simulation time; must not be earlier than the last advance time
     * @return settlement result after registration (the new transfer is not complete at this
     *         instant unless the integration exactly empties the existing transfers)
     */
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

    /**
     * Register a finite positive, possibly fractional byte demand on two endpoints.
     *
     * @param transferId unique transfer identifier
     * @param bytes finite positive modeled bytes; no integer rounding is applied
     * @param sourceEndpoint source resource key
     * @param destinationEndpoint destination resource key
     * @param nominalRateBytesPerSecond finite positive nominal rate
     * @param now current simulation time
     * @return completed earlier transfers and the next predicted completion
     */
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

    /**
     * Register a transfer that occupies an arbitrary resource set (endpoint plus link
     * keys) and integrate the existing active transfers forward to {@code now}. The
     * Fat-tree topology-aware contention model uses this overload (resource set = both
     * endpoints plus every link key of the deterministic route, see
     * {@code FatTreeTopology#route}).
     *
     * @param transferId transfer ID guaranteed unique and never reused by the caller
     * @param bytes transfer byte count, must be positive
     * @param occupiedResources occupied resource key list (non-null, may be empty = no
     *        contention constraint; a repeated key is counted once per occurrence)
     * @param nominalRateBytesPerSecond contention-free nominal rate in bytes/second, must be
     *        positive and finite
     * @param now current simulation time; must not be earlier than the last advance time
     * @return settlement result after registration
     */
    public AdvanceResult addTransfer(
            long transferId, long bytes, List<String> occupiedResources, double nominalRateBytesPerSecond, double now) {
        return addTransfer(transferId, (double) bytes, occupiedResources, nominalRateBytesPerSecond, now);
    }

    /**
     * Register a finite positive, possibly fractional byte demand on a resource set.
     *
     * @param transferId unique transfer identifier
     * @param bytes finite positive modeled bytes
     * @param occupiedResources resource keys; an empty list has no shared capacity constraint
     * @param nominalRateBytesPerSecond finite positive nominal rate
     * @param now current simulation time
     * @return completed earlier transfers and the next predicted completion
     */
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

    /**
     * 2026-10-26 complexity extraction from addInternal(); statements verbatim; order unchanged.
     * Scope: coherent-checked admission preconditions (normal rate, known physical resources, representable finish).
     */
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

    /**
     * Integrate all active transfers forward to {@code now}, settle the completed
     * transfers, and recompute the rates of the remaining transfers.
     *
     * @param now current simulation time; must not be earlier than the last advance time
     * @return list of completed transfer IDs and the earliest predicted completion instant
     *         of the remaining transfers
     */
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

    /**
     * Integration main loop of advanceInternal() (verbatim extraction of the rate-plane
     * advancement and of the service-segment/completion event ordering).
     */
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
                // Round-off in subtraction must not leave the actual earliest flow alive.
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

    /**
     * 2026-10-26 complexity extraction from integrateUntil(); statements verbatim; order unchanged.
     * Scope: byte-conserving rate-area service pass over active transfers, including trace/coherent residuals.
     */
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

    /**
     * 2026-10-26 complexity extraction from integrateUntil(); statements verbatim; order unchanged.
     * Scope: positive-step SERVICE_SEGMENT trace publication preceding completion settlement.
     */
    private void recordServiceTrace(double elapsed, double now) {
        if (traceState != null && elapsed > 0.0) {
            // All services precede completions; retain elapsed even if the two clocks round equal.
            for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
                traceState.service(entry.getKey(), entry.getValue(), lastAdvanceTime, now, elapsed);
            }
        }
    }

    /** Settled collection stage of integrateUntil() (verbatim extraction). */
    private List<Long> collectSettled() {
        List<Long> settled = new ArrayList<Long>();
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            if (entry.getValue().completed()) {
                settled.add(entry.getKey());
            }
        }
        return settled;
    }

    /**
     * Completion settlement stage of integrateUntil() (trace completion, coherent
     * observation, removal, rate recomputation; verbatim extraction).
     */
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

    /** Current number of active transfers. */
    public int activeTransferCount() {
        return activeTransfers.size();
    }

    /**
     * Return the current effective rate of the given active transfer in bytes/second, for
     * tests and evidence records.
     *
     * @param transferId active transfer ID
     * @return current effective rate
     */
    public double currentRateBytesPerSecond(long transferId) {
        Transfer transfer = activeTransfers.get(transferId);
        if (transfer == null) {
            throw new IllegalArgumentException("Unknown active transfer: " + transferId);
        }
        return transfer.rateBytesPerSecond;
    }

    /** Enabled-only: remember final rates before the untouched solver runs. */
    private void captureCurrentRates() {
        for (Transfer transfer : activeTransfers.values()) {
            transfer.tracePreviousRate = transfer.rateBytesPerSecond;
        }
    }

    /** Called only after successful final-rate validation; START covers the new admission. */
    private void recordRateChanges(double observedTime, Transfer admitted) {
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            Transfer transfer = entry.getValue();
            if (transfer != admitted && transfer.tracePreviousRate != transfer.rateBytesPerSecond) {
                traceState.rateChange(entry.getKey(), transfer, lastAdvanceTime, observedTime);
            }
        }
    }

    /**
     * Remaining capacity and the count of not-yet-frozen flow occupancies; a repeated key
     * of the same resource is charged once per occurrence.
     */
    private static final class FillResource {
        private double remainingCapacity;
        private long growingOccupancy;
        private double incrementLimit;

        private FillResource(double capacity) {
            remainingCapacity = capacity;
        }
    }

    /** Progressive filling: reclaim capacity left over by flows already limited by another bottleneck. */
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

    /**
     * Progressive-filling max-min allocation loop (F-2026-10-23 extraction; statement order and
     * iteration order are preserved verbatim from the former inline loop body of
     * {@link #recomputeRates()}). Terminates only when every remaining transfer freezes; no
     * progress is a solver contract violation and fails fast.
     */
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

    /** Checked V2 invariants use active state, never the optional history buffer. */
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

    /**
     * 2026-10-26 complexity extraction from checkCoherentState(); statements verbatim; order unchanged.
     * Scope: per-flow balance and completion-representability invariants, returning the certified rate.
     */
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

    /**
     * 2026-10-26 complexity extraction from checkCoherentState(); statements verbatim; order unchanged.
     * Scope: per-flow resource capacity accumulation with per-flow and shared aggregate capacity checks.
     */
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

    /**
     * Earliest predicted completion instant of the remaining active transfers; returns
     * null when no transfer is active.
     */
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
