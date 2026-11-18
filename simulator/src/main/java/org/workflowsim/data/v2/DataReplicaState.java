package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class DataReplicaState {

    public static final String VISIBILITY_POLICY = "PER_FILE_SETTLEMENT_OBSERVATION_V2";

    public enum Acquisition {
        EXTERNAL_SEED,
        TASK_OUTPUT,
        COPY_SETTLEMENT,
        ZERO_BYTE_REFERENCE,
        ZERO_BYTE_OUTPUT
    }

    public static final class Origin {
        private final Integer producerTaskId;
        private final Long jobAttemptId;
        private final DataLocation location;
        private final double observedAt;

        private Origin(Integer task, Long attempt, DataLocation location, double observedAt) {
            producerTaskId = task;
            jobAttemptId = attempt;
            this.location = location;
            this.observedAt = observedAt;
        }

        public boolean isExternal() {
            return producerTaskId == null;
        }

        public Integer getProducerTaskId() {
            return producerTaskId;
        }

        public Long getJobAttemptId() {
            return jobAttemptId;
        }

        public DataLocation getLocation() {
            return location;
        }

        public double getObservedAt() {
            return observedAt;
        }
    }

    public static final class Replica {
        private final DataflowFilePlan.FileDefinition file;
        private final DataLocation location, copiedFrom;
        private final double visibleAt;
        private final Acquisition acquisition;
        private final Origin origin;
        private final Long copyOrdinal;

        private Replica(
                DataflowFilePlan.FileDefinition file,
                DataLocation location,
                double at,
                Acquisition acquisition,
                Origin origin,
                DataLocation copiedFrom,
                Long ordinal) {
            this.file = file;
            this.location = location;
            visibleAt = at;
            this.acquisition = acquisition;
            this.origin = origin;
            this.copiedFrom = copiedFrom;
            copyOrdinal = ordinal;
        }

        public DataflowFilePlan.FileDefinition getFile() {
            return file;
        }

        public DataLocation getLocation() {
            return location;
        }

        public double getVisibleAt() {
            return visibleAt;
        }

        public Acquisition getAcquisition() {
            return acquisition;
        }

        public Origin getOrigin() {
            return origin;
        }

        public DataLocation getCopiedFrom() {
            return copiedFrom;
        }

        public Long getCopyOrdinal() {
            return copyOrdinal;
        }
    }

    public static final class Publication {
        private final Replica replica;
        private final boolean fresh;

        private Publication(Replica replica, boolean fresh) {
            this.replica = replica;
            this.fresh = fresh;
        }

        public Replica getReplica() {
            return replica;
        }

        public boolean isNewReplica() {
            return fresh;
        }
    }

    public static final class CopyTicket {
        private final Object owner;
        private final long ordinal;
        private final Replica source;
        private final DataLocation destination;
        private final double releaseTime;

        private CopyTicket(Object owner, long ordinal, Replica source, DataLocation destination, double release) {
            this.owner = owner;
            this.ordinal = ordinal;
            this.source = source;
            this.destination = destination;
            releaseTime = release;
        }

        public long getOrdinal() {
            return ordinal;
        }

        public Replica getSourceReplica() {
            return source;
        }

        public DataLocation getDestination() {
            return destination;
        }

        public DataflowFilePlan.FileDefinition getFile() {
            return source.file;
        }

        public double getReleaseTime() {
            return releaseTime;
        }
    }

    public static final class Snapshot {
        private final DataflowFilePlan plan;
        private final Set<DataLocation> known;
        private final List<DataLocation> locations;
        private final PersistentHistoryMap<DataflowFilePlan.FileId, Map<DataLocation, Replica>> replicas;
        private final List<CopyTicket> active;
        private final double observedThrough;

        private Snapshot(DataReplicaState state) {
            plan = state.plan;
            known = state.locations;
            locations = state.locationList;
            observedThrough = state.observedThrough;
            replicas = state.replicas;
            active = immutableList(state.active.values());
        }

        public double getObservedThrough() {
            return observedThrough;
        }

        public List<DataLocation> getLocations() {
            return locations;
        }

        public List<CopyTicket> getActiveCopies() {
            return active;
        }

        public DataflowFilePlan.FileDefinition getFile(DataflowFilePlan.FileId file) {
            return plan.getFile(file);
        }

        public List<Replica> getReplicas(DataflowFilePlan.FileId file) {
            plan.getFile(file);
            Map<DataLocation, Replica> copies = replicas.get(file);
            return copies == null ? Collections.<Replica>emptyList() : immutableList(copies.values());
        }

        public Replica getReplica(DataflowFilePlan.FileId file, DataLocation location) {
            plan.getFile(file);
            requireKnown(known, location);
            Map<DataLocation, Replica> copies = replicas.get(file);
            return copies == null ? null : copies.get(location);
        }
    }

    private final DataflowFilePlan plan;
    private final Set<DataLocation> locations;
    private final List<DataLocation> locationList;
    private final Object ticketOwner;

    private PersistentHistoryMap<DataflowFilePlan.FileId, Map<DataLocation, Replica>> replicas =
            PersistentHistoryMap.empty();
    private final Map<Long, CopyTicket> active = new TreeMap<>();
    private final Map<Target, CopyTicket> byTarget = new HashMap<>();
    private PersistentHistoryMap<Attempt, Completion> completions = PersistentHistoryMap.empty();
    private PersistentHistoryMap<Long, JobObservation> jobObservations = PersistentHistoryMap.empty();
    private long nextOrdinal = 1;
    private double observedThrough;

    public DataReplicaState(DataflowFilePlan plan, Collection<DataLocation> knownLocations) {
        if (plan == null || knownLocations == null) {
            throw bad("File plan and known locations are required");
        }
        this.plan = plan;
        ticketOwner = new Object();
        Set<DataLocation> copied = new TreeSet<>();
        for (DataLocation location : knownLocations) {
            if (location == null || !copied.add(location)) {
                throw bad("Locations must be nonnull and distinct");
            }
        }
        locations = Collections.unmodifiableSet(copied);
        locationList = immutableList(locations);
    }

    private DataReplicaState(DataReplicaState previous) {
        plan = previous.plan;
        locations = previous.locations;
        locationList = previous.locationList;
        ticketOwner = previous.ticketOwner;
        nextOrdinal = previous.nextOrdinal;
        observedThrough = previous.observedThrough;
        replicas = previous.replicas;
        completions = previous.completions;
        jobObservations = previous.jobObservations;
        active.putAll(previous.active);
        byTarget.putAll(previous.byTarget);
    }

    DataReplicaState fork() {
        return new DataReplicaState(this);
    }

    public double getObservedThrough() {
        return observedThrough;
    }

    public long getAdmittedCopyCount() {
        return nextOrdinal - 1;
    }

    public long getCompletedTaskAttemptCount() {
        return completions.size();
    }

    public int getActiveCopyCount() {
        return active.size();
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public void advanceTo(double now) {
        observedThrough = time(now);
    }

    public Replica getReplica(DataflowFilePlan.FileId file, DataLocation location) {
        plan.getFile(file);
        requireKnown(locations, location);
        return visible(file, location);
    }

    public CopyTicket getInFlight(DataflowFilePlan.FileId file, DataLocation destination) {
        plan.getFile(file);
        requireKnown(locations, destination);
        return byTarget.get(new Target(file, destination));
    }

    public Publication seedExternal(DataflowFilePlan.FileId file, DataLocation source, double now) {
        DataflowFilePlan.FileDefinition definition = plan.getFile(file);
        requireKnown(locations, source);
        if (!definition.isExternal() || source.getKind() != DataLocation.Kind.SOURCE) {
            throw bad("Only external logical files can be seeded at SOURCE locations");
        }
        double at = time(now);
        Replica proposal = new Replica(
                definition, source, at, Acquisition.EXTERNAL_SEED, new Origin(null, null, source, at), null, null);
        Publication result = publish(proposal);
        observedThrough = at;
        return result;
    }

    public List<Publication> recordTaskCompletion(
            int taskId, long jobAttemptId, DataLocation actualVm, boolean succeeded, double now) {
        List<DataflowFilePlan.FileId> outputIds = plan.getOutputFiles(taskId);
        requireKnown(locations, actualVm);
        if (jobAttemptId < 0 || actualVm.getKind() != DataLocation.Kind.VM) {
            throw bad("Task outcome needs nonnegative attempt ID and an actual VM location");
        }
        double at = time(now);
        JobObservation job = jobObservations.get(jobAttemptId);
        if (job != null && !job.matches(actualVm, at)) {
            throw new IllegalStateException("A Job attempt has one actual VM and one completion observation");
        }
        Attempt key = new Attempt(taskId, jobAttemptId);
        Completion previous = completions.get(key);
        if (previous != null) {
            if (!previous.matches(actualVm, succeeded, at)) {
                throw new IllegalStateException("A Task/Job attempt outcome cannot be rewritten");
            }
            return Collections.emptyList();
        }
        List<Replica> proposals = new ArrayList<>();
        if (succeeded) {
            Origin origin = new Origin(taskId, jobAttemptId, actualVm, at);
            for (DataflowFilePlan.FileId file : outputIds) {
                proposals.add(
                        new Replica(plan.getFile(file), actualVm, at, Acquisition.TASK_OUTPUT, origin, null, null));
            }
        }
        List<Publication> published = new ArrayList<>();
        for (Replica proposal : proposals) {
            published.add(publish(proposal));
        }
        if (job == null) {
            jobObservations = jobObservations.with(jobAttemptId, new JobObservation(actualVm, at));
        }
        completions = completions.with(key, new Completion(actualVm, succeeded, at));
        observedThrough = at;
        return Collections.unmodifiableList(published);
    }

    public CopyTicket admitCopy(
            DataflowFilePlan.FileId file, DataLocation source, DataLocation destination, double now) {
        DataflowFilePlan.FileDefinition definition = plan.getFile(file);
        requireKnown(locations, source);
        requireKnown(locations, destination);
        double at = time(now);
        if (definition.getBytes() == 0) {
            throw bad("Zero-byte references do not admit positive copies");
        }
        if (!Double.isFinite(Math.nextUp(at))) {
            throw bad("Positive copy admission has no finite future observation");
        }
        Replica replica = requireVisible(file, source);
        if (visible(file, destination) != null) {
            throw new IllegalStateException("Destination already has a visible replica; no copy is needed");
        }
        Target target = new Target(file, destination);
        if (byTarget.containsKey(target)) {
            throw new IllegalStateException("An active copy already exists; join its ticket instead");
        }
        if (nextOrdinal == Long.MAX_VALUE) {
            throw new IllegalStateException("Copy identity range exhausted");
        }
        CopyTicket ticket = new CopyTicket(ticketOwner, nextOrdinal, replica, destination, at);
        active.put(ticket.ordinal, ticket);
        byTarget.put(target, ticket);
        nextOrdinal++;
        observedThrough = at;
        return ticket;
    }

    public Publication settleCopy(CopyTicket ticket, double now) {
        if (ticket == null || ticket.owner != ticketOwner) {
            throw bad("Copy ticket belongs to another run or is null");
        }
        if (active.get(ticket.ordinal) != ticket) {
            throw new IllegalStateException("Copy is not active");
        }
        double at = time(now);
        if (at <= ticket.releaseTime) {
            throw bad("Positive copy settlement must be observed after its admission");
        }
        Replica proposal = new Replica(
                ticket.source.file,
                ticket.destination,
                at,
                Acquisition.COPY_SETTLEMENT,
                ticket.source.origin,
                ticket.source.location,
                ticket.ordinal);
        Publication result = publish(proposal);
        active.remove(ticket.ordinal);
        byTarget.remove(new Target(ticket.source.file.getId(), ticket.destination));
        observedThrough = at;
        return result;
    }

    public Publication resolveZeroReference(
            DataflowFilePlan.FileId file, DataLocation source, DataLocation destination, double now) {
        DataflowFilePlan.FileDefinition definition = plan.getFile(file);
        requireKnown(locations, source);
        requireKnown(locations, destination);
        double at = time(now);
        if (definition.getBytes() != 0) {
            throw bad("Only zero-byte references can be resolved without positive copy service");
        }
        Replica replica = requireVisible(file, source);
        Publication result = publish(new Replica(
                definition, destination, at, Acquisition.ZERO_BYTE_REFERENCE, replica.origin, source, null));
        observedThrough = at;
        return result;
    }

    public Publication commitZeroOutput(
            DataflowFilePlan.FileId file, DataLocation source, DataLocation destination, double now) {
        DataflowFilePlan.FileDefinition definition = plan.getFile(file);
        requireKnown(locations, source);
        requireKnown(locations, destination);
        double at = time(now);
        if (definition.isExternal()
                || definition.getBytes() != 0
                || source.getKind() != DataLocation.Kind.VM
                || destination.getKind() != DataLocation.Kind.SOURCE) {
            throw bad("Zero output needs a produced object, VM source and store destination");
        }
        Replica replica = requireVisible(file, source);
        Publication result = publish(
                new Replica(definition, destination, at, Acquisition.ZERO_BYTE_OUTPUT, replica.origin, source, null));
        observedThrough = at;
        return result;
    }

    private Replica requireVisible(DataflowFilePlan.FileId file, DataLocation source) {
        Replica replica = visible(file, source);
        if (replica == null) {
            throw new IllegalStateException("Source does not hold a visible replica: " + file + " at " + source);
        }
        return replica;
    }

    private Replica visible(DataflowFilePlan.FileId file, DataLocation location) {
        Map<DataLocation, Replica> copies = replicas.get(file);
        return copies == null ? null : copies.get(location);
    }

    private Publication publish(Replica proposal) {
        Map<DataLocation, Replica> previous = replicas.get(proposal.file.getId());
        Replica existing = previous == null ? null : previous.get(proposal.location);
        if (existing != null) {
            return new Publication(existing, false);
        }

        Map<DataLocation, Replica> copies = previous == null ? new TreeMap<>() : new TreeMap<>(previous);
        copies.put(proposal.location, proposal);
        replicas = replicas.with(proposal.file.getId(), Collections.unmodifiableMap(copies));
        return new Publication(proposal, true);
    }

    private double time(double now) {
        if (!Double.isFinite(now) || now < 0 || now < observedThrough) {
            throw bad("Observation time must be finite, nonnegative and monotonic");
        }
        return now == 0 ? 0 : now;
    }

    private static void requireKnown(Set<DataLocation> locations, DataLocation location) {
        if (location == null || !locations.contains(location)) {
            throw bad("Unknown data location: " + location);
        }
    }

    private static <T> List<T> immutableList(Collection<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    private static final class Target {
        final DataflowFilePlan.FileId file;
        final DataLocation location;

        Target(DataflowFilePlan.FileId file, DataLocation location) {
            this.file = file;
            this.location = location;
        }

        @Override
        public int hashCode() {
            return 31 * file.hashCode() + location.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Target)) {
                return false;
            }
            Target target = (Target) other;
            return file.equals(target.file) && location.equals(target.location);
        }
    }

    private static final class Attempt {
        final int task;
        final long job;

        Attempt(int task, long job) {
            this.task = task;
            this.job = job;
        }

        @Override
        public int hashCode() {
            return 31 * task + Long.hashCode(job);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Attempt)) {
                return false;
            }
            Attempt attempt = (Attempt) other;
            return task == attempt.task && job == attempt.job;
        }
    }

    private static final class JobObservation {
        final DataLocation location;
        final double time;

        JobObservation(DataLocation location, double time) {
            this.location = location;
            this.time = time;
        }

        boolean matches(DataLocation location, double time) {
            return this.location.equals(location) && this.time == time;
        }
    }

    private static final class Completion {
        final DataLocation location;
        final boolean success;
        final double time;

        Completion(DataLocation location, boolean success, double time) {
            this.location = location;
            this.success = success;
            this.time = time;
        }

        boolean matches(DataLocation location, boolean success, double time) {
            return this.location.equals(location) && this.success == success && this.time == time;
        }
    }
}
