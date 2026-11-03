package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.Job;
import org.workflowsim.utils.Parameters.ClassType;

/**
 * Per-VM static Job order, private to one run.
 *
 * <p>A full static DAG planner writes the Job sequence of each VM; planners that only produce
 * a Task-to-VM mapping deliberately return an empty plan. This object constrains the static
 * dispatch order only and does not interpret the model as a real machine queue.</p>
 */
public final class StaticSchedulePlan {

    private static final StaticSchedulePlan EMPTY =
            new StaticSchedulePlan(Collections.<Integer, List<Integer>>emptyMap());

    private final Map<Integer, List<Integer>> jobIdsByVm;
    private final Map<Integer, Integer> nextIndexByVm;

    private StaticSchedulePlan(Map<Integer, List<Integer>> source) {
        Map<Integer, List<Integer>> frozen = new LinkedHashMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, List<Integer>> entry : source.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<Integer>(entry.getValue())));
        }
        this.jobIdsByVm = Collections.unmodifiableMap(frozen);
        this.nextIndexByVm = new LinkedHashMap<Integer, Integer>();
        for (Integer vmId : frozen.keySet()) {
            this.nextIndexByVm.put(vmId, 0);
        }
    }

    /** Returns the empty plan, for mapping-only or historical-compatibility planners. */
    public static StaticSchedulePlan empty() {
        return EMPTY;
    }

    /**
     * Builds a plan after a clustering-free conversion of planned Tasks into Jobs.
     *
     * <p>If no compute Job carries a planned start time, the run is still mapping-only and Job
     * order is not enforced. Equal start times on the same VM are broken by ascending Job
     * ID.</p>
     */
    public static StaticSchedulePlan fromJobs(List<Job> jobs) {
        Map<Integer, List<Job>> grouped = new LinkedHashMap<Integer, List<Job>>();
        boolean anyPlanned = false;
        for (Job job : jobs) {
            if (job.getClassType() != ClassType.COMPUTE.value) {
                continue;
            }
            double plannedStart = job.getStaticScheduleStartTime();
            if (Double.isNaN(plannedStart)) {
                continue;
            }
            anyPlanned = true;
            List<Job> items = grouped.get(job.getVmId());
            if (items == null) {
                items = new ArrayList<Job>();
                grouped.put(job.getVmId(), items);
            }
            items.add(job);
        }
        if (!anyPlanned) {
            return empty();
        }
        for (Job job : jobs) {
            if (job.getClassType() == ClassType.COMPUTE.value && Double.isNaN(job.getStaticScheduleStartTime())) {
                throw new IllegalStateException("Static schedule is incomplete for compute job " + job.getCloudletId());
            }
        }

        Map<Integer, List<Integer>> ordered = new LinkedHashMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, List<Job>> entry : grouped.entrySet()) {
            List<Job> items = entry.getValue();
            Collections.sort(items, new Comparator<Job>() {
                @Override
                public int compare(Job first, Job second) {
                    int byStart =
                            Double.compare(first.getStaticScheduleStartTime(), second.getStaticScheduleStartTime());
                    return byStart != 0 ? byStart : Integer.compare(first.getCloudletId(), second.getCloudletId());
                }
            });
            List<Integer> ids = new ArrayList<Integer>();
            for (Job job : items) {
                ids.add(job.getCloudletId());
            }
            ordered.put(entry.getKey(), ids);
        }
        return new StaticSchedulePlan(ordered);
    }

    public boolean isEnforcingOrder() {
        return !jobIdsByVm.isEmpty();
    }

    public boolean isPlanned(Job job) {
        List<Integer> ids = jobIdsByVm.get(job.getVmId());
        return ids != null && ids.contains(job.getCloudletId());
    }

    /** Returns the only compute Job that the given VM may dispatch next under this static plan. */
    public Integer nextExpectedJobId(int vmId) {
        List<Integer> ids = jobIdsByVm.get(vmId);
        if (ids == null) {
            return null;
        }
        int index = nextIndexByVm.get(vmId);
        return index < ids.size() ? ids.get(index) : null;
    }

    /** Advances the plan cursor of the VM only after the expected Job has actually been dispatched. */
    public void markDispatched(Job job) {
        Integer expected = nextExpectedJobId(job.getVmId());
        if (expected == null || expected.intValue() != job.getCloudletId()) {
            throw new IllegalStateException(
                    "Static plan dispatch order violation for job " + job.getCloudletId() + " on VM " + job.getVmId());
        }
        nextIndexByVm.put(job.getVmId(), nextIndexByVm.get(job.getVmId()) + 1);
    }
}
