package org.workflowsim.planning.networkheft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stretches overlapping flows on one bottleneck using fair capacity sharing. */
public final class NetworkHeftFlowStretcher {

    public Map<String, Double> stretch(
            List<NetworkHeftTask> tasks,
            Map<Integer, Integer> mapping,
            Map<Integer, NetworkHeftSchedule.Entry> entries,
            double capacityBytesPerSecond) {
        List<Flow> flows = new ArrayList<Flow>();
        for (NetworkHeftTask parent : tasks) {
            NetworkHeftSchedule.Entry parentEntry = entries.get(parent.getTaskId());
            for (NetworkHeftEdge edge : parent.getEdgesByChild().values()) {
                NetworkHeftSchedule.Entry childEntry = entries.get(edge.getChildTaskId());
                if (parentEntry.getVmId() == childEntry.getVmId()) {
                    continue;
                }
                flows.add(new Flow(
                        parent.getTaskId(), edge, parentEntry.getFinishSeconds(), edge.getNominalDurationSeconds()));
            }
        }
        flows.sort(Comparator.comparingDouble(flow -> flow.start));
        Map<String, Double> finishByTask = new HashMap<String, Double>();
        double time = 0.0;
        int next = 0;
        List<Flow> active = new ArrayList<Flow>();
        while (next < flows.size() || !active.isEmpty()) {
            while (next < flows.size() && flows.get(next).start <= time + 1.0e-9) {
                active.add(flows.get(next));
                next++;
            }
            if (active.isEmpty()) {
                time = flows.get(next).start;
                continue;
            }
            double nextArrival = next < flows.size() ? flows.get(next).start : Double.POSITIVE_INFINITY;
            double nextCompletion = Double.POSITIVE_INFINITY;
            for (Flow flow : active) {
                nextCompletion =
                        Math.min(nextCompletion, time + flow.remaining * active.size() / capacityBytesPerSecond);
            }
            double end = Math.min(nextArrival, nextCompletion);
            for (Flow flow : active) {
                flow.remaining = Math.max(0.0, flow.remaining - capacityBytesPerSecond * (end - time) / active.size());
            }
            time = end;
            for (Flow flow : active) {
                if (flow.remaining <= 1.0e-9) {
                    finishByTask.put(flow.parentTaskId + "->" + flow.edge.getChildTaskId(), time - flow.start);
                }
            }
            active.removeIf(flow -> flow.remaining <= 1.0e-9);
        }
        return finishByTask;
    }

    private static final class Flow {
        private final int parentTaskId;
        private final NetworkHeftEdge edge;
        private final double start;
        private double remaining;

        private Flow(int parentTaskId, NetworkHeftEdge edge, double start, double nominalDuration) {
            this.parentTaskId = parentTaskId;
            this.edge = edge;
            this.start = start;
            this.remaining = edge.getDataBytes() > 0.0 ? edge.getDataBytes() : edge.getNominalDurationSeconds() * 100.0;
        }
    }
}
