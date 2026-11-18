package org.workflowsim.planning.timed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommunicationEnhancedPlan {

    private CommunicationEnhancedPlan() {}

    public static TimedPlan fromComputePlan(TimedPlan computePlan, List<CaWoSchedCommunicationSpec> links) {
        if (computePlan == null || links == null) {
            throw new IllegalArgumentException("Compute plan and communication links are required");
        }
        Map<String, CaWoSchedCommunicationSpec> linksByEndpoints =
                new LinkedHashMap<String, CaWoSchedCommunicationSpec>();
        for (CaWoSchedCommunicationSpec link : links) {
            String key = link.getSourceVmId() + "->" + link.getDestinationVmId();
            if (linksByEndpoints.put(key, link) != null) {
                throw new IllegalArgumentException("Duplicate communication link " + key);
            }
            for (TimedActivity activity : computePlan.getActivities()) {
                if (activity.getResourceId() == link.getResourceId()) {
                    throw new IllegalArgumentException("Communication resource collides with a compute VM");
                }
            }
        }
        Map<Integer, Double> declaredIdle =
                new LinkedHashMap<Integer, Double>(computePlan.getDeclaredIdleWattsByResource());
        for (CaWoSchedCommunicationSpec link : links) {
            declaredIdle.put(link.getResourceId(), link.getIdleWatts());
        }
        int nextActivityId = 0;
        for (TimedActivity activity : computePlan.getActivities()) {
            nextActivityId = Math.max(nextActivityId, activity.getActivityId() + 1);
        }
        Map<Integer, List<Integer>> orderByLink = new LinkedHashMap<Integer, List<Integer>>();
        List<TimedActivity> result = new ArrayList<TimedActivity>();
        for (TimedActivity activity : computePlan.getActivities()) {
            List<Integer> predecessors = new ArrayList<Integer>();
            for (Integer parentId : activity.getPredecessorActivityIds()) {
                TimedActivity parent = computePlan.getActivity(parentId);
                if (parent.getResourceId() == activity.getResourceId()) {
                    predecessors.add(parentId);
                    continue;
                }
                CaWoSchedCommunicationSpec link =
                        linksByEndpoints.get(parent.getResourceId() + "->" + activity.getResourceId());
                if (link == null) {
                    throw new IllegalArgumentException(
                            "Missing communication link for " + parentId + "->" + activity.getActivityId());
                }
                int communicationId = nextActivityId++;
                int order = orderByLink
                        .computeIfAbsent(link.getResourceId(), key -> new ArrayList<Integer>())
                        .size();
                orderByLink.get(link.getResourceId()).add(communicationId);
                result.add(new TimedActivity(
                        communicationId,
                        parent.getActivityId(),
                        TimedActivityType.COMMUNICATION,
                        link.getResourceId(),
                        order,
                        link.durationFor(parentId, activity.getActivityId()),
                        parent.getFinishSeconds(),
                        link.getIdleWatts(),
                        link.getActiveWatts(),
                        Collections.singletonList(parentId)));
                predecessors.add(communicationId);
            }
            Collections.sort(predecessors);
            result.add(new TimedActivity(
                    activity.getActivityId(),
                    activity.getLogicalTaskId(),
                    activity.getType(),
                    activity.getResourceId(),
                    activity.getOrderOnResource(),
                    activity.getDurationSeconds(),
                    activity.getStartSeconds(),
                    activity.getIdleWatts(),
                    activity.getActiveWatts(),
                    predecessors));
        }
        return new TimedPlan(result, computePlan.getCarbonWindows(), computePlan.getDeadlineSeconds(), declaredIdle);
    }
}
