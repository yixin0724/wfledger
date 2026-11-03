package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.workflowsim.Task;

/**
 * Communication-aware HEFT static DAG planner aligned with the controlled LOCAL file-system
 * execution model of WorkflowSim.
 *
 * <p><b>Algorithm</b> (Topcuoglu, Hariri &amp; Wu, IEEE TPDS 2002): select tasks in
 * descending order of the upward rank
 * {@code r_u(t) = w_bar_t + max_child(c_bar_edge + r_u(child))}; for each task compute the
 * insertion-based earliest finish time (EFT, idle gaps may be filled) over the compatible
 * VMs, choose the minimum EFT, break EFT ties by the smaller VM ID, and break rank ties by
 * the smaller task ID.</p>
 *
 * <p><b>Communication modeling</b>: accumulate {@code size / (1e6 * maxBwth)} per real input
 * file; SOURCE-to-VM uses the target bandwidth, VM-to-VM the smaller bandwidth of both
 * sides. A target replica already available when the task dependency becomes ready needs no
 * transfer, and any other visible replica uses the fastest source. A replica record carries
 * an earliest-available time: an input is available when its whole transfer hold completes
 * and an output when the planned computation completes; the plan assignment itself does not
 * make a future replica readable early.</p>
 *
 * <p><b>Execution-semantics alignment</b>: consistent with the AST (Actual Start Time)
 * semantics of the paper -- input transfer is <em>pre-execution network delay</em>, not VM
 * work: the files of each parent task start transferring at its finish time (parents in
 * parallel) and may overlap the busy period of the target VM; the planned start time of a
 * task is {@code max(VM insertable idle time, input ready time)}, and the VM is occupied
 * only by compute MI. Parent-task files retain the contention-free estimate
 * {@code parentFinish + sum(c_files_from_pred)}; external inputs (including those of root
 * tasks) start transferring when the dependencies of this Job become ready. A positive hold
 * is clamped by the minimum event interval of the runtime model. Compute cost is converted
 * uniformly through {@code TaskExecutionModel}: {@code round(seconds * mips) / mips} with a
 * matrix, otherwise the raw single-PE MI/mips.</p>
 *
 * <p><b>Applicability</b>: LOCAL file-system, NONE clustering, no failure, no modeled
 * overhead, the {@code preExecutionTransferDelayV1} data movement model, and SPACE_SHARED
 * VMs; enforced by {@link PlanningContext#validateLocalStaticDag()}. The per-task VM mapping
 * and planned start times written by the planner are enforced by
 * {@code StaticSchedulePlan} as the runtime per-VM dispatch order.</p>
 *
 * <p><b>Boundary statement</b>: link contention and the full event queue are not modeled;
 * replicas are supplied only by already planned tasks, and a later planned task producing a
 * replica earlier, same-instant event ordering, the completion rules of short computations,
 * and concurrent contention may all make the plan differ from the runtime. It is HEFT on an
 * abstract model, not a calibration against a real platform. LOCAL_CPOP and LOCAL_PEFT
 * share the estimation machinery of {@link AbstractLocalCommPlanningAlgorithm}.</p>
 */
public final class LocalHeftPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    public LocalHeftPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_HEFT", context);
    }

    @Override
    public void run() {
        List<Task> tasks = prepare();
        List<Task> priority = new ArrayList<Task>(tasks);
        Collections.sort(priority, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byRank = Double.compare(upwardRankOf(second), upwardRankOf(first));
                return byRank != 0 ? byRank : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        double stageInFinish = stageInFinishTime();
        for (Task task : priority) {
            allocate(task, null, stageInFinish);
        }
    }
}
