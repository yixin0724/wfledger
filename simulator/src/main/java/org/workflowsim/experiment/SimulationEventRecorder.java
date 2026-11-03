package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.Job;
import org.workflowsim.Task;

/**
 * Ordered event recorder private to a single run.
 *
 * <p>An instance deliberately depends on no legacy static state of WorkflowSim, so that
 * consecutive sessions stay isolated from one another.</p>
 */
public final class SimulationEventRecorder {

    private static final SimulationEventRecorder DISABLED = new SimulationEventRecorder(false);

    private final boolean enabled;
    private final List<SimulationEvent> events;
    private long nextSequence;

    /** Create an enabled recorder that serves a single simulation run. */
    public SimulationEventRecorder() {
        this(true);
    }

    private SimulationEventRecorder(boolean enabled) {
        this.enabled = enabled;
        this.events = enabled ? new ArrayList<SimulationEvent>() : Collections.<SimulationEvent>emptyList();
    }

    /**
     * Return the no-op recorder used by legacy entry points.
     *
     * @return shared recorder that accepts recording calls safely without storing events
     */
    public static SimulationEventRecorder disabled() {
        return DISABLED;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Record a simulation event that carries no Job association.
     *
     * @param type event type, must not be null
     * @param simulationTime finite simulation time at which the event occurs
     * @throws IllegalArgumentException if the event type is null or the simulation time is not finite
     */
    public synchronized void record(SimulationEventType type, double simulationTime) {
        record(type, simulationTime, null, Collections.<String, Object>emptyMap());
    }

    /**
     * Record a simulation event that may carry a Job association and additional attributes.
     *
     * @param type event type, must not be null
     * @param simulationTime finite simulation time at which the event occurs
     * @param job optional associated Job; when null, no Job, VM, class or task identifier is written
     * @param attributes optional additional attributes, copied when the event is recorded
     * @throws IllegalArgumentException if the event type is null or the simulation time is not finite
     */
    public synchronized void record(
            SimulationEventType type, double simulationTime, Job job, Map<String, ?> attributes) {
        if (!enabled) {
            return;
        }
        if (type == null || Double.isNaN(simulationTime) || Double.isInfinite(simulationTime)) {
            throw new IllegalArgumentException("Event type and finite simulation time are required");
        }
        List<Integer> taskIds = new ArrayList<Integer>();
        Integer jobId = null;
        Integer vmId = null;
        Integer classType = null;
        if (job != null) {
            jobId = job.getCloudletId();
            vmId = job.getVmId();
            classType = job.getClassType();
            for (Task task : job.getTaskList()) {
                taskIds.add(task.getCloudletId());
            }
        }
        Map<String, Object> copiedAttributes = new LinkedHashMap<String, Object>();
        if (attributes != null) {
            copiedAttributes.putAll(attributes);
        }
        events.add(new SimulationEvent(
                nextSequence++, simulationTime, type, jobId, vmId, classType, taskIds, copiedAttributes));
    }

    /**
     * Return an immutable snapshot in original event order.
     *
     * @return immutable copy of the events recorded so far; a disabled recorder returns an empty list
     */
    public synchronized List<SimulationEvent> snapshot() {
        if (!enabled) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<SimulationEvent>(events));
    }

    /**
     * Build a small deterministic event attribute map from alternating keys and values.
     *
     * @param values alternating {@link String} keys and values; empty yields an empty map
     * @return attribute map that preserves the order given by the caller
     * @throws IllegalArgumentException if the element count is odd or a key is not a string
     */
    public static Map<String, Object> attributes(Object... values) {
        if (values == null || values.length == 0) {
            return Collections.emptyMap();
        }
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Event attributes require key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            if (!(values[index] instanceof String)) {
                throw new IllegalArgumentException("Event attribute keys must be strings");
            }
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
