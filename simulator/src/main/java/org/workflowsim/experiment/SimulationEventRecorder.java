package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.Job;
import org.workflowsim.Task;

public final class SimulationEventRecorder {

    private static final SimulationEventRecorder DISABLED = new SimulationEventRecorder(false, 0);

    public static final int DEFAULT_MAX_EVENTS = 20000;

    private final boolean enabled;
    private final int maxEvents;
    private final List<SimulationEvent> events;
    private long nextSequence;

    public SimulationEventRecorder() {
        this(true, DEFAULT_MAX_EVENTS);
    }

    public SimulationEventRecorder(int maxEvents) {
        this(true, checkedCap(maxEvents));
    }

    private SimulationEventRecorder(boolean enabled, int maxEvents) {
        this.enabled = enabled;
        this.maxEvents = maxEvents;
        this.events = enabled ? new ArrayList<SimulationEvent>() : Collections.<SimulationEvent>emptyList();
    }

    private static int checkedCap(int maxEvents) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("Event retention cap must be positive: " + maxEvents);
        }
        return maxEvents;
    }

    public static SimulationEventRecorder disabled() {
        return DISABLED;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void record(SimulationEventType type, double simulationTime) {
        record(type, simulationTime, null, Collections.<String, Object>emptyMap());
    }

    public synchronized void record(
            SimulationEventType type, double simulationTime, Job job, Map<String, ?> attributes) {
        if (!enabled) {
            return;
        }
        if (type == null || Double.isNaN(simulationTime) || Double.isInfinite(simulationTime)) {
            throw new IllegalArgumentException("Event type and finite simulation time are required");
        }
        if (events.size() >= maxEvents) {
            throw new IllegalStateException("Simulation event recorder reached its capacity cap of "
                    + maxEvents
                    + " events; the event stream stays bounded instead of growing unbounded. The "
                    + "largest benchmark-standard-v1 run records 9008 events. Raise the "
                    + "eventRecorderCap of the run configuration (SimulationConfig builder; "
                    + "workbench simulation.eventRecorderCap), or use disabled() when the event "
                    + "stream is not needed.");
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

    public synchronized List<SimulationEvent> snapshot() {
        if (!enabled) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<SimulationEvent>(events));
    }

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
