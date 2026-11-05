package org.workflowsim.planning.timed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit full-duplex link power and per-edge communication times for CaWoSched. */
public final class CaWoSchedCommunicationSpec {

    private final int sourceVmId;
    private final int destinationVmId;
    private final int resourceId;
    private final double idleWatts;
    private final double activeWatts;
    private final Map<String, Double> durationSecondsByEdge;

    public CaWoSchedCommunicationSpec(
            int sourceVmId,
            int destinationVmId,
            int resourceId,
            double idleWatts,
            double activeWatts,
            Map<String, Double> durationSecondsByEdge) {
        if (sourceVmId < 0 || destinationVmId < 0 || resourceId < 0 || sourceVmId == destinationVmId) {
            throw new IllegalArgumentException(
                    "Communication link endpoints and resource ID must be distinct and non-negative");
        }
        if (!(idleWatts >= 0.0)
                || !Double.isFinite(idleWatts)
                || !(activeWatts >= idleWatts)
                || !Double.isFinite(activeWatts)) {
            throw new IllegalArgumentException("Communication link power must satisfy 0 <= idle <= active");
        }
        if (durationSecondsByEdge == null || durationSecondsByEdge.isEmpty()) {
            throw new IllegalArgumentException("Communication link requires explicit per-edge durations");
        }
        for (Map.Entry<String, Double> entry : durationSecondsByEdge.entrySet()) {
            if (entry.getKey() == null
                    || !entry.getKey().matches("[0-9]+->[0-9]+")
                    || entry.getValue() == null
                    || !(entry.getValue() > 0.0)
                    || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("Invalid communication edge duration: " + entry.getKey());
            }
        }
        this.sourceVmId = sourceVmId;
        this.destinationVmId = destinationVmId;
        this.resourceId = resourceId;
        this.idleWatts = idleWatts;
        this.activeWatts = activeWatts;
        this.durationSecondsByEdge =
                Collections.unmodifiableMap(new LinkedHashMap<String, Double>(durationSecondsByEdge));
    }

    public int getSourceVmId() {
        return sourceVmId;
    }

    public int getDestinationVmId() {
        return destinationVmId;
    }

    public int getResourceId() {
        return resourceId;
    }

    public double getIdleWatts() {
        return idleWatts;
    }

    public double getActiveWatts() {
        return activeWatts;
    }

    public double durationFor(int parentTaskId, int childTaskId) {
        Double duration = durationSecondsByEdge.get(parentTaskId + "->" + childTaskId);
        if (duration == null) {
            throw new IllegalArgumentException(
                    "No declared communication duration for " + parentTaskId + "->" + childTaskId);
        }
        return duration.doubleValue();
    }
}
